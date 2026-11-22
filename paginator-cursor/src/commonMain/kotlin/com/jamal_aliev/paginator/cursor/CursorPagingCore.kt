// The deprecated L2 persistent cache is still wired through here until it is removed.
@file:Suppress("DEPRECATION")

package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.core.extension.isErrorState
import com.jamal_aliev.paginator.core.extension.isProgressState
import com.jamal_aliev.paginator.core.extension.isSuccessState
import com.jamal_aliev.paginator.core.load.Metadata
import com.jamal_aliev.paginator.core.logger.LogComponent
import com.jamal_aliev.paginator.core.logger.PaginatorLogger
import com.jamal_aliev.paginator.core.logger.debug
import com.jamal_aliev.paginator.core.page.PageState
import com.jamal_aliev.paginator.cursor.CursorPagingCore.Companion.UNLIMITED_CAPACITY
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.cache.CursorInMemoryPagingCache
import com.jamal_aliev.paginator.cursor.cache.CursorPagingCache
import com.jamal_aliev.paginator.cursor.cache.UntrackedCursorPagingCache
import com.jamal_aliev.paginator.cursor.cache.persistent.CursorPersistentPagingCache
import com.jamal_aliev.paginator.cursor.initializer.InitializerCursorBootstrapErrorPage
import com.jamal_aliev.paginator.cursor.initializer.InitializerCursorBootstrapProgressPage
import com.jamal_aliev.paginator.cursor.initializer.InitializerCursorErrorPage
import com.jamal_aliev.paginator.cursor.initializer.InitializerCursorProgressPage
import com.jamal_aliev.paginator.cursor.initializer.InitializerCursorSuccessPage
import com.jamal_aliev.paginator.cursor.page.CursorBootstrapPageState
import com.jamal_aliev.paginator.cursor.page.CursorPageState
import com.jamal_aliev.paginator.cursor.serialization.CursorPageEntry
import com.jamal_aliev.paginator.cursor.serialization.CursorPagingCoreSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * Cursor-based counterpart of [PagingCore].
 *
 * Manages the page cache, context window, dirty-page tracking, snapshot emission
 * and capacity for a [CursorPaginator]. Cache storage is delegated to [cache]
 * (a [CursorPagingCache] implementation, typically [CursorInMemoryPagingCache]
 * or a chain of eviction strategies).
 *
 * **Doubly-linked, not indexed.** Pages are identified by their
 * [CursorBookmark<K>.self] key and ordered by following `next`/`prev` links, not
 * by numeric comparison. This means random-access queries like "find the page
 * closest to index N" are intentionally unavailable — if you need those, use
 * the classic [PagingCore].
 *
 * **Key concepts:**
 * - **Cache**: a keyed store of [PageState] + [CursorBookmark<K>] pairs.
 * - **Context window** ([startContextCursor]..[endContextCursor]): the
 *   contiguous chain of filled success pages visible to the UI via
 *   [snapshot]. A value of `null` on either side means "not started".
 * - **Capacity**: expected number of items per page. A page with fewer items
 *   than [capacity] is considered incomplete and may be re-requested on the
 *   next navigation. Use [UNLIMITED_CAPACITY] to disable the check.
 * - **Dirty cursors**: page keys marked for refresh on the next navigation.
 */
