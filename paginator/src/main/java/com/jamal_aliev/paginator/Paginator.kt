package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.Paginator.Companion.DEFAULT_CAPACITY
import com.jamal_aliev.paginator.Paginator.Companion.UNLIMITED_CAPACITY
import com.jamal_aliev.paginator.bookmark.Bookmark
import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkUInt
import com.jamal_aliev.paginator.exception.FinalPageExceededException
import com.jamal_aliev.paginator.exception.LoadGuardedException
import com.jamal_aliev.paginator.exception.LockedException.GoNextPageWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.GoPreviousPageWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.JumpWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.RefreshWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.RestartWasLockedException
import com.jamal_aliev.paginator.extension.gap
import com.jamal_aliev.paginator.extension.isProgressState
import com.jamal_aliev.paginator.extension.isSuccessState
import com.jamal_aliev.paginator.extension.walkBackwardWhile
import com.jamal_aliev.paginator.extension.walkForwardWhile
import com.jamal_aliev.paginator.initializer.InitializerEmptyPage
import com.jamal_aliev.paginator.initializer.InitializerErrorPage
import com.jamal_aliev.paginator.initializer.InitializerProgressPage
import com.jamal_aliev.paginator.initializer.InitializerSuccessPage
import com.jamal_aliev.paginator.logger.NoOpLogger
import com.jamal_aliev.paginator.logger.PaginatorLogger
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PageState.EmptyPage
import com.jamal_aliev.paginator.page.PageState.ErrorPage
import com.jamal_aliev.paginator.page.PageState.ProgressPage
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.math.max

/**
 * A read-only, reactive pagination manager for Kotlin/Android.
 *
 * `Paginator` maintains a sorted cache of [PageState] objects keyed by page number,
 * handles bidirectional navigation, bookmark-based jumping,
 * and emits state updates via Kotlin [Flow]s.
 *
 * For element-level CRUD, capacity management, and state manipulation,
 * use [MutablePaginator].
 *
 * **Key concepts:**
 * - **Context window** ([startContextPage]..[endContextPage]): the contiguous range of
 *   filled success pages visible to the UI via the [snapshot] flow.
 * - **Capacity**: the expected number of items per page. Pages with fewer items are
 *   considered "incomplete" and will be re-requested on the next navigation.
 * - **Bookmarks**: predefined page targets for quick navigation via [jumpForward]/[jumpBack].
 * - **Load guard**: an optional callback on navigation functions that can veto a page load
 *   before the network request is made, throwing [LoadGuardedException] when rejected.
 *
 * @param T The type of elements contained in each page.
 * @param source A suspending lambda that loads data for a given page number.
 *   The receiver is the paginator itself, giving access to its properties during loading.
 *
 * @see PageState
 * @see Bookmark
 * @see MutablePaginator
 */
