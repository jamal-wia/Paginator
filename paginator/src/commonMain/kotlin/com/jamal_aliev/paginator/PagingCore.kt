package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.PagingCore.Companion.DEFAULT_CAPACITY
import com.jamal_aliev.paginator.PagingCore.Companion.UNLIMITED_CAPACITY
import com.jamal_aliev.paginator.cache.DefaultPagingCache
import com.jamal_aliev.paginator.cache.PagingCache
import com.jamal_aliev.paginator.cache.PersistentPagingCache
import com.jamal_aliev.paginator.extension.gap
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
import com.jamal_aliev.paginator.serialization.PageEntry
import com.jamal_aliev.paginator.serialization.PageEntryType
import com.jamal_aliev.paginator.serialization.PagingCoreSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * Manages the page cache, context window, dirty page tracking, snapshot emission,
 * and capacity for a [Paginator].
 *
 * This class orchestrates all cache-related state and operations, delegating
 * raw storage to [cache] (a [PagingCache] implementation, typically
 * [DefaultPagingCache] or a chain of eviction strategies).
 *
 * Navigation logic lives in [Paginator]; this class is connected to it via composition.
 *
 * **Key concepts:**
 * - **Cache**: a sorted list of [PageState] objects ordered by page number,
 *   managed by the underlying [PagingCache].
 * - **Context window** ([startContextPage]..[endContextPage]): the contiguous range of
 *   filled success pages visible to the UI via the [snapshot] flow.
 * - **Capacity**: the expected number of items per page. Pages with fewer items are
 *   considered "incomplete" and will be re-requested on the next navigation.
 * - **Dirty pages**: pages marked for refresh on the next navigation action.
 *
 * **Strategy safety:** [setState] delegates to [cache], so all writes pass through
 * the eviction strategy chain. Consumers cannot bypass the strategy even with
 * direct access to this class.
 *
 * @param cache The underlying [PagingCache] (optionally wrapped in eviction strategies).
 * @param initialCapacity The expected number of items per page. Default: [DEFAULT_CAPACITY].
 * @param T The type of elements contained in each page.
 */