open class CursorPagingCore<K : Any, T>(
    val cache: CursorPagingCache<K, T> = CursorInMemoryPagingCache<K, T>(),
    @Deprecated(
        message = "The L2 persistent cache is deprecated since 10.3.0 and will be removed in a future major release. " +
            "It is no longer supported together with the rest of the library (refresh, eviction, transactions, " +
            "reactive sources) and may cause non-obvious bugs. Keep your database as the single source of truth " +
            "and bridge it with observe() instead.",
    )
    val persistentCache: CursorPersistentPagingCache<K, T>? = null,
    initialCapacity: Int = DEFAULT_CAPACITY,
) {

    /** Logger for observing cache operations (eviction, etc.). */
    var logger: PaginatorLogger? = null
        set(value) {
            field = value
            cache.logger = value
        }

    /** All cached bookmarks in head-to-tail order. */
    val cursors: List<CursorBookmark<K>> get() = cache.cursors

    /** All cached page states in head-to-tail order. */
    val states: List<CursorPageState<K, T>>
        get() = cache.cursors.mapNotNull { readState(it.self) }

    /** The number of pages currently in the cache. */
    val size: Int get() = cache.size

    /** Returns the tail bookmark of the cached chain, or `null` if empty. */
    fun tailCursor(): CursorBookmark<K>? = cache.tail()

    /** Returns the head bookmark of the cached chain, or `null` if empty. */
    fun headCursor(): CursorBookmark<K>? = cache.head()

    /**
     * The expected number of items per page.
     *
     * When a page contains fewer items than [capacity], it is considered
     * "incomplete" and will be re-requested on the next navigation.
     * A value of [UNLIMITED_CAPACITY] (0) disables capacity checks entirely.
     */
    var capacity: Int = initialCapacity
        internal set

    val isCapacityUnlimited: Boolean
        get() = capacity == UNLIMITED_CAPACITY

    /**
     * The left (head-side) boundary of the current context window. `null` = not started.
     *
     * When an eviction strategy removes a page of the window, the window narrows to the contiguous
     * chain of cached pages around the page navigation is on, so this boundary can move toward the
     * tail even though no navigation call asked for it.
     */
    var startContextCursor: CursorBookmark<K>?
        get() = cache.startContextCursor
        internal set(value) {
            cache.startContextCursor = value
        }

    /**
     * The right (tail-side) boundary of the current context window. `null` = not started.
     *
     * When an eviction strategy removes a page of the window, the window narrows to the contiguous
     * chain of cached pages around the page navigation is on, so this boundary can move toward the
     * head even though no navigation call asked for it.
     */
    var endContextCursor: CursorBookmark<K>?
        get() = cache.endContextCursor
        internal set(value) {
            cache.endContextCursor = value
        }

    /** `true` if both boundaries of the context window are set. */
    val isStarted: Boolean get() = cache.isStarted

    /**
     * Walks backward from [pageState]'s bookmark through contiguous filled success pages
     * and updates [startContextCursor] to the earliest bookmark found.
     */
    fun expandStartContextCursor(
        pageState: CursorPageState<K, T>?,
        cursor: CursorBookmark<K>?
    ): CursorPageState<K, T>? {
        cursor ?: return null
        return walkWhile(
            pageState,
            cursor,
            next = { _, c -> cache.walkBackward(c) },
            predicate = ::isFilledSuccessState
        )
            ?.also { (_, newCursor) -> startContextCursor = newCursor }
            ?.first
    }

    /**
     * Walks forward from [pageState]'s bookmark through contiguous filled success pages
     * and updates [endContextCursor] to the latest bookmark found.
     */
    fun expandEndContextCursor(
        pageState: CursorPageState<K, T>?,
        cursor: CursorBookmark<K>?
    ): CursorPageState<K, T>? {
        cursor ?: return null
        return walkWhile(
            pageState,
            cursor,
            next = { _, c -> cache.walkForward(c) },
            predicate = ::isFilledSuccessState
        )
            ?.also { (_, newCursor) -> endContextCursor = newCursor }
            ?.first
    }

    /**
     * Fallback anchoring used by [findNearContextCursor] when the cache holds **no** FULL
     * (`== capacity`) page — a short / partial-only feed. A non-empty success page is still a valid
     * *visible* anchor, so instead of clearing the window (which would stop the reactive snapshot,
     * see issue #2) this anchors on the success page nearest by chain distance to the current
     * [startCursor]..[endCursor] band and sets the window across its contiguous success run.
     *
     * The window is set **directly** because [expandStartContextCursor] / [expandEndContextCursor]
     * walk only FULL pages and would bail on a partial pivot. This is intentionally reached only
     * when no filled page exists: with filled pages present the strict filled-only expansion is
     * required so the window is never stretched across a non-filled page buried in its interior.
     * Only a cache with no non-empty success page at all stays cleared.
     */
    private fun anchorOnNearestSuccessRun(
        ordered: List<CursorBookmark<K>>,
        orderedStates: List<CursorPageState<K, T>?>,
        startCursor: CursorBookmark<K>?,
        endCursor: CursorBookmark<K>?,
    ) {
        val startIdx: Int =
            startCursor?.self?.let { s -> ordered.indexOfFirst { it.self == s } } ?: -1
        val endIdx: Int =
            endCursor?.self?.let { e -> ordered.indexOfFirst { it.self == e } } ?: -1
        var bestIdx = -1
        var bestDist = Int.MAX_VALUE
        for (i in ordered.indices) {
            if (!orderedStates[i].isSuccessState()) continue
            val dist: Int = when {
                startIdx >= 0 && i < startIdx -> startIdx - i
                endIdx >= 0 && i > endIdx -> i - endIdx
                else -> 0
            }
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
            }
        }
        if (bestIdx == -1) {
            startContextCursor = null
            endContextCursor = null
            return
        }
        val anchorCursor: CursorBookmark<K> = ordered[bestIdx]
        val anchorState: CursorPageState<K, T>? = orderedStates[bestIdx]
        startContextCursor = walkWhile(
            anchorState, anchorCursor,
            next = { _, c -> cache.walkBackward(c) },
            predicate = { it.isSuccessState() },
        )?.second ?: anchorCursor
        endContextCursor = walkWhile(
            anchorState, anchorCursor,
            next = { _, c -> cache.walkForward(c) },
            predicate = { it.isSuccessState() },
        )?.second ?: anchorCursor
    }

    /**
     * Re-anchors the context window to the nearest filled-success page group when the
     * current window boundaries no longer point at filled pages.
     *
     * This is the cursor counterpart of `PagingCore.findNearContextPage`. It is needed when
     * an operation (typically `refresh`/`refreshAll` after the backend deleted the currently
     * viewed range) leaves [startContextCursor]/[endContextCursor] pointing at empty-`Success`
     * pages: the snapshot would then collapse to a blank window even though other filled pages
     * are still cached. Since cursors have no numeric order, the search walks the cached chain
     * (head-to-tail [cursors]) and picks the filled page closest **by chain distance** to the
     * current window band, then expands outward through its contiguous filled group.
     *
     * If no filled-success page remains in the cache, both boundaries are cleared
     * ([isStarted] becomes `false`).
     *
     * @param startCursor Lower reference boundary of the search band. Defaults to [startContextCursor].
     * @param endCursor Upper reference boundary of the search band. Defaults to [endContextCursor].
     */
    fun findNearContextCursor(
        startCursor: CursorBookmark<K>? = startContextCursor,
        endCursor: CursorBookmark<K>? = endContextCursor,
    ) {
        val ordered: List<CursorBookmark<K>> = cache.cursors
        if (ordered.isEmpty()) {
            startContextCursor = null
            endContextCursor = null
            return
        }
        // Resolve every cached state once — it is read per cursor below and again in the fallback,
        // so materialise it a single time instead of re-looking-up per pass.
        val orderedStates: List<CursorPageState<K, T>?> = ordered.map { readState(it.self) }

        // Indices (in head-to-tail order) of pages that are currently filled-success.
        val validIndices = ArrayList<Int>()
        var firstValid = -1
        var lastValid = -1
        for (i in ordered.indices) {
            if (isFilledSuccessState(orderedStates[i])) {
                validIndices.add(i)
                if (firstValid == -1) firstValid = i
                lastValid = i
            }
        }
        if (validIndices.isEmpty()) {
            anchorOnNearestSuccessRun(ordered, orderedStates, startCursor, endCursor)
            return
        }

        // Reference band derived from the current (possibly stale) window. When a boundary
        // is no longer in the chain, fall back to the filled-page extremes.
        val startIdx: Int = startCursor?.self?.let { s -> ordered.indexOfFirst { it.self == s } } ?: -1
        val endIdx: Int = endCursor?.self?.let { e -> ordered.indexOfFirst { it.self == e } } ?: -1
        val bandLo: Int = if (startIdx >= 0) startIdx else firstValid
        val bandHi: Int = if (endIdx >= 0) endIdx else lastValid

        // Pick the filled page with the smallest chain distance to the band; ties resolve
        // toward the head (earlier range), matching the offset variant's bias.
        var bestIdx: Int = validIndices.first()
        var bestDist: Int = Int.MAX_VALUE
        for (vi in validIndices) {
            val dist: Int = when {
                vi < bandLo -> bandLo - vi
                vi > bandHi -> vi - bandHi
                else -> 0
            }
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = vi
            }
        }

        val nearestCursor: CursorBookmark<K> = ordered[bestIdx]
        val nearestState: CursorPageState<K, T>? = orderedStates[bestIdx]
        startContextCursor = nearestCursor
        endContextCursor = nearestCursor
        expandStartContextCursor(nearestState, nearestCursor)
        expandEndContextCursor(nearestState, nearestCursor)
    }

    /**
     * Restores the context window invariant after a page of the window may have left the cache.
     *
     * **Invariant:** while the paginator is started, every page on the chain of links from
     * [startContextCursor] to [endContextCursor] is cached, so both boundaries exist and the chain
     * is whole. An eviction strategy that runs inside [CursorPagingCache.setState] with
     * `protectContextWindow = false` (or [removeFromCache] called directly) can break it. A removed
     * page loses its bookmark too, so the walk from the start stops at the hole: [snapshot] would
     * go silent on a missing boundary or publish only the part of the window before the hole.
     *
     * **Rule ("the window follows the anchor"):** when a window page is missing, the window shrinks
     * to the contiguous run of cached pages **inside the old window** that contains the page with
     * the key [anchor] - the page navigation currently holds (the boundary being walked past while
     * loading, or the page just loaded). A run is found by walking `prev`/`next` links from a seed
     * page while the pages are cached and the old boundaries are not passed. Seeds are the anchor
     * and the two boundary pages (a missing boundary is replaced by its neighbour inside the
     * window; the stale boundary object keeps its links even without a cache entry). If the anchor
     * itself is not cached, the run on the side of the anchor is taken: the start side when the
     * anchor is the old start, the end side otherwise. If no seed run exists, the window is
     * re-anchored with [findNearContextCursor] around the old boundaries. Without bookmarks the
     * middle of a chain cannot be walked, so a run that is reachable neither from the anchor nor
     * from the boundary pages (or their neighbours inside the window) is not found: with several
     * holes the re-anchoring can then pick another chain of the cache even though a page of the old
     * window survives. A cached anchor is always found, even when it lies outside the old window
     * (the page being loaded after the whole window was removed).
     *
     * Only the presence of a page is checked ([CursorPagingCache.peekStateOf]), not whether it is
     * filled; the rules about filled and non-filled boundaries stay in the navigation code. Reads
     * are untracked: reconciling must not reorder an LRU strategy or restart a TTL timer.
     *
     * Call it under the navigation lock, before [snapshot], after anything that wrote to the cache
     * ([CursorPagingCache.setState]) or can have damaged the window. `restart` does not need it:
     * it clears the cache and sets a fresh single-page window, so a hole cannot appear.
     *
     * @param anchor The `self` key of the page navigation is anchored on; it should be a page of
     *   the old window (cached or already evicted).
     */
    internal fun reconcileContextWindow(anchor: K) {
        if (!isStarted) return
        val start: CursorBookmark<K> = startContextCursor ?: return
        val end: CursorBookmark<K> = endContextCursor ?: return

        fun isCached(self: K): Boolean = cache.peekStateOf(self) != null

        // Fast path: the chain from start reaches end through cached pages only (no allocations;
        // the budget guards against cyclic links)
        fun isWindowIntact(): Boolean {
            var current: CursorBookmark<K> = start
            var budget: Int = cache.size
            while (budget-- > 0 && isCached(current.self)) {
                if (current.self == end.self) return true
                current = cache.walkForward(current) ?: return false
            }
            return false
        }
        if (isWindowIntact()) return

        // The contiguous run of cached pages around [seed], never past the old boundaries
        fun runAround(seed: CursorBookmark<K>?): Pair<CursorBookmark<K>, CursorBookmark<K>>? {
            if (seed == null || !isCached(seed.self)) return null
            var first: CursorBookmark<K> = seed
            var budget: Int = cache.size // guards against cyclic links
            while (first.self != start.self && budget-- > 0) {
                val previous: CursorBookmark<K> = cache.walkBackward(first) ?: break
                if (!isCached(previous.self)) break
                first = previous
            }
            var last: CursorBookmark<K> = seed
            budget = cache.size
            while (last.self != end.self && budget-- > 0) {
                val next: CursorBookmark<K> = cache.walkForward(last) ?: break
                if (!isCached(next.self)) break
                last = next
            }
            return first to last
        }

        val isSinglePageWindow: Boolean = start.self == end.self
        val startSeed: CursorBookmark<K>? = cache.getCursorOf(start.self)?.takeIf { isCached(it.self) }
            ?: if (isSinglePageWindow) null else cache.walkForward(start)
        val endSeed: CursorBookmark<K>? = cache.getCursorOf(end.self)?.takeIf { isCached(it.self) }
            ?: if (isSinglePageWindow) null else cache.walkBackward(end)

        val chosen: Pair<CursorBookmark<K>, CursorBookmark<K>>? =
            runAround(cache.getCursorOf(anchor))
                ?: if (anchor == start.self) runAround(startSeed) ?: runAround(endSeed)
                else runAround(endSeed) ?: runAround(startSeed)

        if (chosen == null) {
            withUntrackedReads { findNearContextCursor(startCursor = start, endCursor = end) }
        } else {
            startContextCursor = chosen.first
            endContextCursor = chosen.second
        }
        logger.debug(LogComponent.CACHE) {
            "reconcileContextWindow: window ${start.self}..${end.self} -> " +
                    "${startContextCursor?.self}..${endContextCursor?.self} (anchor=$anchor)"
        }
    }

    /** Retrieves the cached [PageState] by its `self` key. */
    fun getStateOf(self: K): CursorPageState<K, T>? = readState(self)

    /**
     * While `true`, the core's own reads ([getStateOf], [states], the context walks and everything
     * built on them — [snapshot], [findNearContextCursor], context expansion) go through
     * [CursorPagingCache.peekStateOf], so they do not count as a use of the pages. Set only around
     * bookkeeping that is not user access (applying a reactive source's events), under the
     * navigation lock — see [withUntrackedReads].
     */
    @PublishedApi
    internal var untrackedReads: Boolean = false

    @PublishedApi
    internal fun readState(self: K): CursorPageState<K, T>? =
        if (untrackedReads) cache.peekStateOf(self) else cache.getStateOf(self)

    /** [cache] seen through [UntrackedCursorPagingCache], used by `CursorPaginator.cache` in untracked mode. */
    internal val untrackedCache: CursorPagingCache<K, T> by lazy { UntrackedCursorPagingCache(cache) }

    /** Runs [block] with [untrackedReads] on. Not reentrant-safe across coroutines: hold the navigation lock. */
    internal inline fun <R> withUntrackedReads(block: () -> R): R {
        val previous = untrackedReads
        untrackedReads = true
        try {
            return block()
        } finally {
            untrackedReads = previous
        }
    }

    /** Retrieves the cached [CursorBookmark<K>] by its `self` key. */
    fun getCursorOf(self: K): CursorBookmark<K>? = cache.getCursorOf(self)

    /**
     * Attempts to load a page from the [persistentCache] (L2) and promote it
     * into the in-memory [cache] (L1).
     */
    @Deprecated(
        message = "The L2 persistent cache is deprecated since 10.3.0 and will be removed in a future major release. " +
            "It is no longer supported together with the rest of the library (refresh, eviction, transactions, " +
            "reactive sources) and may cause non-obvious bugs. Keep your database as the single source of truth " +
            "and bridge it with observe() instead.",
    )
    suspend fun loadFromPersistentCache(self: K): Pair<CursorBookmark<K>, CursorPageState<K, T>>? {
        val pc = persistentCache ?: return null
        val persisted = pc.load(self) ?: return null
        cache.setState(persisted.first, persisted.second, silently = true)
        return persisted
    }

    /**
     * Stores a [state] under the [cursor] key. The [cursor] replaces any existing
     * bookmark for that `self` key, which is the mechanism used to update links
     * when the server returns new `prev`/`next` for an already-cached page.
     */
    fun setState(
        cursor: CursorBookmark<K>,
        state: CursorPageState<K, T>,
        silently: Boolean = false
    ) {
        cache.setState(cursor, state, silently = true)
        if (!silently) snapshot()
    }

    /** Removes the page identified by [self] from the cache. */
    fun removeFromCache(self: K): CursorPageState<K, T>? = cache.removeFromCache(self)

    /** Clears all pages from the cache. */
    fun clear() {
        cache.clear()
    }

    /** Retrieves a single element from the page identified by [self]. */
    fun getElement(self: K, index: Int): T? = cache.getElement(self, index)

    /**
     * Determines whether the given [PageState] represents a successfully loaded page
     * whose data set is considered "filled". Mirrors [PagingCore.isFilledSuccessState].
     */
    @OptIn(ExperimentalContracts::class)
    @Suppress("NOTHING_TO_INLINE")
    inline fun isFilledSuccessState(state: CursorPageState<K, T>?): Boolean {
        contract {
            returns(true) implies (state is CursorPageState.Success<K, T>)
        }
        if (!state.isSuccessState()) return false
        return isCapacityUnlimited || state.data.size == capacity
    }

    /**
     * Trims [data] to at most [capacity] elements.
     *
     * If [isCapacityUnlimited] is `true` or `data.size <= capacity`, the input is
     * returned as a mutable list (the input itself if it already is one, otherwise
     * a copy) — callers that cache a page state built from the result can rely on
     * its `data` being mutable for later CRUD mutations. Otherwise, a new mutable
     * list containing the first [capacity] elements is returned.
     */
    fun coerceToCapacity(data: List<T>): List<T> {
        val capacity = capacity
        if (isCapacityUnlimited || data.size <= capacity) {
            return data as? MutableList<T> ?: ArrayList(data)
        }
        return if (data.size / 2 >= capacity) {
            ArrayList<T>(capacity).apply { for (i in 0 until capacity) add(data[i]) }
        } else {
            ArrayList(data).apply { subList(capacity, size).clear() }
        }
    }

    fun coerceToCapacity(state: CursorPageState<K, T>): CursorPageState<K, T> {
        if (isCapacityUnlimited || state.data.size <= capacity) return state
        val newData = coerceToCapacity(state.data)
        return if (newData === state.data) state else state.copy(data = newData)
    }

    /**
     * Traverses the linked chain starting from [pivotState]/[pivotCursor], repeatedly
     * applying [next] to compute the next bookmark, as long as:
     *  - the computed bookmark exists in the cache,
     *  - its state satisfies [predicate].
     */
    inline fun walkWhile(
        pivotState: CursorPageState<K, T>?,
        pivotCursor: CursorBookmark<K>?,
        next: (state: CursorPageState<K, T>, cursor: CursorBookmark<K>) -> CursorBookmark<K>?,
        predicate: (CursorPageState<K, T>) -> Boolean = { true },
    ): Pair<CursorPageState<K, T>, CursorBookmark<K>>? {
        if (pivotState == null || pivotCursor == null) return null
        if (!predicate.invoke(pivotState)) return null

        var currentState: CursorPageState<K, T> = pivotState
        var currentCursor: CursorBookmark<K> = pivotCursor
        while (true) {
            val nextCursor: CursorBookmark<K> =
                next.invoke(currentState, currentCursor) ?: return currentState to currentCursor
            val nextState: CursorPageState<K, T>? = readState(nextCursor.self)
            if (nextState != null && predicate.invoke(nextState)) {
                currentState = nextState
                currentCursor = nextCursor
            } else {
                return currentState to currentCursor
            }
        }
    }

    // ── Cache flow ──────────────────────────────────────────────────────────

    var enableCacheFlow = false
        private set
    private val _cacheFlow = MutableStateFlow<List<CursorPageState<K, T>>>(emptyList())

    /** Returns a [Flow] that emits the **entire** cache list whenever it changes. */
    fun asFlow(): Flow<List<CursorPageState<K, T>>> {
        enableCacheFlow = true
        return _cacheFlow.asStateFlow()
    }

    /** Forces a re-emission of the current cache into [asFlow] subscribers. */
    fun repeatCacheFlow() {
        // Publishing the whole cache to asFlow() observers is not a use of every page: read it
        // untracked, so an LRU order or a TTL timer is not reshaped on each navigation call.
        _cacheFlow.value = withUntrackedReads { states }
    }

    // ── Snapshot ────────────────────────────────────────────────────────────

    /**
     * One published view of the paginator. [bootstrap] and [started] are captured together with
     * [pages], so the cursor-keyed [snapshot], the [visibleSnapshot] and `uiState` all derive from
     * the same moment.
     */
    private data class SnapshotEmission<K : Any, T>(
        val version: Long,
        val pages: List<CursorPageState<K, T>>,
        val startCursor: CursorBookmark<K>?,
        val endCursor: CursorBookmark<K>?,
        val bootstrap: PageState<T>? = null,
        val started: Boolean = false,
    )

    private val _snapshot = MutableStateFlow(SnapshotEmission<K, T>(0L, emptyList(), null, null))

    /**
     * A [Flow] emitting the list of [PageState] objects within the current
     * context window. Mirrors [PagingCore.snapshot].
     *
     * Only cursor-keyed pages appear here. While the anchorless first page is loading (or after
     * that load failed) the cache is empty and so is this list — use [visibleSnapshot] or
     * `uiState` to render that phase.
     */
    val snapshot: Flow<List<CursorPageState<K, T>>> = _snapshot.map { it.pages }

    /**
     * What the UI should show: the [snapshot] pages, or — during the anchorless first load — the
     * single [bootstrapState] page (a [PageState.ProgressState] while loading, a
     * [PageState.ErrorState] after a failure). Emits exactly when [snapshot] does.
     */
    val visibleSnapshot: Flow<List<PageState<T>>> = _snapshot.map { it.visible() }

    /** [visibleSnapshot] paired with whether the paginator counted as started at that emission. */
    internal val visibleEmissions: Flow<Pair<List<PageState<T>>, Boolean>> =
        _snapshot.map { it.visible() to it.started }

    private fun SnapshotEmission<K, T>.visible(): List<PageState<T>> =
        bootstrap?.let { listOf(it) } ?: pages

    // ── Bootstrap (anchorless first load) ───────────────────────────────────

    private val _bootstrapState = MutableStateFlow<PageState<T>?>(null)

    /**
     * State of the **anchorless first load** — `restart()` without `initialCursor`, or the implicit
     * bootstrap of `goNextPage()`. There is no cursor key until the source answers, so this phase
     * lives outside the key-addressed cache:
     *
     * - `null` — no anchorless first load is pending (not started yet, or the first page is cached);
     * - a [PageState.ProgressState] — `load(null)` is in flight;
     * - a [PageState.ErrorState] — it failed; the cache stays empty until a retry succeeds.
     */
    val bootstrapState: StateFlow<PageState<T>?> = _bootstrapState.asStateFlow()

    /** `true` while the anchorless first page is being loaded. */
    val isBootstrapping: Boolean
        get() = _bootstrapState.value is PageState.ProgressState

    /**
     * Sets [bootstrapState]. With [emit], also publishes it (the cache is empty in this phase, so
     * the keyed part of the emission is empty too); otherwise the next [snapshot] call carries it.
     */
    internal fun setBootstrapState(state: PageState<T>?, emit: Boolean) {
        _bootstrapState.value = state
        if (emit) {
            val prev = _snapshot.value
            _snapshot.value = SnapshotEmission(
                version = prev.version + 1,
                pages = emptyList(),
                startCursor = null,
                endCursor = null,
                bootstrap = state,
                started = state != null || isStarted,
            )
        }
    }

    /**
     * Returns the `(startCursor, endCursor)` pair currently visible in the last
     * emitted snapshot, or `null` if the snapshot is empty.
     */
    fun snapshotCursorRange(): Pair<CursorBookmark<K>, CursorBookmark<K>>? {
        val emission = _snapshot.value
        val start = emission.startCursor ?: return null
        val end = emission.endCursor ?: return null
        return start to end
    }

    /**
     * Returns the list of `self` keys currently visible in the last emitted snapshot,
     * in head-to-tail order. Useful for checking whether a candidate cursor is
     * already visible.
     */
    fun snapshotSelves(): List<K> {
        val emission = _snapshot.value
        if (emission.pages.isEmpty()) return emptyList()
        val start = emission.startCursor ?: return emptyList()
        val end = emission.endCursor ?: return emptyList()
        val result = ArrayList<K>()
        val visited = HashSet<K>()
        var current: CursorBookmark<K>? = start
        while (current != null && visited.add(current.self)) {
            result.add(current.self)
            if (current.self == end.self) break
            current = cache.walkForward(current)
        }
        return result
    }

    /**
     * Emits the current visible state to [snapshot] subscribers. When [cursorRange]
     * is `null`, the range is computed from [startContextCursor] / [endContextCursor]
     * extended outward through any adjacent non-success pages.
     */
    fun snapshot(cursorRange: Pair<CursorBookmark<K>, CursorBookmark<K>>? = null) {
        val range: Pair<CursorBookmark<K>, CursorBookmark<K>>? = cursorRange ?: run {
            if (!isStarted) return@run null
            val start = startContextCursor ?: return@run null
            val end = endContextCursor ?: return@run null
            val pivotBackwardState = readState(start.self) ?: return@run null
            val pivotForwardState = readState(end.self) ?: return@run null

            val backwardExpand = walkWhile(
                pivotBackwardState, start,
                next = { _, c -> cache.walkBackward(c) },
                predicate = ::isFilledSuccessState,
            )
            val forwardExpand = walkWhile(
                pivotForwardState, end,
                next = { _, c -> cache.walkForward(c) },
                predicate = ::isFilledSuccessState,
            )

            val backwardEdge: CursorBookmark<K>? =
                backwardExpand?.second?.let { cache.walkBackward(it) }
            val forwardEdge: CursorBookmark<K>? =
                forwardExpand?.second?.let { cache.walkForward(it) }

            val min: CursorBookmark<K> = backwardEdge ?: backwardExpand?.second ?: start
            val max: CursorBookmark<K> = forwardEdge ?: forwardExpand?.second ?: end
            return@run min to max
        }

        range ?: return
        val prev = _snapshot.value
        val bootstrap: PageState<T>? = _bootstrapState.value
        _snapshot.value = SnapshotEmission(
            version = prev.version + 1,
            pages = scan(range),
            startCursor = range.first,
            endCursor = range.second,
            bootstrap = bootstrap,
            started = isStarted || bootstrap != null,
        )
    }

    /**
     * Returns a list of cached [PageState] objects walked from [cursorRange].first
     * to [cursorRange].second inclusive.
     */
    fun scan(
        cursorRange: Pair<CursorBookmark<K>, CursorBookmark<K>> = run {
            val start = checkNotNull(startContextCursor) {
                "You cannot scan because startContextCursor is null"
            }
            val end = checkNotNull(endContextCursor) {
                "You cannot scan because endContextCursor is null"
            }
            start to end
        }
    ): List<CursorPageState<K, T>> {
        val (start, end) = cursorRange
        val visited = HashSet<K>()
        val result = ArrayList<CursorPageState<K, T>>()
        var current: CursorBookmark<K>? = start
        while (current != null && visited.add(current.self)) {
            val state = readState(current.self)
            if (state != null) result.add(state)
            if (current.self == end.self) break
            current = cache.walkForward(current)
        }
        return result
    }

    // ── Wholesale replacement ───────────────────────────────────────────────

    /**
     * Bumped whenever the cache content is **replaced wholesale** rather than changed page by page
     * — [restoreState] (also when silent), a transaction rollback and [release]. Consumers that keep
     * state in sync with the cache (the reactive bridge of `observe`) treat it as "every page may
     * now hold foreign data" and resynchronise.
     */
    internal val cacheReplacements = MutableStateFlow(0L)

    internal fun markCacheReplaced() {
        cacheReplacements.update { it + 1 }
    }

    // ── Dirty tracking ──────────────────────────────────────────────────────

    private val _dirtyCursors: MutableSet<K> = mutableSetOf()
    val dirtyCursors: Set<K> get() = _dirtyCursors.toSet()

    fun markDirty(self: K) {
        _dirtyCursors.add(self)
    }

    fun markDirty(selves: Collection<K>) {
        _dirtyCursors.addAll(selves)
    }

    fun clearDirty(self: K) {
        _dirtyCursors.remove(self)
    }

    fun clearDirty(selves: Collection<K>) {
        _dirtyCursors.removeAll(selves.toSet())
    }

    fun clearAllDirty() {
        _dirtyCursors.clear()
    }

    fun isDirty(self: K): Boolean = self in _dirtyCursors

    fun isDirtyCursorsEmpty(): Boolean = _dirtyCursors.isEmpty()

    /**
     * Returns dirty cursors that sit on the chain between [startCursor] and [endCursor]
     * (walking `next`), removing them from the dirty set.
     */
    fun drainDirtyCursorsInRange(
        startCursor: CursorBookmark<K>,
        endCursor: CursorBookmark<K>,
    ): List<K>? {
        if (_dirtyCursors.isEmpty()) return null
        val inRange = HashSet<K>()
        val visited = HashSet<K>()
        var current: CursorBookmark<K>? = startCursor
        while (current != null && visited.add(current.self)) {
            if (current.self in _dirtyCursors) inRange.add(current.self)
            if (current.self == endCursor.self) break
            current = cache.walkForward(current)
        }
        if (inRange.isEmpty()) return null
        _dirtyCursors.removeAll(inRange)
        return inRange.toList()
    }

    // ── Initializers ────────────────────────────────────────────────────────

    var initializerProgressPage: InitializerCursorProgressPage<K, T> =
        fun(
            bookmark: CursorBookmark<K>,
            data: List<T>,
            metadata: Metadata?
        ): CursorPageState.Progress<K, T> {
            return CursorPageState.Progress(bookmark = bookmark, data = data, metadata = metadata)
        }

    var initializerSuccessPage: InitializerCursorSuccessPage<K, T> =
        fun(
            bookmark: CursorBookmark<K>,
            data: List<T>,
            metadata: Metadata?
        ): CursorPageState.Success<K, T> {
            return CursorPageState.Success(bookmark = bookmark, data = data, metadata = metadata)
        }

    var initializerErrorPage: InitializerCursorErrorPage<K, T> =
        fun(
            exception: Exception,
            bookmark: CursorBookmark<K>,
            data: List<T>,
            metadata: Metadata?
        ): CursorPageState.Error<K, T> {
            return CursorPageState.Error(
                exception = exception,
                bookmark = bookmark,
                data = data,
                metadata = metadata
            )
        }

    /**
     * Factory for the Progress state of the **anchorless first load** (see [bootstrapState]). The
     * keyed [initializerProgressPage] cannot be used there because no bookmark exists yet. Override
     * it to show a skeleton (e.g. a [CursorBootstrapPageState.Progress] subclass with placeholders).
     */
    var initializerBootstrapProgressPage: InitializerCursorBootstrapProgressPage<T> =
        { CursorBootstrapPageState.Progress() }

    /** Factory for the Error state of a failed **anchorless first load** (see [bootstrapState]). */
    var initializerBootstrapErrorPage: InitializerCursorBootstrapErrorPage<T> =
        { exception: Exception -> CursorBootstrapPageState.Error(exception) }

    /**
     * Cursor-based pagination does not support in-place `resize` the way the
     * offset variant does: there is no numeric page index to redistribute items
     * into, and every page is keyed by a server-provided `self`. Change
     * [capacity] directly if you only need to tighten/relax the capacity check
     * for subsequent loads.
     */
    fun resize(capacity: Int): Unit = throw UnsupportedOperationException(
        "CursorPagingCore does not support resize — cursors are not reassignable by index."
    )

    fun release(
        capacity: Int = DEFAULT_CAPACITY,
        silently: Boolean = false,
    ) {
        cache.release(capacity, silently)
        _bootstrapState.value = null
        if (!silently) {
            val prev = _snapshot.value
            _snapshot.value = SnapshotEmission(prev.version + 1, emptyList(), null, null)
        }
        this.capacity = capacity
        _dirtyCursors.clear()
        markCacheReplaced()
    }

    // ── Serialization ───────────────────────────────────────────────────────

    fun saveState(
        contextOnly: Boolean = false,
        selfEncoder: (K) -> JsonElement,
        metadataEncoder: ((Metadata?) -> JsonElement?)? = null,
    ): CursorPagingCoreSnapshot<T> {
        val allCursors = cache.cursors
        val inContext: Set<K>? =
            if (contextOnly && isStarted) contextSelves() else null
        val filtered =
            if (inContext != null) allCursors.filter { it.self in inContext } else allCursors

        val entries = filtered.mapNotNull { cursor ->
            val state = readState(cursor.self) ?: return@mapNotNull null
            val wasDirty = isDirty(cursor.self)
                    || state.isErrorState()
                    || state.isProgressState()
            val errorMessage: String? =
                if (state.isErrorState()) state.exception.message else null

            CursorPageEntry(
                selfKey = selfEncoder.invoke(cursor.self),
                prevKey = cursor.prev?.let(selfEncoder),
                nextKey = cursor.next?.let(selfEncoder),
                data = state.data,
                wasDirty = wasDirty,
                errorMessage = errorMessage,
                metadata = metadataEncoder?.invoke(state.metadata),
            )
        }
        return CursorPagingCoreSnapshot(
            entries = entries,
            startContextSelf = startContextCursor?.self?.let(selfEncoder),
            endContextSelf = endContextCursor?.self?.let(selfEncoder),
            capacity = capacity,
        )
    }

    /**
     * Restores this core's state from a previously saved [fromSnapshot].
     *
     * The capacity and the context window are restored **before** the pages are written, so an
     * eviction strategy judges the restored pages by the restored window, not by the window the
     * paginator had before the call. Every entry is decoded once, the window boundaries are the
     * very bookmarks that are inserted afterwards; a boundary whose `self` has no entry in the
     * snapshot is `null` (the paginator is then not started).
     *
     * The pages are written in the order of the chain (from every head along `next`, orphans and
     * cycles last), whatever the order of the snapshot's entries: a window strategy reaches the
     * pages of the window by walking the links that are already written, so a snapshot that is
     * assembled by hand or reordered must not cost pages. A snapshot made by [saveState] already
     * lists its main chain in that order. The strategy's own bookkeeping is not restored: the LRU/FIFO
     * order is the write order (every restored page counts as just inserted), and the timers of a TTL
     * strategy start anew. If the strategy evicts pages of the restored window while they are written
     * (a snapshot bigger than its `maxSize` with `protectContextWindow = false`), the window narrows to
     * the contiguous run that survived.
     */
    fun restoreState(
        fromSnapshot: CursorPagingCoreSnapshot<T>,
        silently: Boolean = false,
        selfDecoder: (JsonElement) -> K,
        metadataDecoder: ((JsonElement?) -> Metadata?)? = null,
    ) {
        validateSnapshot(fromSnapshot)

        cache.clear()
        _dirtyCursors.clear()
        _bootstrapState.value = null

        class RestoredPage(
            val bookmark: CursorBookmark<K>,
            val state: CursorPageState<K, T>,
            val wasDirty: Boolean,
        )

        val restored: List<RestoredPage> = fromSnapshot.entries.map { entry ->
            val selfKey: K = selfDecoder.invoke(entry.selfKey)
            val prevKey: K? = entry.prevKey?.let(selfDecoder)
            val nextKey: K? = entry.nextKey?.let(selfDecoder)
            val metadata: Metadata? = metadataDecoder?.invoke(entry.metadata)
            val bookmark = CursorBookmark<K>(prev = prevKey, self = selfKey, next = nextKey)
            val pageState: CursorPageState<K, T> = initializerSuccessPage(
                bookmark,
                entry.data.toMutableList(),
                metadata,
            )
            RestoredPage(bookmark, pageState, entry.wasDirty)
        }

        // Entries in chain order: every head (no `prev`, or a `prev` that is not part of the snapshot)
        // in order of appearance, followed along `next`; whatever no head reaches (cycles) goes last
        // in the original order. A snapshot that is already in chain order stays as it is.
        fun inChainOrder(pages: List<RestoredPage>): List<RestoredPage> {
            val bySelf = HashMap<K, RestoredPage>(pages.size * 2)
            for (page in pages) bySelf[page.bookmark.self] = page
            val written = HashSet<K>(pages.size * 2)
            val ordered = ArrayList<RestoredPage>(pages.size)
            for (page in pages) {
                val prev: K? = page.bookmark.prev
                if (prev != null && prev in bySelf) continue // not a head
                var current: RestoredPage? = page
                while (current != null && written.add(current.bookmark.self)) {
                    ordered.add(current)
                    current = current.bookmark.next?.let { bySelf[it] }
                }
            }
            for (page in pages) {
                if (written.add(page.bookmark.self)) ordered.add(page)
            }
            return ordered
        }

        val startSelf: K? = fromSnapshot.startContextSelf?.let(selfDecoder)
        val endSelf: K? = fromSnapshot.endContextSelf?.let(selfDecoder)
        capacity = fromSnapshot.capacity
        startContextCursor = startSelf?.let { self -> restored.firstOrNull { it.bookmark.self == self }?.bookmark }
        endContextCursor = endSelf?.let { self -> restored.firstOrNull { it.bookmark.self == self }?.bookmark }

        inChainOrder(restored).forEach { page ->
            cache.setState(page.bookmark, page.state, silently = true)
            if (page.wasDirty) _dirtyCursors.add(page.bookmark.self)
        }

        // A snapshot bigger than the cache strategy allows can have evicted pages of the window
        startContextCursor?.let { reconcileContextWindow(anchor = it.self) }
        markCacheReplaced()

        if (!silently) snapshot()
    }

    private fun validateSnapshot(fromSnapshot: CursorPagingCoreSnapshot<T>) {
        require(fromSnapshot.capacity > 0) {
            "Snapshot capacity must be > 0, but was ${fromSnapshot.capacity}"
        }
        val selves = HashSet<JsonElement>()
        for (entry in fromSnapshot.entries) {
            require(selves.add(entry.selfKey)) {
                "Duplicate self key: ${entry.selfKey}"
            }
        }
    }

    private fun contextSelves(): Set<K> {
        val start = startContextCursor ?: return emptySet()
        val end = endContextCursor ?: return emptySet()
        val result = HashSet<K>()
        val visited = HashSet<K>()
        var current: CursorBookmark<K>? = start
        while (current != null && visited.add(current.self)) {
            result.add(current.self)
            if (current.self == end.self) break
            current = cache.walkForward(current)
        }
        return result
    }

    operator fun iterator(): Iterator<CursorPageState<K, T>> = states.iterator()

    operator fun contains(self: K): Boolean = getStateOf(self) != null

    operator fun get(self: K): CursorPageState<K, T>? = getStateOf(self)

    operator fun get(self: K, index: Int): T? = getElement(self, index)

    override fun toString(): String = "CursorPagingCore(size=$size)"

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = super.hashCode()

    companion object {
        const val DEFAULT_CAPACITY = 20
        const val UNLIMITED_CAPACITY = 0
    }
}
