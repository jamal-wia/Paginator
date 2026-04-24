package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.CursorPagingCore.Companion.UNLIMITED_CAPACITY
import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cache.CursorPagingCache
import com.jamal_aliev.paginator.cache.CursorPersistentPagingCache
import com.jamal_aliev.paginator.cache.DefaultCursorPagingCache
import com.jamal_aliev.paginator.extension.isErrorState
import com.jamal_aliev.paginator.extension.isProgressState
import com.jamal_aliev.paginator.extension.isSuccessState
import com.jamal_aliev.paginator.initializer.InitializerEmptyPage
import com.jamal_aliev.paginator.initializer.InitializerErrorPage
import com.jamal_aliev.paginator.initializer.InitializerProgressPage
import com.jamal_aliev.paginator.initializer.InitializerSuccessPage
import com.jamal_aliev.paginator.load.Metadata
import com.jamal_aliev.paginator.logger.PaginatorLogger
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PageState.EmptyPage
import com.jamal_aliev.paginator.page.PageState.ErrorPage
import com.jamal_aliev.paginator.page.PageState.ProgressPage
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import com.jamal_aliev.paginator.serialization.CursorPageEntry
import com.jamal_aliev.paginator.serialization.CursorPagingCoreSnapshot
import com.jamal_aliev.paginator.serialization.PageEntryType
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
 * (a [CursorPagingCache] implementation, typically [DefaultCursorPagingCache]
 * or a chain of eviction strategies).
 *
 * **Doubly-linked, not indexed.** Pages are identified by their
 * [CursorBookmark.self] key and ordered by following `next`/`prev` links, not
 * by numeric comparison. This means random-access queries like "find the page
 * closest to index N" are intentionally unavailable — if you need those, use
 * the classic [PagingCore].
 *
 * **Key concepts:**
 * - **Cache**: a keyed store of [PageState] + [CursorBookmark] pairs.
 * - **Context window** ([startContextCursor]..[endContextCursor]): the
 *   contiguous chain of filled success pages visible to the UI via
 *   [snapshot]. A value of `null` on either side means "not started".
 * - **Capacity**: expected number of items per page. A page with fewer items
 *   than [capacity] is considered incomplete and may be re-requested on the
 *   next navigation. Use [UNLIMITED_CAPACITY] to disable the check.
 * - **Dirty cursors**: page keys marked for refresh on the next navigation.
 */