open class Paginator<T>(
    var source: suspend Paginator<T>.(page: UInt) -> List<T>
) : Comparable<Paginator<*>> {

    /**
     * Logger for observing paginator operations.
     *
     * Set a custom [PaginatorLogger] implementation to receive logs about navigation,
     * state changes, and element-level operations. By default, [NoOpLogger] is used,
     * which discards all messages (zero overhead).
     *
     * @see PaginatorLogger
     * @see NoOpLogger
     */
    var logger: PaginatorLogger = NoOpLogger

    /**
     * The expected number of items per page.
     *
     * When a page contains fewer items than [capacity], it is considered
     * "incomplete" and will be re-requested on the next [goNextPage] call.
     * A value of [UNLIMITED_CAPACITY] (0) disables capacity checks entirely.
     *
     * Default: [DEFAULT_CAPACITY] (20).
     */
    var capacity: Int = DEFAULT_CAPACITY
        protected set

    /**
     * `true` if [capacity] equals [UNLIMITED_CAPACITY], meaning capacity checks are disabled.
     */
    val isCapacityUnlimited: Boolean
        get() = capacity == UNLIMITED_CAPACITY

    /** Internal sorted cache of page states, keyed by page number. */
    protected val cache = sortedMapOf<UInt, PageState<T>>()

    /** All cached page numbers, sorted in ascending order. */
    val pages: List<UInt> get() = cache.keys.toList()

    /** All cached page states, sorted by page number. */
    val pageStates: List<PageState<T>> get() = cache.values.toList()

    /** The number of pages currently in the cache. */
    val size: Int get() = cache.size

    /** Whether the full cache flow ([asFlow]) has been activated by a subscriber. */
    var enableCacheFlow = false
        private set
    private val _cacheFlow = MutableStateFlow(false to cache)

    /**
     * Returns a [Flow] that emits the **entire** cache map whenever it changes.
     *
     * This includes all pages — even those outside the current context window.
     * Calling this method automatically enables cache flow updates ([enableCacheFlow] = `true`).
     *
     * For most UI use cases, prefer [snapshot] which emits only the visible pages.
     */
    fun asFlow(): Flow<Map<UInt, PageState<T>>> {
        enableCacheFlow = true
        return _cacheFlow.asStateFlow()
            .filter { it.first }
            .map { it.second }
    }

    /**
     * Forces a re-emission of the current cache into [asFlow] subscribers.
     *
     * Emits a "reset" (false) followed by the current cache (true) to ensure
     * that collectors receive the latest state, even if the map reference hasn't changed.
     */
    fun repeatCacheFlow() {
        _cacheFlow.update { false to sortedMapOf() }
        _cacheFlow.update { true to cache }
    }

    /**
     * The left (lowest) boundary of the current context window.
     *
     * Together with [endContextPage], defines the contiguous range of filled success pages
     * that are visible to the UI via the [snapshot] flow. A value of `0u` means the
     * paginator has not been started yet.
     *
     * Updated automatically during navigation operations.
     */
    var startContextPage = 0u
        protected set

    /**
     * Walks backward from [pageState] through contiguous filled success pages
     * and updates [startContextPage] to the earliest page found.
     *
     * @param pageState The starting page to expand backward from.
     * @return The earliest filled success page found, or `null` if [pageState] is not valid.
     */
    protected fun expandStartContextPage(pageState: PageState<T>?): PageState<T>? {
        return walkBackwardWhile(pageState, ::isFilledSuccessState)
            ?.also { startContextPage = it.page }
    }

    /**
     * The right (highest) boundary of the current context window.
     *
     * Together with [startContextPage], defines the contiguous range of filled success pages
     * that are visible to the UI via the [snapshot] flow. A value of `0u` means the
     * paginator has not been started yet.
     *
     * Updated automatically during navigation operations.
     */
    var endContextPage = 0u
        protected set

    /**
     * Walks forward from [pageState] through contiguous filled success pages
     * and updates [endContextPage] to the latest page found.
     *
     * @param pageState The starting page to expand forward from.
     * @return The latest filled success page found, or `null` if [pageState] is not valid.
     */
    protected fun expandEndContextPage(pageState: PageState<T>?): PageState<T>? {
        return walkForwardWhile(pageState, ::isFilledSuccessState)
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
     * If the cache is empty, both context pages are reset to `0u`.
     *
     * @param startPoint The lower bound of the search range. Must be >= 1.
     * @param endPoint The upper bound of the search range. Must be >= [startPoint].
     * @throws IllegalArgumentException If [startPoint] or [endPoint] is 0, or if [endPoint] < [startPoint].
     */
    fun findNearContextPage(startPoint: UInt = 1u, endPoint: UInt = startPoint) {
        require(startPoint >= 1u) { "startPoint must be greater than zero" }
        require(endPoint >= 1u) { "endPoint must be greater than zero" }
        require(endPoint >= startPoint) { "endPoint must be greater than startPoint" }

        fun find(sPont: UInt, ePoint: UInt) {
            // example 1(0), 2(1), 3(2), 21(3), 22(4), 23(5)
            val validStates = this.pageStates.filter(::isFilledSuccessState)
            if (validStates.isEmpty()) {
                startContextPage = 0u
                endContextPage = 0u
                return
            }

            var startPoint = 1u
            if (sPont > 0u) startPoint = sPont
            var endPoint = validStates[validStates.lastIndex].page
            if (ePoint < endPoint) endPoint = ePoint

            var ltlIndex = -1
            var ltlCost = UInt.MAX_VALUE

            var ltrIndex = -1
            var ltrCost = UInt.MAX_VALUE

            var rtlIndex = -1
            var rtlCost = UInt.MAX_VALUE

            var rtrIndex = -1
            var rtrCost = UInt.MAX_VALUE

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
            if (startPivotIndex > validStates.lastIndex) startPivotIndex = validStates.lastIndex
            val startPivotState = validStates[startPivotIndex]
            if (startPivotState.page < startPoint) {
                ltlIndex = startPivotIndex
                ltlCost = startPivotState gap startPoint
                val rightPivotState = validStates.getOrNull(startPivotIndex + 1)
                if (rightPivotState != null) {
                    ltrIndex = startPivotIndex + 1
                    ltrCost = startPoint gap rightPivotState
                }
            } else { // sPont < leftPivot.page (not equal)
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
            if (endPivotIndex > validStates.lastIndex) endPivotIndex = validStates.lastIndex
            val endPivotState = validStates[endPivotIndex]
            if (endPivotState.page < endPoint) {
                rtlIndex = endPivotIndex
                rtlCost = endPivotState gap endPoint
                val rightPivotState = validStates.getOrNull(endPivotIndex + 1)
                if (rightPivotState != null) {
                    rtrIndex = endPivotIndex + 1
                    rtrCost = endPoint gap rightPivotState
                }
            } else { // ePoint < leftPivot.page (not equal)
                val rightPivotState = endPivotState
                rtrIndex = endPivotIndex
                rtrCost = endPoint gap rightPivotState
                val leftPivotState = validStates.getOrNull(endPivotIndex - 1)
                if (leftPivotState != null) {
                    rtlIndex = endPivotIndex - 1
                    rtlCost = leftPivotState gap endPoint
                }
            }

            var minCost: UInt = ltlCost
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
            startContextPage = 0u
            endContextPage = 0u
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
            expandStartContextPage(getStateOf(pointState.page - 1u))
            expandEndContextPage(getStateOf(pointState.page + 1u))
        } else {
            // startPoint (== endPoint) is not a valid page state,
            // so we need to find around it the nearest valid page state
            find(
                sPont = startPoint - 1u,
                ePoint = endPoint + 1u
            )
        }
    }

    /**
     * `true` if the paginator has been started, i.e., at least one [jump] has been performed
     * and both [startContextPage] and [endContextPage] are non-zero.
     */
    val isStarted: Boolean get() = startContextPage > 0u && endContextPage > 0u

    /**
     * The Maximum page number allowed for pagination.
     *
     * Used as an upper border when pagination. If you try to paginate
     * (e.g. via [goNextPage] or [jump]) the requested page number
     * exceeds [finalPage], [FinalPageExceededException] will be thrown.
     *
     * The default value is [UInt.MAX_VALUE], which means there is no limit.
     */
    var finalPage: UInt = UInt.MAX_VALUE

    @Suppress("NOTHING_TO_INLINE")
    private inline fun exceedsFinal(
        page: UInt,
        finalPage: UInt = this.finalPage
    ): Boolean {
        return page > finalPage
    }

    /**
     * Predefined page targets for quick navigation via [jumpForward] and [jumpBack].
     *
     * By default, contains a single bookmark pointing to page 1. You can add, remove, or
     * replace bookmarks at any time. The internal iterator tracks the current position
     * within this list.
     */
    val bookmarks: MutableList<Bookmark> = mutableListOf(BookmarkUInt(page = 1u))

    /**
     * If `true`, bookmark navigation ([jumpForward]/[jumpBack]) wraps around when
     * reaching the end/beginning of the [bookmarks] list. Default: `false`.
     */
    var recyclingBookmark = false
    protected var bookmarkIterator = bookmarks.listIterator()

    protected val _snapshot = MutableStateFlow(false to emptyList<PageState<T>>())

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
    val snapshot: Flow<List<PageState<T>>>
        get() {
            return _snapshot.asStateFlow()
                .filter { it.first }
                .map { it.second }
        }

    /**
     * Factory for creating [ProgressPage] instances during page loading.
     * Override to provide custom progress page subclasses with additional metadata.
     */
    var initializerProgressPage: InitializerProgressPage<T> =
        fun(page: UInt, data: List<T>): ProgressPage<T> {
            return ProgressPage(page = page, data = data)
        }

    /**
     * Factory for creating [SuccessPage] instances on successful load.
     * Automatically delegates to [initializerEmptyPage] when the data list is empty.
     * Override to provide custom success page subclasses.
     */
    var initializerSuccessPage: InitializerSuccessPage<T> =
        fun(page: UInt, data: List<T>): SuccessPage<T> {
            return if (data.isEmpty()) initializerEmptyPage.invoke(page, data)
            else SuccessPage(page = page, data = data)
        }

    /**
     * Factory for creating [EmptyPage] instances when the source returns no data for a page.
     * Override to provide custom empty page subclasses.
     */
    var initializerEmptyPage: InitializerEmptyPage<T> =
        fun(page: UInt, data: List<T>): EmptyPage<T> {
            return EmptyPage(page = page, data = data)
        }

    /**
     * Factory for creating [ErrorPage] instances when the source throws an exception.
     * The previously cached data (if any) is preserved in the error state.
     * Override to provide custom error page subclasses.
     */
    var initializerErrorPage: InitializerErrorPage<T> =
        fun(exception: Exception, page: UInt, data: List<T>): ErrorPage<T> {
            return ErrorPage(exception = exception, page = page, data = data)
        }

    /**
     * Moves forward to the next bookmark in the [bookmarks] list and jumps to it.
     *
     * If the bookmark iterator has reached the end and [recycling] is `true`,
     * the iterator resets to the beginning and continues from the first bookmark.
     *
     * This function delegates to [jump] for the actual page loading.
     *
     * @param recycling If `true`, wraps around to the first bookmark when the end is reached.
     *   Defaults to [recyclingBookmark].
     * @param silentlyLoading If `true`, the snapshot will **not** be emitted during loading.
     * @param silentlyResult If `true`, the snapshot will **not** be emitted after loading.
     * @param finalPage Upper page boundary. Defaults to [Paginator.finalPage].
     * @param loadGuard A guard callback invoked with `(page, currentState)` **before** the page
     *   is loaded from the source. Return `true` to proceed, or `false` to abort.
     *   When `false` is returned, [LoadGuardedException] is thrown.
     *   Defaults to always allowing the load.
     * @param lockJump If `true`, throws [JumpWasLockedException]. Defaults to [Paginator.lockJump].
     * @param enableCacheFlow If `true`, the full cache flow is also updated.
     * @param initProgressState Factory for creating [ProgressPage] instances during loading.
     * @param initSuccessState Factory for creating [SuccessPage] instances on successful load.
     * @param initEmptyState Factory for creating [EmptyPage] instances when the source returns no data.
     * @param initErrorState Factory for creating [ErrorPage] instances when the source throws.
     * @return A [Pair] of the [Bookmark] and resulting [PageState], or `null` if no bookmark is available.
     * @throws JumpWasLockedException If [lockJump] is `true`.
     * @throws FinalPageExceededException If the bookmark page exceeds [finalPage].
     * @throws LoadGuardedException If [loadGuard] returns `false`.
     */
    suspend fun jumpForward(
        recycling: Boolean = recyclingBookmark,
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        finalPage: UInt = this.finalPage,
        loadGuard: (page: UInt, state: PageState<T>?) -> Boolean = { _, _ -> true },
        lockJump: Boolean = this.lockJump,
        enableCacheFlow: Boolean = this.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = initializerProgressPage,
        initSuccessState: InitializerSuccessPage<T> = initializerSuccessPage,
        initEmptyState: InitializerEmptyPage<T> = initializerEmptyPage,
        initErrorState: InitializerErrorPage<T> = initializerErrorPage
    ): Pair<Bookmark, PageState<T>>? {
        if (lockJump) throw JumpWasLockedException()
        logger.log(TAG, "jumpForward: recycling=$recycling")

        var bookmark: Bookmark? = bookmarkIterator
            .takeIf { it.hasNext() }
            ?.next()

        if (bookmark != null || recycling) {
            if (bookmark == null) {
                bookmarkIterator = bookmarks.listIterator()
                bookmark = bookmarkIterator
                    .takeIf { it.hasNext() }
                    ?.next() ?: return null
            }
            return jump(
                bookmark = bookmark,
                silentlyLoading = silentlyLoading,
                silentlyResult = silentlyResult,
                finalPage = finalPage,
                loadGuard = loadGuard,
                enableCacheFlow = enableCacheFlow,
                initProgressState = initProgressState,
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState,
            )
        }

        logger.log(TAG, "jumpForward: no bookmark available")
        return null
    }

    /**
     * Moves backward to the previous bookmark in the [bookmarks] list and jumps to it.
     *
     * If the bookmark iterator has reached the beginning and [recycling] is `true`,
     * the iterator resets to the last bookmark and continues backward.
     *
     * This function delegates to [jump] for the actual page loading.
     *
     * @param recycling If `true`, wraps around to the last bookmark when the beginning is reached.
     *   Defaults to [recyclingBookmark].
     * @param silentlyLoading If `true`, the snapshot will **not** be emitted during loading.
     * @param silentlyResult If `true`, the snapshot will **not** be emitted after loading.
     * @param finalPage Upper page boundary. Defaults to [Paginator.finalPage].
     * @param loadGuard A guard callback invoked with `(page, currentState)` **before** the page
     *   is loaded from the source. Return `true` to proceed, or `false` to abort.
     *   When `false` is returned, [LoadGuardedException] is thrown.
     *   Defaults to always allowing the load.
     * @param lockJump If `true`, throws [JumpWasLockedException]. Defaults to [Paginator.lockJump].
     * @param enableCacheFlow If `true`, the full cache flow is also updated.
     * @param initProgressState Factory for creating [ProgressPage] instances during loading.
     * @param initEmptyState Factory for creating [EmptyPage] instances when the source returns no data.
     * @param initSuccessState Factory for creating [SuccessPage] instances on successful load.
     * @param initErrorState Factory for creating [ErrorPage] instances when the source throws.
     * @return A [Pair] of the [Bookmark] and resulting [PageState], or `null` if no bookmark is available.
     * @throws JumpWasLockedException If [lockJump] is `true`.
     * @throws FinalPageExceededException If the bookmark page exceeds [finalPage].
     * @throws LoadGuardedException If [loadGuard] returns `false`.
     */
    suspend fun jumpBack(
        recycling: Boolean = recyclingBookmark,
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        finalPage: UInt = this.finalPage,
        loadGuard: (page: UInt, state: PageState<T>?) -> Boolean = { _, _ -> true },
        lockJump: Boolean = this.lockJump,
        enableCacheFlow: Boolean = this.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = initializerErrorPage
    ): Pair<Bookmark, PageState<T>>? {
        if (lockJump) throw JumpWasLockedException()
        logger.log(TAG, "jumpBack: recycling=$recycling")

        var bookmark: Bookmark? = bookmarkIterator
            .takeIf { it.hasPrevious() }
            ?.previous()

        if (bookmark != null || recycling) {
            if (bookmark == null) {
                bookmarkIterator = bookmarks.listIterator(bookmarks.lastIndex)
                bookmark = bookmarkIterator
                    .takeIf { it.hasPrevious() }
                    ?.previous() ?: return null
            }
            return jump(
                bookmark = bookmark,
                silentlyLoading = silentlyLoading,
                silentlyResult = silentlyResult,
                finalPage = finalPage,
                loadGuard = loadGuard,
                enableCacheFlow = enableCacheFlow,
                initProgressState = initProgressState,
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState,
            )
        }

        logger.log(TAG, "jumpBack: no bookmark available")
        return null
    }

    /**
     * If `true`, all jump operations ([jump], [jumpForward], [jumpBack]) are blocked
     * and will throw [JumpWasLockedException]. Reset to `false` on [MutablePaginator.release].
     */
    var lockJump = false

    /**
     * Jumps directly to a specific page identified by [bookmark].
     *
     * If the target page is already cached as a filled success page, it is returned
     * immediately without reloading. Otherwise, the context window is reset to the
     * target page and the page is loaded from the [source].
     *
     * **Behavior:**
     * 1. Validates that [bookmark] page > 0 and does not exceed [finalPage].
     * 2. If the target page is already a filled [SuccessPage], expands context
     *    around it and returns immediately.
     * 3. Invokes [loadGuard] — if it returns `false`, throws [LoadGuardedException],
     *    aborting the load before the network request.
     * 4. Resets [startContextPage] and [endContextPage] to the target page.
     * 5. Sets the page to [ProgressPage], loads from [source], and updates the cache.
     *
     * @param bookmark The target page bookmark. Must have `page > 0`.
     * @param silentlyLoading If `true`, the snapshot will **not** be emitted when the
     *   page transitions to [ProgressPage].
     * @param silentlyResult If `true`, the snapshot will **not** be emitted after the
     *   page finishes loading.
     * @param finalPage Upper page boundary for this call. Defaults to [Paginator.finalPage].
     * @param loadGuard A guard callback invoked with `(page, currentState)` **before** the page
     *   is loaded from the source. Return `true` to proceed, or `false` to abort.
     *   When `false` is returned, [LoadGuardedException] is thrown.
     *   Not invoked if the page is already a filled success page (cache hit).
     *   Defaults to always allowing the load.
     * @param lockJump If `true`, the operation is blocked and [JumpWasLockedException]
     *   is thrown immediately. Defaults to [Paginator.lockJump].
     * @param enableCacheFlow If `true`, the full cache flow ([asFlow]) is also updated.
     * @param initProgressState Factory for creating [ProgressPage] instances during loading.
     * @param initEmptyState Factory for creating [EmptyPage] instances when the source returns no data.
     * @param initSuccessState Factory for creating [SuccessPage] instances on successful load.
     * @param initErrorState Factory for creating [ErrorPage] instances when the source throws.
     * @return A [Pair] of the [Bookmark] and the resulting [PageState].
     * @throws JumpWasLockedException If [lockJump] is `true`.
     * @throws FinalPageExceededException If [bookmark] page exceeds [finalPage].
     * @throws LoadGuardedException If [loadGuard] returns `false`.
     * @throws IllegalArgumentException If [bookmark] page is 0.
     */
    suspend fun jump(
        bookmark: Bookmark,
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        finalPage: UInt = this.finalPage,
        loadGuard: (page: UInt, state: PageState<T>?) -> Boolean = { _, _ -> true },
        lockJump: Boolean = this.lockJump,
        enableCacheFlow: Boolean = this.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = initializerErrorPage
    ): Pair<Bookmark, PageState<T>> {
        if (lockJump) throw JumpWasLockedException()

        require(bookmark.page > 0u) { "bookmark.page should be greater than 0" }
        logger.log(TAG, "jump: page=${bookmark.page}")

        if (exceedsFinal(bookmark.page, finalPage)) {
            throw FinalPageExceededException(
                attemptedPage = bookmark.page,
                finalPage = finalPage
            )
        }

        val probablySuccessBookmarkPage: PageState<T>? = getStateOf(bookmark.page)
        if (isFilledSuccessState(probablySuccessBookmarkPage)) {
            expandStartContextPage(probablySuccessBookmarkPage)
            expandEndContextPage(probablySuccessBookmarkPage)
            if (!silentlyResult) snapshot()
            logger.log(TAG, "jump: page=${bookmark.page} cache hit")
            return bookmark to probablySuccessBookmarkPage
        }

        if (!loadGuard.invoke(bookmark.page, probablySuccessBookmarkPage)) {
            throw LoadGuardedException(attemptedPage = bookmark.page)
        }

        startContextPage = bookmark.page
        endContextPage = bookmark.page

        loadOrGetPageState(
            page = bookmark.page,
            forceLoading = true,
            loading = { page: UInt, pageState: PageState<T>? ->
                val data: List<T> = pageState?.data.orEmpty()
                val progressState: ProgressPage<T> = initProgressState.invoke(page, data)
                setState(
                    state = progressState,
                    silently = true,
                )
                if (enableCacheFlow) {
                    repeatCacheFlow()
                }
                if (!silentlyLoading) {
                    snapshot()
                }
            },
            initEmptyState = initEmptyState,
            initSuccessState = initSuccessState,
            initErrorState = initErrorState
        ).also { resultState: PageState<T> ->
            setState(
                state = resultState,
                silently = true
            )
            expandStartContextPage(getStateOf(startContextPage))
            expandEndContextPage(getStateOf(endContextPage))

            if (enableCacheFlow) {
                repeatCacheFlow()
            }
            if (!silentlyResult) {
                snapshot()
            }

            logger.log(TAG, "jump: page=${bookmark.page} result=${resultState::class.simpleName}")
            return bookmark to resultState
        }
    }

    /**
     * If `true`, [goNextPage] is blocked and will throw [GoNextPageWasLockedException].
     * Reset to `false` on [MutablePaginator.release].
     */
    var lockGoNextPage: Boolean = false

    /**
     * Loads the next page after the current [endContextPage].
     *
     * If the paginator has not been started yet (i.e., no pages have been loaded),
     * this function automatically performs a [jump] to page 1.
     *
     * **Behavior:**
     * 1. Expands [endContextPage] forward through any contiguous filled success pages.
     * 2. Determines the next page number to load.
     * 3. If the next page exceeds [finalPage], throws [FinalPageExceededException].
     * 4. If the next page is already in a [ProgressPage] state, returns it immediately
     *    (deduplication — avoids double-loading).
     * 5. Invokes [loadGuard] — if it returns `false`, throws [LoadGuardedException],
     *    aborting the load before the network request.
     * 6. Sets the page to [ProgressPage] (with any previously cached data),
     *    loads from [source], and updates the cache.
     *
     * @param silentlyLoading If `true`, the snapshot will **not** be emitted when the
     *   page transitions to [ProgressPage] (loading state). Useful for background preloading.
     * @param silentlyResult If `true`, the snapshot will **not** be emitted after the
     *   page finishes loading (success/error/empty). Useful for batch operations.
     * @param finalPage Upper page boundary for this call. Defaults to [Paginator.finalPage].
     * @param loadGuard A guard callback invoked with `(page, currentState)` **before** the page
     *   is loaded from the source. Return `true` to proceed with loading, or `false` to abort.
     *   When `false` is returned, [LoadGuardedException] is thrown.
     *   Defaults to always allowing the load.
     * @param lockGoNextPage If `true`, the operation is blocked and [GoNextPageWasLockedException]
     *   is thrown immediately. Defaults to [Paginator.lockGoNextPage].
     * @param enableCacheFlow If `true`, the full cache flow ([asFlow]) is also updated
     *   during and after loading.
     * @param initProgressState Factory for creating [ProgressPage] instances during loading.
     * @param initEmptyState Factory for creating [EmptyPage] instances when the source returns no data.
     * @param initSuccessState Factory for creating [SuccessPage] instances on successful load.
     * @param initErrorState Factory for creating [ErrorPage] instances when the source throws.
     * @return The resulting [PageState] of the loaded page.
     * @throws GoNextPageWasLockedException If [lockGoNextPage] is `true`.
     * @throws FinalPageExceededException If the next page exceeds [finalPage].
     * @throws LoadGuardedException If [loadGuard] returns `false`.
     */
    suspend fun goNextPage(
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        finalPage: UInt = this.finalPage,
        loadGuard: (page: UInt, state: PageState<T>?) -> Boolean = { _, _ -> true },
        lockGoNextPage: Boolean = this.lockGoNextPage,
        enableCacheFlow: Boolean = this.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = this.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = this.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = this.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = this.initializerErrorPage
    ): PageState<T> = coroutineScope {
        if (lockGoNextPage) throw GoNextPageWasLockedException()
        logger.log(TAG, "goNextPage: endContextPage=$endContextPage")
        if (!isStarted) {
            logger.log(TAG, "goNextPage: not started, jumping to page 1")
            val pageState: PageState<T> = jump(
                bookmark = BookmarkUInt(page = 1u),
                silentlyLoading = silentlyLoading,
                silentlyResult = silentlyResult,
                finalPage = finalPage,
                enableCacheFlow = enableCacheFlow,
                initProgressState = initProgressState,
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState,
            ).second
            return@coroutineScope pageState
        }

        var pivotContextPage: UInt = endContextPage
        var pivotContextPageState: PageState<T>? = getStateOf(pivotContextPage)
        val isPivotContextPageValid: Boolean = isFilledSuccessState(pivotContextPageState)
        if (isPivotContextPageValid) {
            expandEndContextPage(getStateOf(pivotContextPage + 1u))
                ?.also { expanded: PageState<T> ->
                    pivotContextPage = expanded.page
                    pivotContextPageState = expanded
                }
        }

        val nextPage: UInt =
            if (isPivotContextPageValid) pivotContextPage + 1u
            else pivotContextPage

        if (exceedsFinal(nextPage, finalPage)) {
            throw FinalPageExceededException(
                attemptedPage = nextPage,
                finalPage = finalPage
            )
        }

        val nextPageState: PageState<T>? =
            if (nextPage == pivotContextPage) pivotContextPageState
            else getStateOf(nextPage)

        if (nextPageState.isProgressState())
            return@coroutineScope nextPageState

        if (!loadGuard.invoke(nextPage, nextPageState)) {
            throw LoadGuardedException(attemptedPage = nextPage)
        }

        loadOrGetPageState(
            page = nextPage,
            forceLoading = true,
            loading = { page: UInt, pageState: PageState<T>? ->
                val data: List<T> = pageState?.data.orEmpty()
                val progressState: ProgressPage<T> = initProgressState.invoke(page, data)
                setState(
                    state = progressState,
                    silently = true,
                )
                if (enableCacheFlow) {
                    repeatCacheFlow()
                }
                if (!silentlyLoading) {
                    snapshot()
                }
            },
            initEmptyState = initEmptyState,
            initSuccessState = initSuccessState,
            initErrorState = initErrorState
        ).also { resultState ->
            setState(
                state = resultState,
                silently = true
            )
            if (endContextPage == pivotContextPage
                && isFilledSuccessState(resultState)
            ) {
                endContextPage = nextPage
                expandEndContextPage(getStateOf(nextPage + 1u))
            }
            if (enableCacheFlow) {
                repeatCacheFlow()
            }
            if (!silentlyResult) {
                snapshot()
            }

            logger.log(TAG, "goNextPage: page=$nextPage result=${resultState::class.simpleName}")
            return@coroutineScope resultState
        }
    }

    /**
     * If `true`, [goPreviousPage] is blocked and will throw [GoPreviousPageWasLockedException].
     * Reset to `false` on [MutablePaginator.release].
     */
    var lockGoPreviousPage: Boolean = false

    /**
     * Loads the page before the current [startContextPage].
     *
     * Requires the paginator to be started (i.e., at least one [jump] must have been performed).
     * If the paginator is not started, an [IllegalStateException] is thrown.
     *
     * **Behavior:**
     * 1. Expands [startContextPage] backward through any contiguous filled success pages.
     * 2. Determines the previous page number to load.
     * 3. If the previous page is `0`, throws [IllegalStateException] (page numbers start at 1).
     * 4. If the previous page is already in a [ProgressPage] state, returns it immediately
     *    (deduplication — avoids double-loading).
     * 5. Invokes [loadGuard] — if it returns `false`, throws [LoadGuardedException],
     *    aborting the load before the network request.
     * 6. Sets the page to [ProgressPage] (with any previously cached data),
     *    loads from [source], and updates the cache.
     *
     * @param silentlyLoading If `true`, the snapshot will **not** be emitted when the
     *   page transitions to [ProgressPage]. Useful for background preloading.
     * @param silentlyResult If `true`, the snapshot will **not** be emitted after the
     *   page finishes loading. Useful for batch operations.
     * @param loadGuard A guard callback invoked with `(page, currentState)` **before** the page
     *   is loaded from the source. Return `true` to proceed with loading, or `false` to abort.
     *   When `false` is returned, [LoadGuardedException] is thrown.
     *   Defaults to always allowing the load.
     * @param enableCacheFlow If `true`, the full cache flow ([asFlow]) is also updated
     *   during and after loading.
     * @param initProgressState Factory for creating [ProgressPage] instances during loading.
     * @param initEmptyState Factory for creating [EmptyPage] instances when the source returns no data.
     * @param initSuccessState Factory for creating [SuccessPage] instances on successful load.
     * @param initErrorState Factory for creating [ErrorPage] instances when the source throws.
     * @return The resulting [PageState] of the loaded page.
     * @throws GoPreviousPageWasLockedException If [lockGoPreviousPage] is `true`.
     * @throws LoadGuardedException If [loadGuard] returns `false`.
     * @throws IllegalStateException If the paginator has not been started or the previous page is 0.
     */
    suspend fun goPreviousPage(
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        loadGuard: (page: UInt, state: PageState<T>?) -> Boolean = { _, _ -> true },
        enableCacheFlow: Boolean = this.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = this.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = this.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = this.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = this.initializerErrorPage
    ): PageState<T> = coroutineScope {
        if (lockGoPreviousPage) throw GoPreviousPageWasLockedException()
        logger.log(TAG, "goPreviousPage: startContextPage=$startContextPage")
        check(isStarted) {
            "startContextPage=0 or endContextPage=0 so paginator was not jumped (started). " +
                    "First of all paginator must be jumped (started). " +
                    "Please use jump function to start paginator before use goPreviousPage"
        }

        var pivotContextPage = startContextPage
        var pivotContextPageState = getStateOf(pivotContextPage)
        val pivotContextPageValid = isFilledSuccessState(pivotContextPageState)
        if (pivotContextPageValid) {
            expandStartContextPage(getStateOf(pivotContextPage - 1u))
                ?.also { expanded ->
                    pivotContextPage = expanded.page
                    pivotContextPageState = expanded
                }
        }

        val previousPage: UInt =
            if (pivotContextPageValid) pivotContextPage - 1u
            else pivotContextPage
        check(previousPage > 0u) { "previousPage is 0. you can't go to 0" }
        val previousPageState: PageState<T>? =
            if (previousPage == pivotContextPage) pivotContextPageState
            else getStateOf(previousPage)

        if (previousPageState.isProgressState())
            return@coroutineScope previousPageState

        if (!loadGuard.invoke(previousPage, previousPageState)) {
            throw LoadGuardedException(attemptedPage = previousPage)
        }

        loadOrGetPageState(
            page = previousPage,
            forceLoading = true,
            loading = { page: UInt, pageState: PageState<T>? ->
                val data: List<T> = pageState?.data.orEmpty()
                val progressState: ProgressPage<T> = initProgressState(page, data)
                setState(
                    state = progressState,
                    silently = true
                )
                if (enableCacheFlow) {
                    repeatCacheFlow()
                }
                if (!silentlyLoading) {
                    snapshot()
                }
            },
            initEmptyState = initEmptyState,
            initSuccessState = initSuccessState,
            initErrorState = initErrorState
        ).also { resulState: PageState<T> ->
            setState(
                state = resulState,
                silently = true
            )
            if (startContextPage == pivotContextPage
                && isFilledSuccessState(resulState)
            ) {
                startContextPage = previousPage
                expandStartContextPage(getStateOf(previousPage - 1u))
            }
            if (enableCacheFlow) {
                repeatCacheFlow()
            }
            if (!silentlyResult) {
                snapshot()
            }

            logger.log(TAG, "goPreviousPage: page=$previousPage result=${resulState::class.simpleName}")
            return@coroutineScope resulState
        }
    }

    /**
     * If `true`, [restart] is blocked and will throw [RestartWasLockedException].
     * Reset to `false` on [MutablePaginator.release].
     */
    var lockRestart: Boolean = false

    /**
     * Resets the paginator to its initial state and reloads the first page.
     *
     * Clears all cached pages except page 1's structure, resets the context window
     * to page 1, and reloads it from the [source]. Ideal for swipe-to-refresh scenarios.
     *
     * **Behavior:**
     * 1. Removes all pages from the cache except page 1.
     * 2. Invokes [loadGuard] with page `1` — if it returns `false`, throws [LoadGuardedException].
     * 3. Sets page 1 to [ProgressPage] and reloads from [source].
     * 4. Updates the cache with the result.
     *
     * @param silentlyLoading If `true`, the snapshot will **not** be emitted during loading.
     * @param silentlyResult If `true`, the snapshot will **not** be emitted after loading.
     * @param loadGuard A guard callback invoked with `(page, currentState)` **before** page 1
     *   is reloaded. Return `true` to proceed, or `false` to abort.
     *   When `false` is returned, [LoadGuardedException] is thrown.
     *   Defaults to always allowing the load.
     * @param enableCacheFlow If `true`, the full cache flow ([asFlow]) is also updated.
     * @param initProgressState Factory for creating [ProgressPage] instances during loading.
     * @param initEmptyState Factory for creating [EmptyPage] instances when the source returns no data.
     * @param initSuccessState Factory for creating [SuccessPage] instances on successful load.
     * @param initErrorState Factory for creating [ErrorPage] instances when the source throws.
     * @throws RestartWasLockedException If [lockRestart] is `true`.
     * @throws LoadGuardedException If [loadGuard] returns `false`.
     */
    suspend fun restart(
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        loadGuard: (page: UInt, state: PageState<T>?) -> Boolean = { _, _ -> true },
        enableCacheFlow: Boolean = this.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = this.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = this.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = this.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = this.initializerErrorPage
    ): Unit = coroutineScope {
        if (lockRestart) throw RestartWasLockedException()
        logger.log(TAG, "restart")

        val firstPage = cache.getValue(1u)
        cache.clear()
        setState(
            state = firstPage,
            silently = true
        )

        if (!loadGuard.invoke(1u, firstPage)) {
            throw LoadGuardedException(attemptedPage = 1u)
        }

        startContextPage = 1u
        endContextPage = 1u
        loadOrGetPageState(
            page = 1u,
            forceLoading = true,
            loading = { page, pageState ->
                setState(
                    state = initProgressState.invoke(page, pageState?.data.orEmpty()),
                    silently = true
                )
                if (enableCacheFlow) repeatCacheFlow()
                if (!silentlyLoading) snapshot(1u..1u)
            },
            initEmptyState = initEmptyState,
            initSuccessState = initSuccessState,
            initErrorState = initErrorState
        ).also { resultPageState ->
            setState(
                state = resultPageState,
                silently = true
            )
            if (enableCacheFlow) repeatCacheFlow()
            if (!silentlyResult) snapshot(1u..1u)
            logger.log(TAG, "restart: result=${resultPageState::class.simpleName}")
        }
    }

    /**
     * If `true`, [refresh] is blocked and will throw [RefreshWasLockedException].
     * Reset to `false` on [MutablePaginator.release].
     */
    var lockRefresh: Boolean = false

    /**
     * Refreshes the specified pages by reloading them from the [source] in parallel.
     *
     * Each page is first set to [ProgressPage] (preserving any previously cached data),
     * then all pages are reloaded concurrently. After all loads complete, the cache is
     * updated with the results.
     *
     * **Behavior:**
     * 1. Invokes [loadGuard] for each page — if it returns `false` for any page,
     *    throws [LoadGuardedException] and **none** of the pages are refreshed.
     * 2. Sets all specified pages to [ProgressPage].
     * 3. Loads all pages concurrently from [source].
     * 4. Updates the cache with results.
     *
     * @param pages The list of page numbers to refresh.
     * @param loadingSilently If `true`, the snapshot will **not** be emitted after setting
     *   pages to [ProgressPage].
     * @param finalSilently If `true`, the snapshot will **not** be emitted after all pages
     *   finish loading.
     * @param loadGuard A guard callback invoked with `(page, currentState)` for **each** page
     *   **before** any loading begins. Return `true` to proceed, or `false` to abort the
     *   entire refresh. When `false` is returned, [LoadGuardedException] is thrown.
     *   Defaults to always allowing the load.
     * @param enableCacheFlow If `true`, the full cache flow ([asFlow]) is also updated.
     * @param initProgressState Factory for creating [ProgressPage] instances during loading.
     * @param initEmptyState Factory for creating [EmptyPage] instances when the source returns no data.
     * @param initSuccessState Factory for creating [SuccessPage] instances on successful load.
     * @param initErrorState Factory for creating [ErrorPage] instances when the source throws.
     * @throws RefreshWasLockedException If [lockRefresh] is `true`.
     * @throws LoadGuardedException If [loadGuard] returns `false` for any page.
     */
    suspend fun refresh(
        pages: List<UInt>,
        loadingSilently: Boolean = false,
        finalSilently: Boolean = false,
        loadGuard: (page: UInt, state: PageState<T>?) -> Boolean = { _, _ -> true },
        enableCacheFlow: Boolean = this.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = this.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = this.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = this.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = this.initializerErrorPage
    ): Unit = coroutineScope {
        if (lockRefresh) throw RefreshWasLockedException()
        logger.log(TAG, "refresh: pages=$pages")

        pages.forEach { page ->
            if (!loadGuard.invoke(page, cache[page])) {
                throw LoadGuardedException(attemptedPage = page)
            }
        }

        pages.forEach { page ->
            setState(
                state = initProgressState.invoke(page, cache[page]?.data.orEmpty()),
                silently = true
            )
        }
        if (enableCacheFlow) {
            repeatCacheFlow()
        }
        if (!loadingSilently) {
            snapshot()
        }

        pages.map { page ->
            async {
                loadOrGetPageState(
                    page = page,
                    forceLoading = true,
                    initEmptyState = initEmptyState,
                    initSuccessState = initSuccessState,
                    initErrorState = initErrorState
                ).also { finalPageState ->
                    setState(
                        state = finalPageState,
                        silently = true
                    )
                }
            }
        }.forEach { it.await() }

        if (enableCacheFlow) repeatCacheFlow()
        if (!finalSilently) snapshot()
        logger.log(TAG, "refresh: pages=$pages completed")
    }

    /**
     * Loads a page from the [source] or returns the cached state if it is already a filled success page.
     *
     * This is the low-level loading primitive used internally by [goNextPage], [goPreviousPage],
     * [jump], [restart], and [refresh]. It does **not** update context pages or emit snapshots —
     * callers are responsible for that.
     *
     * **Behavior:**
     * 1. If [forceLoading] is `false` and the cached state is a filled success page, returns it immediately.
     * 2. Invokes the [loading] callback (used by callers to set [ProgressPage] and emit snapshots).
     * 3. Calls [source] to fetch data.
     * 4. Returns [SuccessPage], [EmptyPage], or [ErrorPage] based on the result.
     *
     * @param page The page number to load.
     * @param forceLoading If `true`, always reloads from [source] even if a valid cached state exists.
     * @param loading A callback invoked just before loading starts. Typically used to set the page
     *   to [ProgressPage] and emit a snapshot.
     * @param source The data source suspend function. Defaults to [Paginator.source].
     * @param initEmptyState Factory for [EmptyPage] when the source returns an empty list.
     * @param initSuccessState Factory for [SuccessPage] when the source returns data.
     * @param initErrorState Factory for [ErrorPage] when the source throws an exception.
     * @return The resulting [PageState] after loading or from cache.
     */
    suspend inline fun loadOrGetPageState(
        page: UInt,
        forceLoading: Boolean = false,
        loading: ((page: UInt, pageState: PageState<T>?) -> Unit) = { _, _ -> },
        noinline source: suspend Paginator<T>.(page: UInt) -> List<T> = this.source,
        noinline initEmptyState: InitializerEmptyPage<T> = initializerEmptyPage,
        noinline initSuccessState: InitializerSuccessPage<T> = initializerSuccessPage,
        noinline initErrorState: InitializerErrorPage<T> = initializerErrorPage
    ): PageState<T> {
        logger.log(TAG, "loadOrGetPageState: page=$page forceLoading=$forceLoading")
        val cachedState: PageState<T>? = getStateOf(page)
        if (!forceLoading && isFilledSuccessState(cachedState)) {
            logger.log(TAG, "loadOrGetPageState: page=$page cachedState(isFilledSuccessState)=$cachedState")
            return cachedState
        }
        loading.invoke(page, cachedState)
        return try {
            val data: MutableList<T> =
                source.invoke(this, page)
                    .toMutableList()
            if (data.isEmpty()) {
                logger.log(TAG, "loadOrGetPageState: page=$page data.isEmpty()")
                initEmptyState.invoke(page, data)
            } else {
                logger.log(TAG, "loadOrGetPageState: page=$page data.isNotEmpty()")
                initSuccessState.invoke(page, data)
            }
        } catch (exception: Exception) {
            logger.log(TAG, "loadOrGetPageState: page=$page exception=$exception")
            initErrorState.invoke(exception, page, cachedState?.data.orEmpty())
        }
    }

    /**
     * Retrieves the cached [PageState] for the given [page] number.
     *
     * @param page The page number to look up.
     * @return The cached [PageState], or `null` if the page is not in the cache.
     */
    fun getStateOf(page: UInt): PageState<T>? {
        logger.log(TAG, "getStateOf: page=$page")
        return cache[page]
    }

    /**
     * Stores a [PageState] in the cache, replacing any existing state for that page number.
     *
     * The page number is determined by [state]`.page`.
     *
     * @param state The page state to store.
     * @param silently If `true`, the change will **not** trigger a [snapshot] emission.
     *   Set to `true` during batch operations to avoid redundant emissions.
     */
    open fun setState(
        state: PageState<T>,
        silently: Boolean = false
    ) {
        logger.log(TAG, "setState: page=${state.page}")
        cache[state.page] = state
        if (!silently) {
            snapshot()
        }
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
        page: UInt,
        index: Int,
    ): T? {
        return getStateOf(page)
            ?.data?.get(index)
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
    fun snapshot(pageRange: UIntRange? = null) {
        (pageRange ?: run {
            if (!isStarted) return@run null
            val min = walkBackwardWhile(cache[startContextPage])?.page
            val max = walkForwardWhile(cache[endContextPage])?.page
            return@run if (min != null && max != null) min..max
            else null
        })?.let { mPageRange: UIntRange ->
            _snapshot.update { false to emptyList() }
            _snapshot.update { true to scan(mPageRange) }
        }
    }

    /**
     * Returns a list of [PageState] objects for all contiguous cached pages within [pagesRange].
     *
     * Iterates through the range and collects cached pages. Stops at the first gap
     * (page not in the cache), ensuring only contiguous pages are returned.
     *
     * @param pagesRange The range of page numbers to scan. Defaults to the expanded
     *   context window ([startContextPage]..[endContextPage] plus adjacent pages).
     * @return A list of contiguous [PageState] objects within the range.
     * @throws IllegalStateException If [startContextPage] or [endContextPage] is `0u`
     *   (when using the default range).
     */
    fun scan(
        pagesRange: UIntRange = run {
            check(startContextPage != 0u) { "You cannot scan because startContextPage is 0" }
            check(endContextPage != 0u) { "You cannot scan because endContextPage is 0" }
            val min = walkBackwardWhile(cache[startContextPage])?.page
            val max = walkForwardWhile(cache[endContextPage])?.page
            checkNotNull(min) { "min is null the data structure is broken!" }
            checkNotNull(max) { "max is null the data structure is broken!" }
            return@run min..max
        }
    ): List<PageState<T>> {
        val capacity: UInt = max(pagesRange.last - pagesRange.first, 1u)
        return buildList(capacity.toInt()) {
            for (page in pagesRange) {
                val pageState: PageState<T> = getStateOf(page) ?: break
                this.add(pageState)
            }
        }
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
     * that the returned value is a valid page number (e.g., non-negative within range).
     *
     * @param predicate A condition that each visited PageState must satisfy.
     *
     * @return The last PageState encountered that satisfies [predicate],
     * or null if [pivotState] is null or does not satisfy [predicate].
     */
    inline fun walkWhile(
        pivotState: PageState<T>?,
        next: (current: UInt) -> UInt,
        predicate: (PageState<T>) -> Boolean = { true }
    ): PageState<T>? {
        if (pivotState == null) {
            return null
        }
        if (!predicate.invoke(pivotState)) {
            return null
        }

        var resultState: PageState<T> = pivotState
        while (true) {
            val nextPage: UInt = next.invoke(resultState.page)
            val state: PageState<T>? = getStateOf(nextPage)
            if (state != null && predicate.invoke(state)) {
                resultState = state
            } else {
                return resultState
            }
        }
    }

    override operator fun compareTo(other: Paginator<*>): Int = this.size - other.size

    operator fun iterator(): MutableIterator<MutableMap.MutableEntry<UInt, PageState<T>>> {
        return cache.iterator()
    }

    operator fun contains(page: UInt): Boolean = getStateOf(page) != null

    operator fun contains(pageState: PageState<T>): Boolean = getStateOf(pageState.page) != null

    operator fun get(page: UInt): PageState<T>? = getStateOf(page)

    operator fun get(page: UInt, index: Int): T? = getElement(page, index)

    override fun toString(): String = "Paginator(pages=$cache, bookmarks=$bookmarks)"

    override fun hashCode(): Int = cache.hashCode()

    override fun equals(other: Any?): Boolean = this === other

    companion object {
        const val TAG = "Paginator"
        const val DEFAULT_CAPACITY = 20
        const val UNLIMITED_CAPACITY = 0
    }
}