open class PagingCore<T>(
    val cache: PagingCache<T> = DefaultPagingCache(),
    val persistentCache: PersistentPagingCache<T>? = null,
    initialCapacity: Int = DEFAULT_CAPACITY,
) {

    /**
     * Logger for observing cache operations (eviction, etc.).
     *
     * When set via [Paginator.logger], this is automatically kept in sync.
     */
    var logger: PaginatorLogger? = null
        set(value) {
            field = value
            cache.logger = value
        }

    /** All cached page numbers, sorted in ascending order. */
    val pages: List<Int> get() = cache.pages

    /** All cached page states, sorted by page number. */
    val states: List<PageState<T>>
        get() = cache.pages.mapNotNull { cache.getStateOf(it) }

    /** The number of pages currently in the cache. */
    val size: Int get() = cache.size

    /** Returns the highest page number in the cache, or `null` if empty. */
    fun lastPage(): Int? = cache.pages.lastOrNull()

    /**
     * The expected number of items per page.
     *
     * When a page contains fewer items than [capacity], it is considered
     * "incomplete" and will be re-requested on the next navigation.
     * A value of [UNLIMITED_CAPACITY] (0) disables capacity checks entirely.
     *
     * Default: [DEFAULT_CAPACITY] (20).
     */
    var capacity: Int = initialCapacity
        internal set

    /**
     * `true` if [capacity] equals [UNLIMITED_CAPACITY], meaning capacity checks are disabled.
     */
    val isCapacityUnlimited: Boolean
        get() = capacity == UNLIMITED_CAPACITY

    /**
     * The left (lowest) boundary of the current context window.
     *
     * Together with [endContextPage], defines the contiguous range of filled success pages
     * that are visible to the UI via the [snapshot] flow. A value of `0` means the
     * paginator has not been started yet.
     *
     * Updated automatically during navigation operations.
     */
    var startContextPage: Int
        get() = cache.startContextPage
        internal set(value) {
            cache.startContextPage = value
        }

    /**
     * The right (highest) boundary of the current context window.
     *
     * Together with [startContextPage], defines the contiguous range of filled success pages
     * that are visible to the UI via the [snapshot] flow. A value of `0` means the
     * paginator has not been started yet.
     *
     * Updated automatically during navigation operations.
     */
    var endContextPage: Int
        get() = cache.endContextPage
        internal set(value) {
            cache.endContextPage = value
        }

    /**
     * `true` if the paginator has been started, i.e., at least one jump has been performed
     * and both [startContextPage] and [endContextPage] are non-zero.
     */
    val isStarted: Boolean get() = cache.isStarted

    /**
     * Walks backward from [pageState] through contiguous filled success pages
     * and updates [startContextPage] to the earliest page found.
     *
     * @param pageState The starting page to expand backward from.
     * @return The earliest filled success page found, or `null` if [pageState] is not valid.
     */
    fun expandStartContextPage(pageState: PageState<T>?): PageState<T>? {
        return walkWhile(pageState, next = { it - 1 }, predicate = ::isFilledSuccessState)
            ?.also { startContextPage = it.page }
    }

    /**
     * Walks forward from [pageState] through contiguous filled success pages
     * and updates [endContextPage] to the latest page found.
     *
     * @param pageState The starting page to expand forward from.
     * @return The latest filled success page found, or `null` if [pageState] is not valid.
     */
    fun expandEndContextPage(pageState: PageState<T>?): PageState<T>? {
        return walkWhile(pageState, next = { it + 1 }, predicate = ::isFilledSuccessState)
            ?.also { endContextPage = it.page }
    }

    /**
     * Finds the nearest contiguous group of filled success pages to the given
     * [startPoint]..[endPoint] range and sets [startContextPage]/[endContextPage] accordingly.
     *
     * Uses binary search over all valid (filled success) pages to efficiently locate
     * the closest group. When the exact point is found, the context is expanded around it.
     * When no exact match exists, the algorithm computes distances from up to four pivot
     * candidates (left-of-start, right-of-start, left-of-end, right-of-end) and selects
     * the nearest one.
     *
     * If the cache is empty, both context pages are reset to `0`.
     *
     * @param startPoint The lower bound of the search range. Must be >= 1.
     * @param endPoint The upper bound of the search range. Must be >= [startPoint].
     * @throws IllegalArgumentException If [startPoint] or [endPoint] is 0, or if [endPoint] < [startPoint].
     */
    fun findNearContextPage(startPoint: Int = 1, endPoint: Int = startPoint) {
        require(startPoint >= 1) { "startPoint must be greater than zero" }
        require(endPoint >= 1) { "endPoint must be greater than zero" }
        require(endPoint >= startPoint) { "endPoint must be greater than startPoint" }

        fun find(sPoint: Int, ePoint: Int) {
            // example 1(0), 2(1), 3(2), 21(3), 22(4), 23(5)
            val validStates = this.states.filter(::isFilledSuccessState)
            if (validStates.isEmpty()) {
                startContextPage = 0
                endContextPage = 0
                return
            }

            var startPoint = 1
            if (sPoint > 0) startPoint = sPoint
            var endPoint = validStates[validStates.lastIndex].page
            if (ePoint < endPoint) endPoint = ePoint

            var ltlIndex = -1
            var ltlCost = Int.MAX_VALUE

            var ltrIndex = -1
            var ltrCost = Int.MAX_VALUE

            var rtlIndex = -1
            var rtlCost = Int.MAX_VALUE

            var rtrIndex = -1
            var rtrCost = Int.MAX_VALUE

            val sIndex: Int =
                validStates.binarySearch { it.page.compareTo(startPoint) }
            val eIndex: Int =
                if (startPoint == endPoint) sIndex
                else validStates.binarySearch { it.page.compareTo(endPoint) }

            if (sIndex >= 0) {
                val pivot = validStates[sIndex]
                expandStartContextPage(pivot)
                expandEndContextPage(pivot)
                return
            } else if (eIndex >= 0) {
                val pivot = validStates[eIndex]
                expandStartContextPage(pivot)
                expandEndContextPage(pivot)
                return
            }

            var startPivotIndex = -(sIndex + 1)
            startPivotIndex = startPivotIndex.coerceAtMost(validStates.lastIndex)
            val startPivotState = validStates[startPivotIndex]
            if (startPivotState.page < startPoint) {
                ltlIndex = startPivotIndex
                ltlCost = startPivotState gap startPoint
                val rightPivotState = validStates.getOrNull(startPivotIndex + 1)
                if (rightPivotState != null) {
                    ltrIndex = startPivotIndex + 1
                    ltrCost = startPoint gap rightPivotState
                }
            } else { // startPoint < leftPivot.page (not equal)
                val rightPivotState = startPivotState
                ltrIndex = startPivotIndex
                ltrCost = startPoint gap rightPivotState
                val leftPivotState = validStates.getOrNull(startPivotIndex - 1)
                if (leftPivotState != null) {
                    ltlIndex = startPivotIndex - 1
                    ltlCost = leftPivotState gap startPoint
                }
            }

            var endPivotIndex = -(eIndex + 1)
            endPivotIndex = endPivotIndex.coerceAtMost(validStates.lastIndex)
            val endPivotState = validStates[endPivotIndex]
            if (endPivotState.page < endPoint) {
                rtlIndex = endPivotIndex
                rtlCost = endPivotState gap endPoint
                val rightPivotState = validStates.getOrNull(endPivotIndex + 1)
                if (rightPivotState != null) {
                    rtrIndex = endPivotIndex + 1
                    rtrCost = endPoint gap rightPivotState
                }
            } else { // endPivotIndex < leftPivot.page (not equal)
                val rightPivotState = endPivotState
                rtrIndex = endPivotIndex
                rtrCost = endPoint gap rightPivotState
                val leftPivotState = validStates.getOrNull(endPivotIndex - 1)
                if (leftPivotState != null) {
                    rtlIndex = endPivotIndex - 1
                    rtlCost = leftPivotState gap endPoint
                }
            }

            var minCost: Int = ltlCost
            var minIndex: Int = ltlIndex

            if (ltrCost < minCost) {
                minCost = ltrCost
                minIndex = ltrIndex
            }
            if (rtlCost < minCost) {
                minCost = rtlCost
                minIndex = rtlIndex
            }
            if (rtrCost < minCost) {
                minCost = rtrCost
                minIndex = rtrIndex
            }

            val nearestPage: PageState<T> = validStates[minIndex]
            expandStartContextPage(nearestPage)
            expandEndContextPage(nearestPage)
        }

        if (size == 0) {
            startContextPage = 0
            endContextPage = 0
            return
        } else if (startPoint != endPoint) {
            // we should find the nearest valid page state with specific start and end points
            return find(startPoint, endPoint)
        }
        // else (size > 0 && startPoint == endPoint)
        val pointState = getStateOf(startPoint)
        if (isFilledSuccessState(pointState)) {
            // startPoint (== endPoint) is a valid page state,
            // so we can just expand the context
            startContextPage = startPoint
            endContextPage = startPoint
            expandStartContextPage(getStateOf(pointState.page - 1))
            expandEndContextPage(getStateOf(pointState.page + 1))
        } else {
            // startPoint (== endPoint) is not a valid page state,
            // so we need to find around it the nearest valid page state
            find(
                sPoint = startPoint - 1,
                ePoint = endPoint + 1
            )
        }
    }

    /**
     * Retrieves the cached [PageState] for the given [page] number.
     *
     * @param page The page number to look up.
     * @return The cached [PageState], or `null` if the page is not in the cache.
     */
    fun getStateOf(page: Int): PageState<T>? {
        return cache.getStateOf(page)
    }

    /**
     * Attempts to load a page from the [persistentCache] (L2) and promote it
     * into the in-memory [cache] (L1).
     *
     * If [persistentCache] is `null` or does not contain the requested page,
     * returns `null` without modifying L1.
     *
     * @param page The page number to load from persistent storage.
     * @return The restored [PageState], or `null` if not found.
     */
    suspend fun loadFromPersistentCache(page: Int): PageState<T>? {
        val pc = persistentCache ?: return null
        val persisted = pc.load(page) ?: return null
        cache.setState(persisted, silently = true)
        return persisted
    }

    /**
     * Stores a [PageState] in the cache via the strategy chain, replacing any existing
     * state for that page number.
     *
     * The page number is determined by [state]`.page`.
     *
     * @param state The page state to store.
     * @param silently If `true`, the change will **not** trigger a [snapshot] emission.
     *   Set to `true` during batch operations to avoid redundant emissions.
     */
    fun setState(
        state: PageState<T>,
        silently: Boolean = false
    ) {
        cache.setState(state, silently = true)
        if (!silently) {
            snapshot()
        }
    }

    /**
     * Removes a page from the cache by its page number.
     *
     * @param page The page number to remove.
     * @return The removed [PageState], or `null` if the page was not in the cache.
     */
    fun removeFromCache(page: Int): PageState<T>? {
        return cache.removeFromCache(page)
    }

    /**
     * Clears all pages from the cache.
     */
    fun clear() {
        cache.clear()
    }

    /**
     * Retrieves a single element by its position within a cached page.
     *
     * @param page The page number containing the element.
     * @param index The zero-based index of the element within the page's data list.
     * @return The element at the specified position, or `null` if the page is not cached.
     * @throws IndexOutOfBoundsException If [index] is out of range for the page's data.
     */
    fun getElement(
        page: Int,
        index: Int,
    ): T? {
        return cache.getElement(page, index)
    }

    /**
     * Determines whether the given [PageState] represents a successfully loaded page
     * whose data set is considered "filled".
     *
     * A state is treated as "filled" when:
     * - it is a [SuccessPage];
     * - its data size is equal to the configured `capacity`, unless `capacity` is unlimited;
     * - for any non-success state (including `EmptyPage`), the result is `false`.
     *
     * @param state The [PageState] instance to evaluate.
     * @return `true` if `state` is a [SuccessPage] containing the maximum number of items
     *         (or if the capacity is unlimited), `false` otherwise.
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

    /**
     * Traverses pages starting from [pivotState], repeatedly applying [next]
     * to compute the next page number, as long as:
     *  - the computed page exists in the cache, and
     *  - the corresponding PageState satisfies [predicate].
     *
     * Traversal stops when:
     *  - the next page does not exist, or
     *  - its state does not satisfy [predicate].
     *
     * @param pivotState The first page to start traversal from.
     * If null or does not satisfy [predicate], the function returns null.
     *
     * @param next A function that receives the current page number and returns
     * the next page number to traverse to. The caller is responsible for ensuring
     * that the returned value is a valid page number (e.g., >= 1).
     *
     * @param predicate A condition that each visited PageState must satisfy.
     *
     * @return The last PageState encountered that satisfies [predicate],
     * or null if [pivotState] is null or does not satisfy [predicate].
     */
    inline fun walkWhile(
        pivotState: PageState<T>?,
        next: (current: Int) -> Int,
        predicate: (PageState<T>) -> Boolean = { true }
    ): PageState<T>? {
        if (pivotState == null) return null
        if (!predicate.invoke(pivotState)) return null

        var resultState: PageState<T> = pivotState
        while (true) {
            val nextPage: Int = next.invoke(resultState.page)
            val nextState: PageState<T>? = cache.getStateOf(nextPage)

            if (nextState != null && predicate.invoke(nextState)) {
                resultState = nextState
            } else {
                return resultState
            }
        }
    }

    /** Whether the full cache flow ([asFlow]) has been activated by a subscriber. */
    var enableCacheFlow = false
        private set
    private val _cacheFlow = MutableStateFlow<List<PageState<T>>>(emptyList())

    /**
     * Returns a [Flow] that emits the **entire** cache list whenever it changes.
     *
     * This includes all pages — even those outside the current context window.
     * Calling this method automatically enables cache flow updates ([enableCacheFlow] = `true`).
     *
     * For most UI use cases, prefer [snapshot] which emits only the visible pages.
     */
    fun asFlow(): Flow<List<PageState<T>>> {
        enableCacheFlow = true
        return _cacheFlow.asStateFlow()
    }

    /**
     * Forces a re-emission of the current cache into [asFlow] subscribers.
     *
     * Creates a snapshot copy of the current cache list and emits it to ensure
     * that collectors receive the latest state.
     */
    fun repeatCacheFlow() {
        _cacheFlow.value = states
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Snapshot
    // ──────────────────────────────────────────────────────────────────────────

    protected val _snapshot = MutableStateFlow<List<PageState<T>>>(emptyList())

    /**
     * A [Flow] that emits the list of [PageState] objects within the current context window
     * whenever a navigation action completes.
     *
     * This is the primary reactive API for observing the paginator's visible state.
     * Collect this flow in your UI layer (e.g., in a ViewModel) to update the screen.
     *
     * The emitted list includes pages from [startContextPage] to [endContextPage],
     * plus any adjacent non-success pages (e.g., [ProgressPage] or [ErrorPage]).
     */
    val snapshot: Flow<List<PageState<T>>> = _snapshot.asStateFlow()

    /**
     * Returns the page range currently visible in the last emitted [snapshot],
     * or `null` if the snapshot is empty.
     *
     * Used by navigation to skip bookmarks whose pages are already visible on the UI.
     */
    fun snapshotPageRange(): IntRange? {
        val pages: List<PageState<T>> = _snapshot.value
        if (pages.isEmpty()) return null
        return pages.first().page..pages.last().page
    }

    /**
     * Emits the current visible state to [snapshot] subscribers.
     *
     * Collects all cached pages within the given [pageRange] (or the expanded context window
     * if no range is specified) and emits them as a list via the [snapshot] flow.
     *
     * If the paginator is not started and no explicit [pageRange] is given, the emission is skipped.
     *
     * @param pageRange An explicit range of pages to include. If `null`, the range is computed
     *   from [startContextPage] to [endContextPage], expanded outward through any adjacent
     *   non-success pages (e.g., [ProgressPage] or [ErrorPage]).
     */
    fun snapshot(pageRange: IntRange? = null) {
        (pageRange ?: run {
            if (!isStarted) return@run null
            val pivotBackwardState = getStateOf(startContextPage) ?: return@run null
            val pivotForwardState = getStateOf(endContextPage) ?: return@run null
            val expandedBackwardPage: Int? =
                walkWhile(
                    pivotState = pivotBackwardState,
                    next = { it - 1 },
                    predicate = { state: PageState<T> ->
                        isFilledSuccessState(state)
                    }
                )?.page?.minus(1)?.coerceAtLeast(1)
            val expandedForwardPage: Int? =
                walkWhile(
                    pivotState = pivotForwardState,
                    next = { it + 1 },
                    predicate = { state: PageState<T> ->
                        isFilledSuccessState(state)
                    }
                )?.page?.plus(1)

            val min: Int = expandedBackwardPage ?: pivotBackwardState.page
            val max: Int = expandedForwardPage ?: pivotForwardState.page
            return@run min..max
        })?.let { mPageRange: IntRange ->
            _snapshot.value = scan(mPageRange)
        }
    }

    /**
     * Returns a list of [PageState] objects for all cached pages within [pagesRange].
     *
     * Iterates through the range and collects cached pages, skipping any page numbers
     * that are not present in the cache.
     *
     * @param pagesRange The range of page numbers to scan. Defaults to the expanded
     *   context window ([startContextPage]..[endContextPage] plus adjacent pages).
     * @return A list of contiguous [PageState] objects within the range.
     * @throws IllegalStateException If [startContextPage] or [endContextPage] is `0`
     *   (when using the default range).
     */
    fun scan(
        pagesRange: IntRange = run {
            check(startContextPage != 0) { "You cannot scan because startContextPage is 0" }
            check(endContextPage != 0) { "You cannot scan because endContextPage is 0" }
            val min = walkWhile(getStateOf(startContextPage), next = { it - 1 })?.page
            val max = walkWhile(getStateOf(endContextPage), next = { it + 1 })?.page
            checkNotNull(min) { "min is null the data structure is broken!" }
            checkNotNull(max) { "max is null the data structure is broken!" }
            return@run min..max
        }
    ): List<PageState<T>> {
        val rangeSize: Int = (pagesRange.last - pagesRange.first + 1)
        val result: List<PageState<T>> = buildList(rangeSize) {
            for (page in pagesRange) {
                val pageState: PageState<T> =
                    getStateOf(page) ?: continue
                this.add(pageState)
            }
        }
        return result
    }

    /** Internal set of page numbers marked as dirty (needing refresh). */
    private val _dirtyPages: MutableSet<Int> = mutableSetOf()
    val dirtyPages: Set<Int> get() = _dirtyPages.toSet()

    /**
     * Marks a single page as dirty, scheduling it for refresh on the next navigation.
     *
     * @param page The page number to mark as dirty.
     */
    fun markDirty(page: Int) {
        _dirtyPages.add(page)
    }

    /**
     * Marks multiple pages as dirty, scheduling them for refresh on the next navigation.
     *
     * @param pages The page numbers to mark as dirty.
     */
    fun markDirty(pages: List<Int>) {
        _dirtyPages.addAll(pages)
    }

    /**
     * Removes the dirty flag from a single page.
     *
     * @param page The page number to clear.
     */
    fun clearDirty(page: Int) {
        _dirtyPages.remove(page)
    }

    /**
     * Removes the dirty flag from multiple pages.
     *
     * @param pages The page numbers to clear.
     */
    fun clearDirty(pages: List<Int>) {
        _dirtyPages.removeAll(pages.toSet())
    }

    /**
     * Removes all dirty flags from all pages.
     */
    fun clearAllDirty() {
        _dirtyPages.clear()
    }

    /**
     * Checks whether a specific page is marked as dirty.
     *
     * @param page The page number to check.
     * @return `true` if the page is dirty, `false` otherwise.
     */
    fun isDirty(page: Int): Boolean = page in _dirtyPages

    /**
     * Returns `true` if there are no dirty pages.
     *
     * Avoids the allocation that [dirtyPages] (which creates a defensive copy) would incur
     * when the caller only needs to check emptiness.
     */
    fun isDirtyPagesEmpty(): Boolean = _dirtyPages.isEmpty()

    /**
     * Returns dirty pages that fall within the given [range], removing them from the dirty set.
     *
     * This is an atomic "drain" operation: pages are both returned and cleared in one step,
     * avoiding double-access of [dirtyPages] and the extra Set copy it creates.
     *
     * @param range The page range to check against.
     * @return A list of dirty page numbers that were within [range], or `null` if none.
     */
    fun drainDirtyPagesInRange(range: IntRange): List<Int>? {
        if (_dirtyPages.isEmpty()) return null
        val result = _dirtyPages.filter { it in range }
        if (result.isEmpty()) return null
        _dirtyPages.removeAll(result.toSet())
        return result
    }

    /**
     * Factory for creating [ProgressPage] instances during page loading.
     * Override to provide custom progress page subclasses with additional metadata.
     */
    var initializerProgressPage: InitializerProgressPage<T> =
        fun(page: Int, data: List<T>): ProgressPage<T> {
            return ProgressPage(page = page, data = data)
        }

    /**
     * Factory for creating [SuccessPage] instances on successful load.
     * Automatically delegates to [initializerEmptyPage] when the data list is empty.
     * Override to provide custom success page subclasses.
     */
    var initializerSuccessPage: InitializerSuccessPage<T> =
        fun(page: Int, data: List<T>, _Metadata: Metadata?): SuccessPage<T> {
            return if (data.isEmpty()) initializerEmptyPage.invoke(page, data, _Metadata)
            else SuccessPage(page = page, data = data)
        }

    /**
     * Factory for creating [EmptyPage] instances when the source returns no data for a page.
     * Override to provide custom empty page subclasses.
     */
    var initializerEmptyPage: InitializerEmptyPage<T> =
        fun(page: Int, data: List<T>, _Metadata: Metadata?): EmptyPage<T> {
            return EmptyPage(page = page, data = data)
        }

    /**
     * Factory for creating [ErrorPage] instances when the source throws an exception.
     * The previously cached data (if any) is preserved in the error state.
     * Override to provide custom error page subclasses.
     */
    var initializerErrorPage: InitializerErrorPage<T> =
        fun(exception: Exception, page: Int, data: List<T>): ErrorPage<T> {
            return ErrorPage(exception = exception, page = page, data = data)
        }

    /**
     * Changes the expected number of items per page and optionally redistributes existing data.
     *
     * When [resize] is `true`, all success pages within the context window are collected,
     * their items are flattened into a single list, and then re-distributed into new pages
     * of size [capacity]. The cache is rebuilt from scratch with the redistributed data.
     *
     * If the new [capacity] equals the current one, this is a no-op.
     *
     * @param capacity The new capacity for each page. Must be >= 0.
     *   Use [UNLIMITED_CAPACITY] (0) to disable capacity checks.
     * @param resize If `true`, existing cached data is redistributed into pages of the new capacity.
     *   If `false`, only the capacity value is updated without touching cached data.
     * @param silently If `true`, no [snapshot] is emitted after the resize.
     * @param initSuccessState Factory for creating new [PageState.SuccessPage] instances during redistribution.
     * @throws IllegalArgumentException If [capacity] is negative.
     */
    fun resize(
        capacity: Int,
        resize: Boolean = true,
        silently: Boolean = false,
        initSuccessState: InitializerSuccessPage<T> = initializerSuccessPage
    ) {
        if (this.capacity == capacity) return
        require(capacity >= 0) { "capacity must be greater or equal than zero" }
        this.capacity = capacity

        if (resize && capacity > 0) {
            val firstSuccessPageState: PageState<T> =
                walkWhile(
                    pivotState = getStateOf(startContextPage),
                    next = { it - 1 },
                    predicate = { pageState: PageState<T> ->
                        pageState.isSuccessState()
                    }
                ) ?: return
            val lastSuccessPageState: PageState<T> =
                walkWhile(
                    pivotState = getStateOf(endContextPage),
                    next = { it + 1 },
                    predicate = { pageState: PageState<T> ->
                        pageState.isSuccessState()
                    }
                ) ?: return
            val items: MutableList<T> =
                (firstSuccessPageState.page..lastSuccessPageState.page)
                    .mapNotNull { page: Int -> getStateOf(page) }
                    .flatMap { pageState: PageState<T> -> pageState.data }
                    .toMutableList()

            cache.clear()

            var pageIndex: Int = firstSuccessPageState.page
            var offset = 0
            while (offset < items.size) {
                val end = minOf(offset + capacity, items.size)
                val successData = items.subList(offset, end).toMutableList()
                offset = end

                setState(
                    state = initSuccessState.invoke(pageIndex++, successData, null),
                    silently = true
                )
            }
        }

        if (!silently && capacity > 0) {
            snapshot()
        }
    }

    /**
     * Releases all cache resources and resets to initial state.
     *
     * Clears the cache (including eviction strategy state), resets context window,
     * capacity, dirty pages, and snapshot.
     *
     * @param capacity The capacity to set after release. Defaults to [DEFAULT_CAPACITY].
     * @param silently If `true`, the empty snapshot is **not** emitted.
     */
    fun release(
        capacity: Int = DEFAULT_CAPACITY,
        silently: Boolean = false
    ) {
        cache.release(capacity, silently)
        if (!silently) _snapshot.value = emptyList()
        this.capacity = capacity
        _dirtyPages.clear()
    }

    /**
     * Creates a serializable snapshot of this PagingCore's current state.
     *
     * All cached pages are captured (or only pages within the context window if
     * [contextOnly] is `true`). [ErrorPage] and [ProgressPage] entries
     * are converted to [SuccessPage]/[EmptyPage] (preserving their cached data
     * and error messages), and their page numbers are marked as dirty in the snapshot
     * so they will be re-fetched on next navigation after restore.
     *
     * @param contextOnly If `true`, only pages within `startContextPage..endContextPage`
     *   are included in the snapshot. Useful for reducing snapshot size.
     * @return A [PagingCoreSnapshot] containing all the state needed for restoration.
     */
    fun saveState(contextOnly: Boolean = false): PagingCoreSnapshot<T> {
        val allStates = this.states
        val filteredStates = if (contextOnly && startContextPage > 0 && endContextPage > 0) {
            allStates.filter { it.page in startContextPage..endContextPage }
        } else {
            allStates
        }
        val entries: List<PageEntry<T>> =
            filteredStates.map { state: PageState<T> ->
                val wasDirty = isDirty(state.page)
                        || state.isErrorState()
                        || state.isProgressState()

                val type: PageEntryType =
                    if (state.data.isEmpty()) PageEntryType.EMPTY
                    else PageEntryType.SUCCESS

                val errorMessage: String? =
                    if (state.isErrorState()) state.exception.message
                    else null

                PageEntry(
                    page = state.page,
                    type = type,
                    data = state.data,
                    wasDirty = wasDirty,
                    errorMessage = errorMessage,
                )
            }
        return PagingCoreSnapshot(
            entries = entries,
            startContextPage = startContextPage,
            endContextPage = endContextPage,
            capacity = capacity,
        )
    }

    /**
     * Restores this PagingCore's state from a previously saved [snapshot].
     *
     * Validates the snapshot before applying it. Clears the current cache and dirty pages,
     * then rebuilds the cache from the snapshot's entries. Pages marked as dirty in the snapshot
     * (including those that were ErrorPage/ProgressPage before saving) are
     * added to the dirty set for automatic re-fetch on next navigation.
     *
     * Uses [initializerSuccessPage] and [initializerEmptyPage] factories,
     * so custom PageState subclasses are honored on restore.
     *
     * @param snapshot The snapshot to restore from.
     * @param silently If `true`, no snapshot is emitted after restoration.
     * @throws IllegalArgumentException If the snapshot contains invalid data.
     */
    fun restoreState(
        snapshot: PagingCoreSnapshot<T>,
        silently: Boolean = false,
    ) {
        validateSnapshot(snapshot)

        cache.clear()
        _dirtyPages.clear()

        snapshot.entries.forEach { entry: PageEntry<T> ->
            val pageState: PageState<T> = when (entry.type) {
                PageEntryType.EMPTY -> {
                    initializerEmptyPage(
                        entry.page,
                        entry.data,
                        null
                    )
                }

                PageEntryType.SUCCESS -> {
                    val data = entry.data.toMutableList()
                    initializerSuccessPage(
                        entry.page,
                        data,
                        null
                    )
                }
            }
            setState(state = pageState, silently = true)
            if (entry.wasDirty) {
                _dirtyPages.add(entry.page)
            }
        }

        capacity = snapshot.capacity
        startContextPage = snapshot.startContextPage
        endContextPage = snapshot.endContextPage

        if (!silently) {
            snapshot()
        }
    }

    private fun validateSnapshot(snapshot: PagingCoreSnapshot<T>) {
        require(snapshot.capacity > 0) {
            "Snapshot capacity must be > 0, but was ${snapshot.capacity}"
        }
        require(snapshot.startContextPage >= 0) {
            "startContextPage must be >= 0, but was ${snapshot.startContextPage}"
        }
        require(snapshot.endContextPage >= 0) {
            "endContextPage must be >= 0, but was ${snapshot.endContextPage}"
        }
        val bothZero = snapshot.startContextPage == 0 && snapshot.endContextPage == 0
        require(bothZero || snapshot.startContextPage <= snapshot.endContextPage) {
            "startContextPage (${snapshot.startContextPage}) must be <= endContextPage (${snapshot.endContextPage})"
        }
        val pages = mutableSetOf<Int>()
        for (entry in snapshot.entries) {
            require(entry.page >= 1) {
                "Page number must be >= 1, but was ${entry.page}"
            }
            require(pages.add(entry.page)) {
                "Duplicate page number: ${entry.page}"
            }
        }
    }

    operator fun iterator(): Iterator<PageState<T>> {
        return states.iterator()
    }

    operator fun contains(page: Int): Boolean = getStateOf(page) != null

    operator fun contains(pageState: PageState<T>): Boolean = getStateOf(pageState.page) != null

    operator fun get(page: Int): PageState<T>? = getStateOf(page)

    operator fun get(page: Int, index: Int): T? = getElement(page, index)

    override fun toString(): String = "PagingCore(pages=${cache.pages})"

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = super.hashCode()

    companion object {
        const val DEFAULT_CAPACITY = 20
        const val UNLIMITED_CAPACITY = 0
    }
}
