package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.core.extension.isErrorState
import com.jamal_aliev.paginator.core.extension.isProgressState
import com.jamal_aliev.paginator.core.extension.isSuccessState
import com.jamal_aliev.paginator.core.load.Metadata
import com.jamal_aliev.paginator.core.logger.PaginatorLogger
import com.jamal_aliev.paginator.cursor.CursorPagingCore.Companion.UNLIMITED_CAPACITY
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.cache.CursorInMemoryPagingCache
import com.jamal_aliev.paginator.cursor.cache.CursorPagingCache
import com.jamal_aliev.paginator.cursor.cache.persistent.CursorPersistentPagingCache
import com.jamal_aliev.paginator.cursor.initializer.InitializerCursorErrorPage
import com.jamal_aliev.paginator.cursor.initializer.InitializerCursorProgressPage
import com.jamal_aliev.paginator.cursor.initializer.InitializerCursorSuccessPage
import com.jamal_aliev.paginator.cursor.page.CursorPageState
import com.jamal_aliev.paginator.cursor.serialization.CursorPageEntry
import com.jamal_aliev.paginator.cursor.serialization.CursorPagingCoreSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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
        get() = cache.cursors.mapNotNull { cache.getStateOf(it.self) }

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

    /** The left (head-side) boundary of the current context window. `null` = not started. */
    var startContextCursor: CursorBookmark<K>?
        get() = cache.startContextCursor
        internal set(value) {
            cache.startContextCursor = value
        }

    /** The right (tail-side) boundary of the current context window. `null` = not started. */
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

        // Indices (in head-to-tail order) of pages that are currently filled-success.
        val validIndices = ArrayList<Int>()
        var firstValid = -1
        var lastValid = -1
        for (i in ordered.indices) {
            if (isFilledSuccessState(cache.getStateOf(ordered[i].self))) {
                validIndices.add(i)
                if (firstValid == -1) firstValid = i
                lastValid = i
            }
        }
        if (validIndices.isEmpty()) {
            startContextCursor = null
            endContextCursor = null
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
        val nearestState: CursorPageState<K, T>? = cache.getStateOf(nearestCursor.self)
        startContextCursor = nearestCursor
        endContextCursor = nearestCursor
        expandStartContextCursor(nearestState, nearestCursor)
        expandEndContextCursor(nearestState, nearestCursor)
    }

    /** Retrieves the cached [PageState] by its `self` key. */
    fun getStateOf(self: K): CursorPageState<K, T>? = cache.getStateOf(self)

    /** Retrieves the cached [CursorBookmark<K>] by its `self` key. */
    fun getCursorOf(self: K): CursorBookmark<K>? = cache.getCursorOf(self)

    /**
     * Attempts to load a page from the [persistentCache] (L2) and promote it
     * into the in-memory [cache] (L1).
     */
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

    fun coerceToCapacity(data: List<T>): List<T> {
        val capacity = capacity
        if (isCapacityUnlimited || data.size <= capacity) return data
        return if (data.size / 2 >= capacity) {
            ArrayList<T>(capacity).apply { for (i in 0 until capacity) add(data[i]) }
        } else {
            ArrayList(data).apply { subList(capacity, size).clear() }
        }
    }

    fun coerceToCapacity(state: CursorPageState<K, T>): CursorPageState<K, T> {
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
            val nextState: CursorPageState<K, T>? = cache.getStateOf(nextCursor.self)
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
        _cacheFlow.value = states
    }

    // ── Snapshot ────────────────────────────────────────────────────────────

    private data class SnapshotEmission<K : Any, T>(
        val version: Long,
        val pages: List<CursorPageState<K, T>>,
        val startCursor: CursorBookmark<K>?,
        val endCursor: CursorBookmark<K>?,
    )

    private val _snapshot = MutableStateFlow(SnapshotEmission<K, T>(0L, emptyList(), null, null))

    /**
     * A [Flow] emitting the list of [PageState] objects within the current
     * context window. Mirrors [PagingCore.snapshot].
     */
    val snapshot: Flow<List<CursorPageState<K, T>>> = _snapshot.map { it.pages }

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
            val pivotBackwardState = cache.getStateOf(start.self) ?: return@run null
            val pivotForwardState = cache.getStateOf(end.self) ?: return@run null

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
        _snapshot.value = SnapshotEmission(prev.version + 1, scan(range), range.first, range.second)
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
            val state = cache.getStateOf(current.self)
            if (state != null) result.add(state)
            if (current.self == end.self) break
            current = cache.walkForward(current)
        }
        return result
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
        if (!silently) {
            val prev = _snapshot.value
            _snapshot.value = SnapshotEmission(prev.version + 1, emptyList(), null, null)
        }
        this.capacity = capacity
        _dirtyCursors.clear()
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
            val state = cache.getStateOf(cursor.self) ?: return@mapNotNull null
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

    fun restoreState(
        fromSnapshot: CursorPagingCoreSnapshot<T>,
        silently: Boolean = false,
        selfDecoder: (JsonElement) -> K,
        metadataDecoder: ((JsonElement?) -> Metadata?)? = null,
    ) {
        validateSnapshot(fromSnapshot)

        cache.clear()
        _dirtyCursors.clear()

        fromSnapshot.entries.forEach { entry ->
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
            cache.setState(bookmark, pageState, silently = true)
            if (entry.wasDirty) _dirtyCursors.add(selfKey)
        }

        capacity = fromSnapshot.capacity
        startContextCursor =
            fromSnapshot.startContextSelf?.let(selfDecoder)?.let { cache.getCursorOf(it) }
        endContextCursor =
            fromSnapshot.endContextSelf?.let(selfDecoder)?.let { cache.getCursorOf(it) }

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
