package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.Bookmark
import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkUInt
import com.jamal_aliev.paginator.exception.LockedException.GoNextPageWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.GoPreviousPageWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.JumpWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.RefreshWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.RestartWasLockedException
import com.jamal_aliev.paginator.extension.far
import com.jamal_aliev.paginator.extension.gap
import com.jamal_aliev.paginator.extension.isProgressState
import com.jamal_aliev.paginator.extension.isSuccessState
import com.jamal_aliev.paginator.extension.smartForEach
import com.jamal_aliev.paginator.initializer.InitializerEmptyPage
import com.jamal_aliev.paginator.initializer.InitializerErrorPage
import com.jamal_aliev.paginator.initializer.InitializerProgressPage
import com.jamal_aliev.paginator.initializer.InitializerSuccessPage
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

open class MutablePaginator<T>(
    var source: suspend MutablePaginator<T>.(page: UInt) -> List<T>
) : Comparable<MutablePaginator<*>> {

    var capacity: Int = DEFAULT_CAPACITY
        private set

    val ignoreCapacity: Boolean
        get() = capacity == IGNORE_CAPACITY

    protected val cache = sortedMapOf<UInt, PageState<T>>()
    val pages: List<UInt> get() = cache.keys.toList()
    val pageStates: List<PageState<T>> get() = cache.values.toList()
    val size: Int get() = cache.size

    private var enableCacheFlow = false
    private val _cacheFlow = MutableStateFlow(false to cache)
    fun asFlow(): Flow<Map<UInt, PageState<T>>> {
        enableCacheFlow = true
        return _cacheFlow.asStateFlow()
            .filter { it.first }
            .map { it.second }
    }

    /**
     * repeat emit the cache variable into _cacheFlow
     * */
    fun repeatCacheFlow() {
        _cacheFlow.update { false to sortedMapOf() }
        _cacheFlow.update { true to cache }
    }

    /**
     * This is variable that holds the left valid success page expect a jump situation
     * */
    var startContextPage = 0u
        private set

    protected fun expandStartContextPage(pageState: PageState<T>?): PageState<T>? {
        return walkBackwardWhile(pageState, ::isValidSuccessState)
            ?.also { startContextPage = it.page }
    }

    /**
     * This is a variable that holds the right valid success page expect a jump situation
     * */
    var endContextPage = 0u
        private set

    protected fun expandEndContextPage(pageState: PageState<T>?): PageState<T>? {
        return walkForwardWhile(pageState, ::isValidSuccessState)
            ?.also { endContextPage = it.page }
    }

    val isStarted: Boolean get() = startContextPage > 0u && endContextPage > 0u

    fun findNearContextPage(startPoint: UInt = 1u, endPoint: UInt = startPoint) {
        require(startPoint >= 1u) { "startPoint must be greater than zero" }
        require(endPoint >= 1u) { "endPoint must be greater than zero" }
        require(endPoint >= startPoint) { "endPoint must be greater than startPoint" }

        fun find(sPont: UInt, ePoint: UInt) {
            // example 1(0), 2(1), 3(2), 21(3), 22(4), 23(5)
            val validStates = this.pageStates.filter(::isValidSuccessState)
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
        val pointState = getPageState(startPoint)
        if (isValidSuccessState(pointState)) {
            // startPoint (== endPoint) is a valid page state,
            // so we can just expand the context
            startContextPage = startPoint
            endContextPage = startPoint
            expandStartContextPage(getPageState(pointState.page - 1u))
            expandEndContextPage(getPageState(pointState.page + 1u))
        } else {
            // startPoint (== endPoint) is not a valid page state,
            // so we need to find around it the nearest valid page state
            find(
                sPont = startPoint - 1u,
                ePoint = endPoint + 1u
            )
        }
    }

    val bookmarks: MutableList<Bookmark> = mutableListOf(BookmarkUInt(page = 1u))
    var recyclingBookmark = false
    private var bookmarkIterator = bookmarks.listIterator()

    private val _snapshot = MutableStateFlow(false to emptyList<PageState<T>>())
    val snapshot: Flow<List<PageState<T>>>
        get() {
            return _snapshot.asStateFlow()
                .filter { it.first }
                .map { it.second }
        }

    var initializerProgressPage: InitializerProgressPage<T> =
        fun(page: UInt, data: List<T>): ProgressPage<T> {
            return ProgressPage(page = page, data = data)
        }
    var initializerSuccessPage: InitializerSuccessPage<T> =
        fun(page: UInt, data: List<T>): SuccessPage<T> {
            return if (data.isEmpty()) initializerEmptyPage.invoke(page, data)
            else SuccessPage(page = page, data = data)
        }
    var initializerEmptyPage: InitializerEmptyPage<T> =
        fun(page: UInt, data: List<T>): EmptyPage<T> {
            return EmptyPage(page = page, data = data)
        }
    var initializerErrorPage: InitializerErrorPage<T> =
        fun(exception: Exception, page: UInt, data: List<T>): ErrorPage<T> {
            return ErrorPage(exception = exception, page = page, data = data)
        }

    /**
     * Moves forward to the next bookmark in the list.
     *
     * @param recycling Whether to recycle bookmarks when reaching the end.
     * @param silentlyLoading Whether to update the snapshot silently during loading.
     * @param silentlyResult Whether to update the snapshot silently after loading.
     * @param initProgressState Custom initializer for progress state.
     * @param initSuccessState Custom initializer for success state.
     * @param initEmptyState Custom initializer for empty state.
     * @param initErrorState Custom initializer for error state.
     * @return The next bookmark, or null if there are no more bookmarks.
     * @throws JumpWasLockedException If jumping is locked.
     */
    suspend fun jumpForward(
        recycling: Boolean = recyclingBookmark,
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        enableCacheFlow: Boolean = this.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = initializerProgressPage,
        initSuccessState: InitializerSuccessPage<T> = initializerSuccessPage,
        initEmptyState: InitializerEmptyPage<T> = initializerEmptyPage,
        initErrorState: InitializerErrorPage<T> = initializerErrorPage
    ): Pair<Bookmark, PageState<T>>? {
        if (lockJump) throw JumpWasLockedException()

        var bookmark: Bookmark? =
            bookmarkIterator
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
                enableCacheFlow = enableCacheFlow,
                initProgressState = initProgressState,
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState,
            )
        }

        return null
    }

    /**
     * Moves backward to the previous bookmark in the list.
     *
     * @param recycling Whether to recycle bookmarks when reaching the beginning.
     * @param silentlyLoading Whether to update the snapshot silently during loading.
     * @param silentlyResult Whether to update the snapshot silently after loading.
     * @param initProgressState Custom initializer for progress state.
     * @param initSuccessState Custom initializer for success state.
     * @param initEmptyState Custom initializer for empty state.
     * @param initErrorState Custom initializer for error state.
     * @return The previous bookmark, or null if there are no more bookmarks.
     * @throws JumpWasLockedException If jumping is locked.
     */
    suspend fun jumpBack(
        recycling: Boolean = recyclingBookmark,
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        enableCacheFlow: Boolean = this.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = initializerErrorPage
    ): Pair<Bookmark, PageState<T>>? {
        if (lockJump) throw JumpWasLockedException()

        var bookmark = bookmarkIterator
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
                enableCacheFlow = enableCacheFlow,
                initProgressState = initProgressState,
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState,
            )
        }

        return null
    }

    var lockJump = false

    /**
     * Jumps to a specific bookmark.
     *
     * @param bookmark The bookmark to jump to.
     * @param silentlyLoading Whether to update the snapshot silently during loading.
     * @param silentlyResult Whether to update the snapshot silently after loading.
     * @param initProgressState Custom initializer for progress state.
     * @param initSuccessState Custom initializer for success state.
     * @param initEmptyState Custom initializer for empty state.
     * @param initErrorState Custom initializer for error state.
     * @return The provided bookmark.
     * @throws JumpWasLockedException If jumping is locked.
     */
    suspend fun jump(
        bookmark: Bookmark,
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        enableCacheFlow: Boolean = this.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = initializerErrorPage
    ): Pair<Bookmark, PageState<T>> {
        if (lockJump) throw JumpWasLockedException()

        check(bookmark.page > 0u) { "bookmark.page should be greater than 0" }

        val probablySuccessBookmarkPage = cache[bookmark.page]
        if (isValidSuccessState(probablySuccessBookmarkPage)) {
            expandStartContextPage(probablySuccessBookmarkPage)
            expandEndContextPage(probablySuccessBookmarkPage)
            if (!silentlyResult) snapshot()
            return bookmark to probablySuccessBookmarkPage
        }

        startContextPage = bookmark.page
        endContextPage = bookmark.page

        loadOrGetPageState(
            page = bookmark.page,
            forceLoading = true,
            loading = { page, pageState ->
                setPageState(
                    state = initProgressState.invoke(page, pageState?.data.orEmpty()),
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
        ).also { resultPageState ->
            setPageState(
                state = resultPageState,
                silently = true
            )
            expandStartContextPage(getPageState(startContextPage))
            expandEndContextPage(getPageState(endContextPage))

            if (enableCacheFlow) {
                repeatCacheFlow()
            }
            if (!silentlyResult) {
                snapshot()
            }

            return bookmark to resultPageState
        }
    }

    var lockGoNextPage: Boolean = false

    /**
     * Loads the next page.
     *
     * @param silentlyLoading Whether to update the snapshot silently during loading.
     * @param silentlyResult Whether to update the snapshot silently after loading.
     * @param initProgressState Custom initializer for progress state.
     * @param initEmptyState Custom initializer for empty state.
     * @param initSuccessState Custom initializer for success state.
     * @param initErrorState Custom initializer for error state.
     * @return The page number of the next page.
     * @throws GoNextPageWasLockedException If moving to the next page is locked.
     */
    suspend fun goNextPage(
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        enableCacheFlow: Boolean = this.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = this.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = this.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = this.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = this.initializerErrorPage
    ): PageState<T> = coroutineScope {
        if (lockGoNextPage) throw GoNextPageWasLockedException()
        if (!isStarted) {
            val pageState: PageState<T> = jump(
                bookmark = BookmarkUInt(page = 1u),
                silentlyLoading = silentlyLoading,
                silentlyResult = silentlyResult,
                enableCacheFlow = enableCacheFlow,
                initProgressState = initProgressState,
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState,
            ).second
            return@coroutineScope pageState
        }

        var pivotContextPage: UInt = endContextPage
        var pivotContextPageState: PageState<T>? = cache[pivotContextPage]
        val isPivotContextPageValid: Boolean = isValidSuccessState(pivotContextPageState)
        if (isPivotContextPageValid) {
            expandEndContextPage(getPageState(pivotContextPage + 1u))
                ?.also { expanded ->
                    pivotContextPage = expanded.page
                    pivotContextPageState = expanded
                }
        }

        val nextPage: UInt =
            if (isPivotContextPageValid) pivotContextPage + 1u
            else pivotContextPage
        val nextPageState: PageState<T>? =
            if (nextPage == pivotContextPage) pivotContextPageState
            else getPageState(nextPage)

        if (nextPageState.isProgressState())
            return@coroutineScope nextPageState

        loadOrGetPageState(
            page = nextPage,
            forceLoading = true,
            loading = { page, pageState ->
                setPageState(
                    state = initProgressState.invoke(page, pageState?.data.orEmpty()),
                    silently = true,
                )
                if (enableCacheFlow) repeatCacheFlow()
                if (!silentlyLoading) snapshot()
            },
            initEmptyState = initEmptyState,
            initSuccessState = initSuccessState,
            initErrorState = initErrorState
        ).also { resultPageState ->
            setPageState(
                state = resultPageState,
                silently = true
            )
            if (endContextPage == pivotContextPage
                && isValidSuccessState(resultPageState)
            ) {
                endContextPage = nextPage
                expandEndContextPage(getPageState(nextPage + 1u))
            }
            if (enableCacheFlow) repeatCacheFlow()
            if (!silentlyResult) snapshot()

            return@coroutineScope resultPageState
        }
    }

    var lockGoPreviousPage: Boolean = false

    /**
     * Loads the previous page.
     *
     * @param silentlyLoading Whether to update the snapshot silently during loading.
     * @param silentlyResult Whether to update the snapshot silently after loading.
     * @param initProgressState Custom initializer for progress state.
     * @param initEmptyState Custom initializer for empty state.
     * @param initSuccessState Custom initializer for success state.
     * @param initErrorState Custom initializer for error state.
     * @return The page number of the previous page.
     * @throws GoPreviousPageWasLockedException If moving to the previous page is locked.
     */
    suspend fun goPreviousPage(
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        enableCacheFlow: Boolean = this.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = this.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = this.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = this.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = this.initializerErrorPage
    ): PageState<T> = coroutineScope {
        if (lockGoPreviousPage) throw GoPreviousPageWasLockedException()
        check(isStarted) {
            "startContextPage=0 or endContextPage=0 so paginator was not jumped (started). " +
                    "First of all paginator must be jumped (started). " +
                    "Please use jump function to start paginator before use goPreviousPage"
        }

        var pivotContextPage = startContextPage
        var pivotContextPageState = cache[pivotContextPage]
        val pivotContextPageValid = isValidSuccessState(pivotContextPageState)
        if (pivotContextPageValid) {
            expandStartContextPage(getPageState(pivotContextPage - 1u))
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
            else getPageState(previousPage)

        if (previousPageState.isProgressState())
            return@coroutineScope previousPageState

        loadOrGetPageState(
            page = previousPage,
            forceLoading = true,
            loading = { page, pageState ->
                setPageState(
                    state = initProgressState(page, pageState?.data.orEmpty()),
                    silently = true
                )
                if (enableCacheFlow) repeatCacheFlow()
                if (!silentlyLoading) snapshot()
            },
            initEmptyState = initEmptyState,
            initSuccessState = initSuccessState,
            initErrorState = initErrorState
        ).also { resultPageState ->
            setPageState(
                state = resultPageState,
                silently = true
            )
            if (startContextPage == pivotContextPage
                && isValidSuccessState(resultPageState)
            ) {
                startContextPage = previousPage
                expandStartContextPage(getPageState(previousPage - 1u))
            }
            if (enableCacheFlow) repeatCacheFlow()
            if (!silentlyResult) snapshot()

            return@coroutineScope resultPageState
        }
    }

    var lockRestart: Boolean = false

    /**
     * Resets the paginator to its initial state and reloads the first page.
     *
     * @param silentlyLoading Whether the loading state should be set silently.
     * @param silentlyResult Whether the result state should be set silently.
     * @param initProgressState Custom initializer for the progress state page.
     * @param initEmptyState Custom initializer for the empty state page.
     * @param initSuccessState Custom initializer for the success state page.
     * @param initErrorState Custom initializer for the error state page.
     * @throws RestartWasLockedException if the restart process is locked.
     */
    suspend fun restart(
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        enableCacheFlow: Boolean = this.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = this.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = this.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = this.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = this.initializerErrorPage
    ): Unit = coroutineScope {
        if (lockRestart) throw RestartWasLockedException()

        val firstPage = cache.getValue(1u)
        cache.clear()
        setPageState(
            state = firstPage,
            silently = true
        )

        startContextPage = 1u
        endContextPage = 1u
        loadOrGetPageState(
            page = 1u,
            forceLoading = true,
            loading = { page, pageState ->
                setPageState(
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
            setPageState(
                state = resultPageState,
                silently = true
            )
            if (enableCacheFlow) repeatCacheFlow()
            if (!silentlyResult) snapshot(1u..1u)
        }
    }

    /**
     * Indicates whether the refresh operation is locked. If true, any attempts to refresh will throw an exception.
     */
    var lockRefresh: Boolean = false

    /**
     * Refreshes the specified pages by setting their state to a progress state, loading their data,
     * and then updating their state based on the loaded data.
     *
     * @param pages The list of pages to refresh.
     * @param loadingSilently If true, the loading state will not trigger snapshot updates.
     * @param finalSilently If true, the final state will not trigger snapshot updates.
     * @param initProgressState Initializer for the progress state.
     * @param initEmptyState Initializer for the empty state.
     * @param initSuccessState Initializer for the success state.
     * @param initErrorState Initializer for the error state.
     * @throws RefreshWasLockedException if the refresh operation is locked.
     */
    suspend fun refresh(
        pages: List<UInt>,
        loadingSilently: Boolean = false,
        finalSilently: Boolean = false,
        enableCacheFlow: Boolean = this.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = this.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = this.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = this.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = this.initializerErrorPage
    ): Unit = coroutineScope {
        if (lockRefresh) throw RefreshWasLockedException()

        pages.forEach { page ->
            setPageState(
                state = initProgressState.invoke(page, cache[page]?.data.orEmpty()),
                silently = true
            )
        }
        if (enableCacheFlow) repeatCacheFlow()
        if (!loadingSilently) snapshot()

        pages.map { page ->
            async {
                loadOrGetPageState(
                    page = page,
                    forceLoading = true,
                    initEmptyState = initEmptyState,
                    initSuccessState = initSuccessState,
                    initErrorState = initErrorState
                ).also { finalPageState ->
                    setPageState(
                        state = finalPageState,
                        silently = true
                    )
                }
            }
        }.forEach { it.await() }

        if (enableCacheFlow) repeatCacheFlow()
        if (!finalSilently) snapshot()
    }

    /**
     * Refreshes all pages in the cache by setting their state to a progress state, loading their data,
     * and then updating their state based on the loaded data.
     *
     * @param loadingSilently If true, the loading state will not trigger snapshot updates.
     * @param finalSilently If true, the final state will not trigger snapshot updates.
     * @param initProgressState Initializer for the progress state.
     * @param initEmptyState Initializer for the empty state.
     * @param initSuccessState Initializer for the success state.
     * @param initErrorState Initializer for the error state.
     * @throws RefreshWasLockedException if the refresh operation is locked.
     */
    suspend fun refreshAll(
        loadingSilently: Boolean = false,
        finalSilently: Boolean = false,
        enableCacheFlow: Boolean = this.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = this.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = this.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = this.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = this.initializerErrorPage
    ) {
        if (lockRefresh) throw RefreshWasLockedException()
        return refresh(
            pages = cache.keys.toList(),
            loadingSilently = loadingSilently,
            finalSilently = finalSilently,
            enableCacheFlow = enableCacheFlow,
            initProgressState = initProgressState,
            initEmptyState = initEmptyState,
            initSuccessState = initSuccessState,
            initErrorState = initErrorState
        )
    }

    /**
     * Loads or retrieves the state of a page. If forceLoading is true, the page state will be reloaded
     * from the source, otherwise, it will return the cached state if it is valid.
     *
     * @param page The page number to load or get the state.
     * @param forceLoading If true, the page state will be reloaded from the source.
     * @param loading A callback invoked when the loading starts.
     * @param source A suspend function that provides the data for the page.
     * @param initEmptyState Initializer for the empty state.
     * @param initSuccessState Initializer for the success state.
     * @param initErrorState Initializer for the error state.
     * @return The state of the page after loading or getting from the cache.
     */
    suspend inline fun loadOrGetPageState(
        page: UInt,
        forceLoading: Boolean = false,
        loading: ((page: UInt, pageState: PageState<T>?) -> Unit) = { _, _ -> },
        noinline source: suspend MutablePaginator<T>.(page: UInt) -> List<T> = this.source,
        noinline initEmptyState: InitializerEmptyPage<T> = initializerEmptyPage,
        noinline initSuccessState: InitializerSuccessPage<T> = initializerSuccessPage,
        noinline initErrorState: InitializerErrorPage<T> = initializerErrorPage
    ): PageState<T> {
        return try {
            val cachedState = if (forceLoading) null else getPageState(page)
            if (isValidSuccessState(cachedState)) return cachedState
            loading.invoke(page, cachedState)
            val data: MutableList<T> =
                source.invoke(this, page)
                    .toMutableList()
            if (data.isEmpty()) initEmptyState.invoke(page, data)
            else initSuccessState.invoke(page, data)
        } catch (exception: Exception) {
            initErrorState.invoke(exception, page, emptyList())
        }
    }

    /**
     * Removes the state of the specified page from the cache and adjusts surrounding pages and context.
     *
     * This function handles both simple removals and complex cases where pages are non-contiguous:
     * - Finds the state of the page [pageToRemove] in the cache.
     * - If the page exists, removes it and, if necessary, collapses consecutive pages to maintain
     *   correct page numbering.
     * - Detects gaps in the page sequence and ensures context boundaries ([startContextPage] and [endContextPage])
     *   are updated correctly.
     * - Handles edge cases such as removing the first page, last page, or pages in the middle of a gap.
     * - If [silently] is false, takes a snapshot of the current paginator state via [snapshot].
     *
     * The function works in both started and non-started states of the paginator:
     * - In a non-started state, the page is simply removed from the cache.
     * - In a started state, it iterates over all page states using [smartForEach], collapsing pages and
     *   recalculating context boundaries as needed.
     *
     * @param pageToRemove The page number whose state should be removed.
     * @param silently If true, removal will not trigger a snapshot update.
     * @return The removed page state ([PageState<T>]), or null if the page was not found.
     *
     * see inner fun collapse
     * see inner fun recalculateContext
     */
    fun removePageState(
        pageToRemove: UInt,
        silently: Boolean = false,
    ): PageState<T>? {

        fun collapse(startPage: UInt, compression: Int) {
            var currentState: PageState<T> = checkNotNull(
                value = cache.remove(startPage)
            ) { "it's imposable to start collapse from this page" }
            var remaining: Int = compression
            while (remaining > 0) {
                val collapsedState: PageState<T> = currentState.copy(page = currentState.page - 1u)
                val pageState: PageState<T> = getPageState(currentState.page - 1u) ?: break
                setPageState(state = collapsedState, silently = true)
                currentState = pageState
                remaining--
            }
        }

        fun recalculateContext(removedPage: UInt) {
            // Using explicit comparison for performance: avoid creating a UIntRange object
            if (startContextPage <= removedPage && removedPage <= endContextPage) {
                if (endContextPage - startContextPage > 0u) {
                    // Just shrink the context by one page
                    endContextPage--
                } else if (removedPage == 1u) {
                    // If the first page was removed, find the nearest page
                    findNearContextPage()
                } else {
                    // Otherwise, find the nearest pages around the removed page
                    findNearContextPage(removedPage - 1u, removedPage + 1u)
                }
            }
        }

        var pageStateWillRemove: PageState<T>?
        if (!isStarted) {
            pageStateWillRemove = cache.remove(pageToRemove)
        } else {
            pageStateWillRemove = getPageState(pageToRemove) ?: return null
            var indexOfPageWillRemove = -1
            var indexOfStartContext = -1
            var haveRemoved = false
            var previousPageState: PageState<T>? = null
            smartForEach(
                initialIndex = { states: List<PageState<T>> ->
                    indexOfPageWillRemove =
                        states.binarySearch { state: PageState<T> ->
                            state.compareTo(pageStateWillRemove)
                        }
                    indexOfStartContext = indexOfPageWillRemove
                    return@smartForEach indexOfPageWillRemove
                }
            ) { states: List<PageState<T>>, index: Int, currentState: PageState<T> ->
                previousPageState = previousPageState ?: currentState
                if (previousPageState far currentState) {
                    // pages example: 1,2,3 gap 11,12,13
                    // A contextual gap is detected, we need to handle it
                    if (!haveRemoved) {
                        if (index - 1 == indexOfPageWillRemove) {
                            // For example, Just remove the 3 page and recalculate the context
                            cache.remove(pageStateWillRemove.page)
                            recalculateContext(pageStateWillRemove.page)
                        } else {
                            // Remove page (2) by collapse method and recalculate the context
                            collapse(previousPageState.page, index - 1 - indexOfPageWillRemove)
                            recalculateContext(previousPageState.page)
                        }
                        if (index == states.lastIndex) {
                            // pages example: 1,2 gap 11
                            // We need to delete the 11 page because we removed 3
                            // And we need to recalculate the context
                            cache.remove(currentState.page)
                            recalculateContext(currentState.page)
                        }
                        haveRemoved = true
                    } else {
                        // The pageStateWillRemove have already removed
                        // And we need to collapse others page contexts
                        collapse(previousPageState.page, index - 1 - indexOfStartContext)
                        recalculateContext(previousPageState.page)
                    }
                    indexOfStartContext = index
                } else if (index == states.lastIndex) {
                    // pages example: 1,2,3 gap 11,12,13
                    // The final page context is founded, and we need to handle it
                    if (!haveRemoved) {
                        if (index == indexOfPageWillRemove) {
                            // For example, Just remove the 13 page and recalculate the context
                            cache.remove(pageStateWillRemove.page)
                            recalculateContext(pageStateWillRemove.page)
                        } else {
                            // Remove page (12) by collapse method and recalculate the context
                            collapse(currentState.page, index - indexOfPageWillRemove)
                            recalculateContext(currentState.page)
                        }
                        haveRemoved = true
                    } else {
                        // The pageStateWillRemove have already removed
                        // And we need to collapse the final page context
                        collapse(currentState.page, index - indexOfStartContext)
                        recalculateContext(currentState.page)
                    }
                }
                previousPageState = currentState
                return@smartForEach true
            }
        }
        if (!silently && pageStateWillRemove != null) {
            snapshot()
        }
        return pageStateWillRemove
    }

    /**
     * Retrieves the state of a page from the cache.
     *
     * @param page The page number to retrieve the state.
     * @return The state of the page, or null if not found.
     */
    fun getPageState(page: UInt): PageState<T>? {
        return cache[page]
    }

    /**
     * Sets the state of a page and updates the cache.
     *
     * @param state The new state of the page.
     * @param silently If true, the change will not trigger snapshot update.
     */
    fun setPageState(
        state: PageState<T>,
        silently: Boolean = false
    ) {
        cache[state.page] = state
        if (!silently) {
            snapshot()
        }
    }

    fun removeElement(predicate: (T) -> Boolean): T? {
        for (k in cache.keys.toList()) {
            val v = cache.getValue(k)
            for ((i, e) in v.data.withIndex()) {
                if (predicate(e)) {
                    return removeElement(page = k, index = i)
                }
            }
        }
        return null
    }

    fun removeElement(page: UInt, predicate: (T) -> Boolean): T? {
        val v = cache.getValue(page)
        for ((i, e) in v.data.withIndex()) {
            if (predicate(e)) {
                return removeElement(page = page, index = i)
            }
        }
        return null
    }

    /**
     * Removes an element at a specific index within a page.
     *
     * @param page The page number where the element should be removed.
     * @param index The index within the page where the element should be removed.
     * @param silently If true, the change will not trigger snapshot update.
     * @return The removed element.
     * @throws NoSuchElementException if the page is not found in the cache.
     */
    fun removeElement(
        page: UInt,
        index: Int,
        silently: Boolean = false,
    ): T {
        val pageState = cache.getValue(page)
        val removed: T

        val updatedData = pageState.data
            .let { it as MutableList }
            .also { removed = it.removeAt(index) }

        if (updatedData.size < capacity && !ignoreCapacity) {
            val nextPageState = cache[page + 1u]
            if (nextPageState != null
                &&
                nextPageState::class == pageState::class
            ) {
                while (updatedData.size < capacity
                    &&
                    nextPageState.data.isNotEmpty()
                ) {
                    updatedData.add(
                        removeElement(
                            page = page + 1u,
                            index = 0,
                            silently = true
                        )
                    )
                }
            }
        }

        if (updatedData.isEmpty()) {
            removePageState(
                pageToRemove = page,
                silently = true
            )
        } else {
            setPageState(
                state = pageState.copy(data = updatedData),
                silently = true
            )
        }

        if (!silently) {
            val pageBefore = walkBackwardWhile(cache[startContextPage])!!
            val pageAfter = walkForwardWhile(cache[endContextPage])!!
            val rangeSnapshot = pageBefore.page..pageAfter.page
            if (page in rangeSnapshot) {
                snapshot(rangeSnapshot)
            }
        }

        return removed
    }

    fun addElement(
        element: T,
        silently: Boolean = false,
        initSuccessPageState: ((page: UInt, data: List<T>) -> PageState<T>)? = null
    ): Boolean {
        val lastPage: UInt = cache.keys.maxOrNull() ?: return false
        val lastIndex: Int = cache.getValue(lastPage).data.lastIndex
        addElement(element, lastPage, lastIndex, silently, initSuccessPageState)
        return true
    }

    fun addElement(
        element: T,
        page: UInt,
        index: Int,
        silently: Boolean = false,
        initPageState: ((page: UInt, data: List<T>) -> PageState<T>)? = null
    ) {
        return addAllElements(
            elements = listOf(element),
            targetPage = page,
            index = index,
            silently = silently,
            initPageState = initPageState
        )
    }

    /**
     * Adds a list of elements at a specific index within a page.
     *
     * @param elements The elements to add.
     * @param targetPage The page number where the elements should be added.
     * @param index The index within the page where the elements should be added.
     * @param silently If true, the change will not trigger snapshot update.
     * @param initPageState An optional function to initialize a page state if it doesn't exist.
     * @throws IndexOutOfBoundsException if the page is not found in the cache and initPageState is not provided.
     */
    fun addAllElements(
        elements: List<T>,
        targetPage: UInt,
        index: Int,
        silently: Boolean = false,
        initPageState: ((page: UInt, data: List<T>) -> PageState<T>)? = null
    ) {
        val targetState: PageState<T> =
            (getPageState(targetPage) ?: initPageState?.invoke(targetPage, mutableListOf()))
                ?: throw IndexOutOfBoundsException(
                    "page-$targetPage was not created"
                )

        val dataOfTargetState: MutableList<T> = checkNotNull(
            targetState.data as? MutableList
        ) { "data of target page state is not mutable" }
        dataOfTargetState.addAll(index, elements)
        val extraElements: MutableList<T>? =
            if (dataOfTargetState.size > capacity && !ignoreCapacity) {
                MutableList(size = dataOfTargetState.size - capacity) {
                    dataOfTargetState.removeAt(dataOfTargetState.lastIndex)
                }.apply(MutableList<T>::reverse)
            } else {
                null
            }

        if (!extraElements.isNullOrEmpty()) {
            val nextPageState: PageState<T>? = getPageState(targetPage + 1u)
            if ((nextPageState != null && nextPageState::class == targetState::class)
                ||
                (nextPageState == null && initPageState != null)
            ) {
                addAllElements(
                    elements = extraElements,
                    targetPage = targetPage + 1u,
                    index = 0,
                    silently = true,
                    initPageState = initPageState
                )
            } else {
                val rangePageInvalidated: UIntRange = (targetPage + 1u)..cache.keys.last()
                rangePageInvalidated.forEach(cache::remove)
            }
        }

        if (!silently) {
            val startState: PageState<T> = checkNotNull(
                walkBackwardWhile(getPageState(startContextPage))
            ) { "startContextPage is broken so snapshot is imposable" }
            val endState: PageState<T> = checkNotNull(
                walkForwardWhile(getPageState(endContextPage))
            ) { "endContextPage is broken so snapshot is imposable" }
            val rangeSnapshot: UIntRange = startState.page..endState.page
            if (targetPage in rangeSnapshot) {
                snapshot(rangeSnapshot)
            }
        }
    }

    inline fun getElement(
        predicate: (T) -> Boolean
    ): T? {
        this.smartForEach { _, _, pageState: PageState<T> ->
            for (element in pageState.data) {
                if (predicate(element)) {
                    return element
                }
            }
            return@smartForEach true
        }
        return null
    }

    /**
     * Retrieves an element at a specific index within a page.
     *
     * @param page The page number where the element is located.
     * @param index The index within the page where the element is located.
     * @return The element at the specified page and index, or null if not found.
     */
    fun getElement(
        page: UInt,
        index: Int,
    ): T? {
        return getPageState(page)
            ?.data?.get(index)
    }

    /**
     * Replaces all elements within the paginator based on a provider and predicate.
     *
     * @param providerElement A function that provides a new element based on the current element, pageState, and index.
     * @param silently If true, the change will not trigger snapshot update.
     * @param predicate A function that determines whether an element should be replaced.
     */
    inline fun replaceAllElement(
        providerElement: (current: T, pageState: PageState<T>, index: Int) -> T?,
        silently: Boolean = false,
        predicate: (current: T, pageState: PageState<T>, index: Int) -> Boolean
    ) {
        smartForEach { _, _, pageState ->
            var index = 0
            while (index < pageState.data.size) {
                val current = pageState.data[index]
                if (predicate(current, pageState, index)) {
                    val newElement = providerElement(current, pageState, index)
                    if (newElement != null) {
                        setElement(
                            element = newElement,
                            page = pageState.page,
                            index = index,
                            silently = true
                        )
                    } else {
                        removeElement(
                            page = pageState.page,
                            index = index,
                            silently = true
                        )
                    }
                }
                ++index
            }
            return@smartForEach true
        }
        if (!silently) {
            snapshot()
        }
    }

    inline fun setElement(
        element: T,
        silently: Boolean = false,
        predicate: (T) -> Boolean
    ) {
        this.smartForEach { _, _, pageState ->
            var index = 0
            while (index < pageState.data.size) {
                if (predicate(pageState.data[index++])) {
                    setElement(
                        element = element,
                        page = pageState.page,
                        index = index,
                        silently = silently
                    )
                    return
                }
            }
            return@smartForEach true
        }
    }

    /**
     * Sets an element at a specific index within a page.
     *
     * @param element The element to set.
     * @param page The page number where the element should be set.
     * @param index The index within the page where the element should be set.
     * @param silently If true, the change will not trigger snapshot update.
     * @throws NoSuchElementException if the page is not found in the cache.
     */
    fun setElement(
        element: T,
        page: UInt,
        index: Int,
        silently: Boolean = false
    ) {
        val pageState = cache.getValue(page)
        setPageState(
            state = pageState.copy(
                data = pageState.data
                    .let { it as MutableList }
                    .also { it[index] = element }
            ),
            silently = true
        )

        if (!silently) {
            val pageBefore = walkBackwardWhile(cache[startContextPage])!!
            val pageAfter = walkForwardWhile(cache[endContextPage])!!
            val rangeSnapshot = pageBefore.page..pageAfter.page
            if (page in rangeSnapshot) {
                snapshot(rangeSnapshot)
            }
        }
    }

    /**
     * Checks if the given PageState is a valid success state.
     *
     * @param pageState The PageState to check.
     * @return True if the PageState is a SuccessPage with a size equal to capacity, false otherwise.
     *         Returns false if the PageState is an EmptyPage.
     */
    @OptIn(ExperimentalContracts::class)
    @Suppress("NOTHING_TO_INLINE")
    inline fun isValidSuccessState(pageState: PageState<T>?): Boolean {
        contract {
            returns(true) implies (pageState is SuccessPage<T>)
        }
        if (pageState is EmptyPage) return false
        if (pageState !is SuccessPage) return false
        if (ignoreCapacity) return true
        return pageState.data.size == capacity
    }

    /**
     * Updates the snapshot of the paginator within the given range.
     *
     * @param pageRange The range of pages to include in the snapshot. Defaults to the range from startContextPage to endContextPage.
     * @throws IllegalStateException if startContextPage or endContextPage is zero, or if min or max values are null.
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
     * Scans and returns a list of PageState within the given range.
     *
     * @param pagesRange The range of pages to scan. Defaults to the range from startContextPage to endContextPage.
     * @return A list of PageState within the specified range.
     * @throws IllegalStateException if startContextPage or endContextPage is zero, or if min or max values are null.
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
                val pageState: PageState<T> = cache[page] ?: break
                this.add(pageState)
            }
        }
    }

    /**
     * Walks forward from the given [pivotState] through consecutive pages
     * that satisfy the [predicate], and returns the last page in that chain.
     *
     * This function:
     * - Starts at [pivotState].
     * - Moves to the next page (`page + 1`) as long as the page exists in the cache
     *   and satisfies the [predicate].
     * - Stops at the last consecutive page that satisfies the predicate.
     *
     * @param pivotState The initial page from which forward traversal begins.
     * If null or does not satisfy [predicate], the function returns null.
     *
     * @param predicate A condition that each traversed page must satisfy.
     * Defaults to always true, allowing traversal through all consecutive next pages.
     *
     * @return The last PageState encountered while moving forward that still satisfies [predicate],
     * or null if the starting page is null or fails the predicate.
     */
    inline fun walkForwardWhile(
        pivotState: PageState<T>?,
        predicate: (PageState<T>) -> Boolean = { true }
    ): PageState<T>? {
        return walkWhile(
            pivotState = pivotState,
            next = { currentPage: UInt ->
                return@walkWhile currentPage + 1u
            },
            predicate = { state: PageState<T> ->
                return@walkWhile predicate.invoke(state)
            }
        )
    }

    /**
     * Walks backward from the given [pivotState], following consecutive previous pages,
     * and returns the first page in that backward chain that does *not* satisfy the [predicate].
     *
     * In other words, this function:
     * - Starts at [pivotState].
     * - Moves to the previous page (`page - 1`) as long as:
     *   - The page exists in the cache, and
     *   - The page satisfies the [predicate].
     * - Stops at the last page that satisfied the predicate.
     *
     * This effectively finds the earliest consecutive page before [pivotState]
     * that still matches [predicate].
     *
     * @param pivotState The initial page from which backward traversal begins.
     * If null or does not satisfy [predicate], the function returns null.
     *
     * @param predicate A condition that each traversed page must satisfy.
     * Defaults to always true, allowing traversal through all consecutive previous pages.
     *
     * @return The last PageState encountered while moving backward that still satisfies [predicate],
     * or null if the starting page is null or fails the predicate.
     */
    inline fun walkBackwardWhile(
        pivotState: PageState<T>?,
        predicate: (PageState<T>) -> Boolean = { true }
    ): PageState<T>? {
        return walkWhile(
            pivotState = pivotState,
            next = { currentPage: UInt ->
                return@walkWhile currentPage - 1u
            },
            predicate = { state: PageState<T> ->
                return@walkWhile predicate.invoke(state)
            }
        )
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
            val state: PageState<T>? = getPageState(nextPage)
            if (state != null && predicate.invoke(state)) {
                resultState = state
            } else {
                return resultState
            }
        }
    }

    /**
     * Adjusts the capacity of the paginator and optionally resizes the cached pages.
     *
     * @param capacity The new capacity for each page. Must be greater or equal to zero.
     * @param resize If true, the pages will be resized according to the new capacity.
     * @param silently If true, the function will not trigger a snapshot update.
     * @param initSuccessState An optional initializer for success page state.
     *
     * @throws IllegalArgumentException if the capacity is less than zero.
     */
    fun resize(
        capacity: Int,
        resize: Boolean = true,
        silently: Boolean = false,
        initSuccessState: InitializerSuccessPage<T> = initializerSuccessPage
    ) {
        if (this.capacity == capacity) return
        check(capacity >= 0) { "capacity must be greater or equal than zero" }
        this.capacity = capacity

        if (resize && capacity > 0) {
            val firstSuccessPageState: PageState<T>? =
                walkForwardWhile(
                    pivotState = cache[startContextPage],
                    predicate = { pageState: PageState<T> ->
                        pageState.isSuccessState()
                    }
                )
            val lastSuccessPageState: PageState<T>? =
                walkBackwardWhile(
                    pivotState = cache[endContextPage],
                    predicate = { pageState: PageState<T> ->
                        pageState.isSuccessState()
                    }
                )
            firstSuccessPageState!!; lastSuccessPageState!!
            val items: MutableList<T> =
                (firstSuccessPageState.page..lastSuccessPageState.page)
                    .map { page: UInt -> cache.getValue(page) }
                    .flatMap { pageState: PageState<T> -> pageState.data }
                    .toMutableList()

            cache.clear()

            var pageIndex: UInt = firstSuccessPageState.page
            while (items.isNotEmpty()) {
                val successData = mutableListOf<T>()
                while (items.isNotEmpty() && successData.size < capacity) {
                    successData.add(items.removeAt(0))
                }

                setPageState(
                    state = initSuccessState.invoke(pageIndex++, successData),
                    silently = true
                )
            }
        }

        if (!silently && capacity > 0) {
            snapshot()
        }
    }

    /**
     * Releases the paginator by clearing the cache, resetting bookmarks, and resizing to the default capacity.
     *
     * @param capacity The new capacity for each page after releasing. Defaults to DEFAULT_CAPACITY.
     * @param silently If true, the function will not trigger a snapshot update.
     */
    fun release(
        capacity: Int = DEFAULT_CAPACITY,
        silently: Boolean = false
    ) {
        cache.clear()
        bookmarks.clear()
        bookmarks.add(BookmarkUInt(page = 1u))
        bookmarkIterator = bookmarks.listIterator()
        startContextPage = 0u
        endContextPage = 0u
        if (!silently) _snapshot.update { true to emptyList() }
        resize(capacity, resize = false, silently = true)
        lockJump = false
        lockGoNextPage = false
        lockGoPreviousPage = false
        lockRestart = false
        lockRefresh = false
    }

    override operator fun compareTo(other: MutablePaginator<*>): Int = this.size - other.size

    operator fun iterator(): MutableIterator<MutableMap.MutableEntry<UInt, PageState<T>>> {
        return cache.iterator()
    }

    operator fun contains(page: UInt): Boolean = getPageState(page) != null

    operator fun contains(pageState: PageState<T>): Boolean = getPageState(pageState.page) != null

    operator fun minusAssign(page: UInt) {
        removePageState(page)
    }

    operator fun minusAssign(pageState: PageState<T>) {
        removePageState(pageState.page)
    }

    operator fun plusAssign(pageState: PageState<T>): Unit = setPageState(pageState)

    operator fun get(page: UInt): PageState<T>? = getPageState(page)

    operator fun get(page: UInt, index: Int): T? = getElement(page, index)

    override fun toString(): String = "Paginator(pages=$cache, bookmarks=$bookmarks)"

    override fun hashCode(): Int = cache.hashCode()

    override fun equals(other: Any?): Boolean =
        (other as? MutablePaginator<*>)?.cache === this.cache

    companion object {
        const val DEFAULT_CAPACITY = 20
        const val IGNORE_CAPACITY = 0
    }
}