open class CursorPagingCore<T>(
    val cache: CursorPagingCache<T> = DefaultCursorPagingCache(),
    val persistentCache: CursorPersistentPagingCache<T>? = null,
    initialCapacity: Int = DEFAULT_CAPACITY,
) {

    /** Logger for observing cache operations (eviction, etc.). */
    var logger: PaginatorLogger? = null
        set(value) {
            field = value
            cache.logger = value
        }

    /** All cached bookmarks in head-to-tail order. */
    val cursors: List<CursorBookmark> get() = cache.cursors

    /** All cached page states in head-to-tail order. */
    val states: List<PageState<T>>
        get() = cache.cursors.mapNotNull { cache.getStateOf(it.self) }

    /** The number of pages currently in the cache. */
    val size: Int get() = cache.size

    /** Returns the tail bookmark of the cached chain, or `null` if empty. */
    fun tailCursor(): CursorBookmark? = cache.tail()

    /** Returns the head bookmark of the cached chain, or `null` if empty. */
    fun headCursor(): CursorBookmark? = cache.head()

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
    var startContextCursor: CursorBookmark?
        get() = cache.startContextCursor
        internal set(value) {
            cache.startContextCursor = value
        }

    /** The right (tail-side) boundary of the current context window. `null` = not started. */
    var endContextCursor: CursorBookmark?
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
    fun expandStartContextCursor(pageState: PageState<T>?, cursor: CursorBookmark?): PageState<T>? {
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
    fun expandEndContextCursor(pageState: PageState<T>?, cursor: CursorBookmark?): PageState<T>? {
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

    /** Retrieves the cached [PageState] by its `self` key. */
    fun getStateOf(self: Any): PageState<T>? = cache.getStateOf(self)

    /** Retrieves the cached [CursorBookmark] by its `self` key. */
    fun getCursorOf(self: Any): CursorBookmark? = cache.getCursorOf(self)

    /**
     * Attempts to load a page from the [persistentCache] (L2) and promote it
     * into the in-memory [cache] (L1).
     */
    suspend fun loadFromPersistentCache(self: Any): Pair<CursorBookmark, PageState<T>>? {
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
        cursor: CursorBookmark,
        state: PageState<T>,
        silently: Boolean = false
    ) {
        cache.setState(cursor, state, silently = true)
        if (!silently) snapshot()
    }

    /** Removes the page identified by [self] from the cache. */
    fun removeFromCache(self: Any): PageState<T>? = cache.removeFromCache(self)

    /** Clears all pages from the cache. */
    fun clear() {
        cache.clear()
    }

    /** Retrieves a single element from the page identified by [self]. */
    fun getElement(self: Any, index: Int): T? = cache.getElement(self, index)

    /**
     * Determines whether the given [PageState] represents a successfully loaded page
     * whose data set is considered "filled". Mirrors [PagingCore.isFilledSuccessState].
     */
    @OptIn(ExperimentalContracts::class)
    @Suppress("NOTHING_TO_INLINE")
    inline fun isFilledSuccessState(state: PageState<T>?): Boolean {
        contract {
            returns(true) implies (state is SuccessPage<T>)
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

    fun coerceToCapacity(state: PageState<T>): PageState<T> {
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
        pivotState: PageState<T>?,
        pivotCursor: CursorBookmark?,
        next: (state: PageState<T>, cursor: CursorBookmark) -> CursorBookmark?,
        predicate: (PageState<T>) -> Boolean = { true },
    ): Pair<PageState<T>, CursorBookmark>? {
        if (pivotState == null || pivotCursor == null) return null
        if (!predicate.invoke(pivotState)) return null

        var currentState: PageState<T> = pivotState
        var currentCursor: CursorBookmark = pivotCursor
        while (true) {
            val nextCursor: CursorBookmark =
                next.invoke(currentState, currentCursor) ?: return currentState to currentCursor
            val nextState: PageState<T>? = cache.getStateOf(nextCursor.self)
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
    private val _cacheFlow = MutableStateFlow<List<PageState<T>>>(emptyList())

    /** Returns a [Flow] that emits the **entire** cache list whenever it changes. */
    fun asFlow(): Flow<List<PageState<T>>> {
        enableCacheFlow = true
        return _cacheFlow.asStateFlow()
    }

    /** Forces a re-emission of the current cache into [asFlow] subscribers. */
    fun repeatCacheFlow() {
        _cacheFlow.value = states
    }

    // ── Snapshot ────────────────────────────────────────────────────────────

    private data class SnapshotEmission<T>(
        val version: Long,
        val pages: List<PageState<T>>,
        val startCursor: CursorBookmark?,
        val endCursor: CursorBookmark?,
    )

    private val _snapshot = MutableStateFlow(SnapshotEmission<T>(0L, emptyList(), null, null))

    /**
     * A [Flow] emitting the list of [PageState] objects within the current
     * context window. Mirrors [PagingCore.snapshot].
     */
    val snapshot: Flow<List<PageState<T>>> = _snapshot.map { it.pages }

    /**
     * Returns the `(startCursor, endCursor)` pair currently visible in the last
     * emitted snapshot, or `null` if the snapshot is empty.
     */
    fun snapshotCursorRange(): Pair<CursorBookmark, CursorBookmark>? {
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
    fun snapshotSelves(): List<Any> {
        val emission = _snapshot.value
        if (emission.pages.isEmpty()) return emptyList()
        val start = emission.startCursor ?: return emptyList()
        val end = emission.endCursor ?: return emptyList()
        val result = ArrayList<Any>()
        val visited = HashSet<Any>()
        var current: CursorBookmark? = start
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
    fun snapshot(cursorRange: Pair<CursorBookmark, CursorBookmark>? = null) {
        val range: Pair<CursorBookmark, CursorBookmark>? = cursorRange ?: run {
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

            val backwardEdge: CursorBookmark? =
                backwardExpand?.second?.let { cache.walkBackward(it) }
            val forwardEdge: CursorBookmark? =
                forwardExpand?.second?.let { cache.walkForward(it) }

            val min: CursorBookmark = backwardEdge ?: backwardExpand?.second ?: start
            val max: CursorBookmark = forwardEdge ?: forwardExpand?.second ?: end
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
        cursorRange: Pair<CursorBookmark, CursorBookmark> = run {
            val start = checkNotNull(startContextCursor) {
                "You cannot scan because startContextCursor is null"
            }
            val end = checkNotNull(endContextCursor) {
                "You cannot scan because endContextCursor is null"
            }
            start to end
        }
    ): List<PageState<T>> {
        val (start, end) = cursorRange
        val visited = HashSet<Any>()
        val result = ArrayList<PageState<T>>()
        var current: CursorBookmark? = start
        while (current != null && visited.add(current.self)) {
            val state = cache.getStateOf(current.self)
            if (state != null) result.add(state)
            if (current.self == end.self) break
            current = cache.walkForward(current)
        }
        return result
    }

    // ── Dirty tracking ──────────────────────────────────────────────────────

    private val _dirtyCursors: MutableSet<Any> = mutableSetOf()
    val dirtyCursors: Set<Any> get() = _dirtyCursors.toSet()

    fun markDirty(self: Any) {
        _dirtyCursors.add(self)
    }

    fun markDirty(selves: Collection<Any>) {
        _dirtyCursors.addAll(selves)
    }

    fun clearDirty(self: Any) {
        _dirtyCursors.remove(self)
    }

    fun clearDirty(selves: Collection<Any>) {
        _dirtyCursors.removeAll(selves.toSet())
    }

    fun clearAllDirty() {
        _dirtyCursors.clear()
    }

    fun isDirty(self: Any): Boolean = self in _dirtyCursors

    fun isDirtyCursorsEmpty(): Boolean = _dirtyCursors.isEmpty()

    /**
     * Returns dirty cursors that sit on the chain between [startCursor] and [endCursor]
     * (walking `next`), removing them from the dirty set.
     */
    fun drainDirtyCursorsInRange(
        startCursor: CursorBookmark,
        endCursor: CursorBookmark,
    ): List<Any>? {
        if (_dirtyCursors.isEmpty()) return null
        val inRange = HashSet<Any>()
        val visited = HashSet<Any>()
        var current: CursorBookmark? = startCursor
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

    var initializerProgressPage: InitializerProgressPage<T> =
        fun(page: Int, data: List<T>, metadata: Metadata?): ProgressPage<T> {
            return ProgressPage(page = page, data = data, metadata = metadata)
        }

    var initializerSuccessPage: InitializerSuccessPage<T> =
        fun(page: Int, data: List<T>, metadata: Metadata?): SuccessPage<T> {
            return if (data.isEmpty()) initializerEmptyPage.invoke(page, data, metadata)
            else SuccessPage(page = page, data = data, metadata = metadata)
        }

    var initializerEmptyPage: InitializerEmptyPage<T> =
        fun(page: Int, data: List<T>, metadata: Metadata?): EmptyPage<T> {
            return EmptyPage(page = page, data = data, metadata = metadata)
        }

    var initializerErrorPage: InitializerErrorPage<T> =
        fun(exception: Exception, page: Int, data: List<T>, metadata: Metadata?): ErrorPage<T> {
            return ErrorPage(exception = exception, page = page, data = data, metadata = metadata)
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
        selfEncoder: (Any) -> JsonElement,
        metadataEncoder: ((Metadata?) -> JsonElement?)? = null,
    ): CursorPagingCoreSnapshot<T> {
        val allCursors = cache.cursors
        val inContext: Set<Any>? =
            if (contextOnly && isStarted) contextSelves() else null
        val filtered =
            if (inContext != null) allCursors.filter { it.self in inContext } else allCursors

        val entries = filtered.mapNotNull { cursor ->
            val state = cache.getStateOf(cursor.self) ?: return@mapNotNull null
            val wasDirty = isDirty(cursor.self)
                    || state.isErrorState()
                    || state.isProgressState()
            val type = if (state.data.isEmpty()) PageEntryType.EMPTY else PageEntryType.SUCCESS
            val errorMessage: String? =
                if (state.isErrorState()) state.exception.message else null

            CursorPageEntry(
                selfKey = selfEncoder.invoke(cursor.self),
                prevKey = cursor.prev?.let(selfEncoder),
                nextKey = cursor.next?.let(selfEncoder),
                type = type,
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
        selfDecoder: (JsonElement) -> Any,
        metadataDecoder: ((JsonElement?) -> Metadata?)? = null,
    ) {
        validateSnapshot(fromSnapshot)

        cache.clear()
        _dirtyCursors.clear()

        fromSnapshot.entries.forEach { entry ->
            val selfKey: Any = selfDecoder.invoke(entry.selfKey)
            val prevKey: Any? = entry.prevKey?.let(selfDecoder)
            val nextKey: Any? = entry.nextKey?.let(selfDecoder)
            val metadata: Metadata? = metadataDecoder?.invoke(entry.metadata)
            // `page` carried by PageState is unused by the cursor cache; synthesise
            // a unique sequential value so the factories do not collide on it.
            val syntheticPage: Int =
                (PageState.nextId() and Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
            val pageState: PageState<T> = when (entry.type) {
                PageEntryType.EMPTY -> initializerEmptyPage(syntheticPage, entry.data, metadata)
                PageEntryType.SUCCESS -> initializerSuccessPage(
                    syntheticPage,
                    entry.data.toMutableList(),
                    metadata,
                )
            }
            val bookmark = CursorBookmark(prev = prevKey, self = selfKey, next = nextKey)
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

    private fun contextSelves(): Set<Any> {
        val start = startContextCursor ?: return emptySet()
        val end = endContextCursor ?: return emptySet()
        val result = HashSet<Any>()
        val visited = HashSet<Any>()
        var current: CursorBookmark? = start
        while (current != null && visited.add(current.self)) {
            result.add(current.self)
            if (current.self == end.self) break
            current = cache.walkForward(current)
        }
        return result
    }

    operator fun iterator(): Iterator<PageState<T>> = states.iterator()

    operator fun contains(self: Any): Boolean = getStateOf(self) != null

    operator fun get(self: Any): PageState<T>? = getStateOf(self)

    operator fun get(self: Any, index: Int): T? = getElement(self, index)

    override fun toString(): String = "CursorPagingCore(size=$size)"

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = super.hashCode()

    companion object {
        const val DEFAULT_CAPACITY = 20
        const val UNLIMITED_CAPACITY = 0
    }
}
