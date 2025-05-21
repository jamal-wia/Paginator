package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.Bookmark
import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkUInt
import com.jamal_aliev.paginator.exception.LockedException.GoNextPageWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.GoPreviousPageWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.JumpWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.RefreshWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.RestartWasLockedException
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PageState.EmptyPage
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import com.jamal_aliev.paginator.page.PageState.ProgressPage
import com.jamal_aliev.paginator.page.PageState.ErrorPage
import com.jamal_aliev.paginator.extension.isProgressState
import com.jamal_aliev.paginator.extension.isSuccessState
import com.jamal_aliev.paginator.extension.far
import com.jamal_aliev.paginator.extension.smartForEach
import com.jamal_aliev.paginator.initializer.InitializerEmptyPage
import com.jamal_aliev.paginator.initializer.InitializerErrorPage
import com.jamal_aliev.paginator.initializer.InitializerProgressPage
import com.jamal_aliev.paginator.initializer.InitializerSuccessPage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
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
        return fastSearchPageBefore(pageState, ::isValidSuccessState)
            ?.also { startContextPage = it.page }
    }

    /**
     * This is a variable that holds the right valid success page expect a jump situation
     * */
    var endContextPage = 0u
        private set

    protected fun expandEndContextPage(pageState: PageState<T>?): PageState<T>? {
        return fastSearchPageAfter(pageState, ::isValidSuccessState)
            ?.also { endContextPage = it.page }
    }

    val isStarted: Boolean get() = startContextPage > 0u && endContextPage > 0u

    fun findNearContextPage(startPoint: UInt = 1u, endPoint: UInt = startPoint) {
        require(startPoint >= 1u) { "startPoint must be greater than zero" }
        require(endPoint >= 1u) { "endPoint must be greater than zero" }
        require(endPoint >= startPoint) { "endPoint must be greater than startPoint" }

        fun find(sPont: UInt, ePoint: UInt) {
            var fromLeftToLeft = sPont
            var fromRightToRight = ePoint
            var fromLeftToRight = sPont
            var fromRightToLeft = ePoint
            val max = cache.lastKey()
            while (fromLeftToRight <= fromRightToLeft ||
                fromLeftToLeft >= 1u || fromRightToRight <= max
            ) {
                if (fromLeftToRight < fromRightToLeft) {
                    if (isValidSuccessState(getPageState(fromLeftToRight))) {
                        startContextPage = fromLeftToRight
                        endContextPage = fromLeftToRight
                        expandEndContextPage(getPageState(fromLeftToRight + 1u))
                        break
                    }
                    if (isValidSuccessState(getPageState(fromRightToLeft))) {
                        startContextPage = fromRightToLeft
                        endContextPage = fromRightToLeft
                        expandStartContextPage(getPageState(fromRightToLeft - 1u))
                        break
                    }
                } else if (fromLeftToRight == fromRightToLeft) {
                    val fromLeftToRightState = getPageState(fromLeftToRight)
                    if (isValidSuccessState(fromLeftToRightState)) {
                        startContextPage = fromLeftToRight
                        endContextPage = fromLeftToRight
                        break
                    }
                }
                if (fromLeftToLeft >= 1u) {
                    if (isValidSuccessState(getPageState(fromLeftToLeft))) {
                        startContextPage = fromLeftToLeft
                        endContextPage = fromLeftToLeft
                        expandStartContextPage(getPageState(fromLeftToLeft - 1u))
                        break
                    }
                }
                if (fromRightToRight <= max) {
                    if (isValidSuccessState(getPageState(fromRightToRight))) {
                        startContextPage = fromRightToRight
                        endContextPage = fromRightToRight
                        expandEndContextPage(getPageState(fromRightToRight + 1u))
                        break
                    }
                }

                if (fromLeftToLeft > 1u) fromLeftToLeft--
                if (fromRightToRight < max) fromRightToRight++
                if (fromLeftToRight < fromRightToLeft) {
                    fromLeftToRight++
                    fromRightToLeft--
                }
            }
        }

        if (size == 0) {
            startContextPage = 0u
            endContextPage = 0u
            return
        }

        if (startPoint != endPoint) return find(startPoint, endPoint)

        val pointState = getPageState(startPoint)
        if (!isValidSuccessState(pointState))
            return find(startPoint - 1u, endPoint + 1u)

        pointState!!
        startContextPage = startPoint
        endContextPage = startPoint
        expandStartContextPage(getPageState(pointState.page - 1u))
        expandEndContextPage(getPageState(pointState.page + 1u))
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
    ): Pair<PageState<T>, Bookmark>? {
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
    ): Pair<PageState<T>, Bookmark>? {
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
    ): Pair<PageState<T>, Bookmark> {
        if (lockJump) throw JumpWasLockedException()

        check(bookmark.page > 0u) { "bookmark.page should be greater than 0" }

        val probablySuccessBookmarkPage = cache[bookmark.page]
        if (isValidSuccessState(probablySuccessBookmarkPage)) {
            expandStartContextPage(probablySuccessBookmarkPage)
            expandEndContextPage(probablySuccessBookmarkPage)
            if (!silentlyResult) snapshot()
            return probablySuccessBookmarkPage!! to bookmark
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

            return resultPageState to bookmark
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
            return@coroutineScope jump(
                bookmark = BookmarkUInt(page = 1u),
                silentlyLoading = silentlyLoading,
                silentlyResult = silentlyResult,
                enableCacheFlow = enableCacheFlow,
                initProgressState = initProgressState,
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState,
            ).first
        }

        var pivotContextPage: UInt = endContextPage
        var pivotContextPageState: PageState<T>? = cache[pivotContextPage]
        val isPivotContextPageValid: Boolean = isValidSuccessState(pivotContextPageState)
        if (isPivotContextPageValid) {
            fastSearchPageAfter(cache[pivotContextPage + 1u]) { isValidSuccessState(it) }
                ?.also {
                    pivotContextPage = it.page
                    pivotContextPageState = it
                    endContextPage = pivotContextPage
                }
        }

        val nextPage: UInt =
            if (isPivotContextPageValid) pivotContextPage + 1u
            else pivotContextPage
        val nextPageState: PageState<T>? =
            if (nextPage == pivotContextPage) pivotContextPageState
            else cache[nextPage]

        if (nextPageState.isProgressState())
            return@coroutineScope nextPageState!!

        loadOrGetPageState(
            page = nextPage,
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
            if (endContextPage == pivotContextPage
                && isValidSuccessState(resultPageState)
            ) {
                endContextPage = nextPage
            }
            if (enableCacheFlow) {
                repeatCacheFlow()
            }
            if (!silentlyResult) {
                snapshot()
            }

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
            fastSearchPageBefore(cache[pivotContextPage - 1u]) { isValidSuccessState(it) }
                ?.also { beforePageState ->
                    pivotContextPage = beforePageState.page
                    pivotContextPageState = beforePageState
                    startContextPage = pivotContextPage
                }
        }

        val previousPage: UInt =
            if (pivotContextPageValid) pivotContextPage - 1u
            else pivotContextPage
        check(previousPage > 0u) { "previousPage is 0. you can't go to 0" }
        val previousPageState: PageState<T>? =
            if (previousPage == pivotContextPage) pivotContextPageState
            else cache[previousPage]

        if (previousPageState.isProgressState())
            return@coroutineScope previousPageState!!

        loadOrGetPageState(
            page = previousPage,
            forceLoading = true,
            loading = { page, pageState ->
                setPageState(
                    state = initProgressState(page, pageState?.data.orEmpty()),
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
        ).also { resultPageState ->
            setPageState(
                state = resultPageState,
                silently = true
            )
            if (startContextPage == pivotContextPage
                && isValidSuccessState(resultPageState)
            ) {
                startContextPage = previousPage
            }
            if (enableCacheFlow) {
                repeatCacheFlow()
            }
            if (!silentlyResult) {
                snapshot()
            }

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
                if (enableCacheFlow) {
                    repeatCacheFlow()
                }
                if (!silentlyLoading) {
                    snapshot(1u..1u)
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
            if (enableCacheFlow) {
                repeatCacheFlow()
            }
            if (!silentlyResult) {
                snapshot(1u..1u)
            }
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
                    setPageState(
                        state = finalPageState,
                        silently = true
                    )
                }
            }
        }.forEach { it.await() }

        if (enableCacheFlow) {
            repeatCacheFlow()
        }
        if (!finalSilently) {
            snapshot()
        }
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
            if (isValidSuccessState(cachedState)) return cachedState!!
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
     * Removes the state of a page and sets its state to empty.
     *
     * @param page The page number to remove the state.
     * @param silently If true, the change will not trigger snapshot update.
     * @return The removed page state, or null if it was not found.
     */
    fun removePageState(
        page: UInt,
        silently: Boolean = false,
    ): PageState<T>? {
        fun collapse(startPage: UInt, compression: Int) {
            var current: PageState<T> = cache.remove(startPage)!!
            for (i in 1..compression) {
                val collapsed = current.copy(page = current.page - 1u)
                current = getPageState(current.page - 1u)!!
                setPageState(state = collapsed, silently = true)
            }

        }

        fun recalculateContext(removedPage: UInt) {
            if (removedPage in startContextPage..endContextPage) {
                if (endContextPage - startContextPage > 0u) endContextPage--
                else if (removedPage == 1u) findNearContextPage()
                else findNearContextPage(removedPage - 1u, removedPage + 1u)
            }
        }

        var pageStateToRemove: PageState<T>? = null
        if (!isStarted) pageStateToRemove = cache.remove(page)
        else {
            pageStateToRemove = getPageState(page) ?: return null
            var prev: PageState<*>? = null
            var pageToRemoveIndex = -1
            var startContextIndex = -1
            var wasRemoved = false
            smartForEach(
                startIndex = { list ->
                    pageToRemoveIndex = list.binarySearch { it.page.compareTo(page) }
                    startContextIndex = pageToRemoveIndex
                    return@smartForEach pageToRemoveIndex
                }
            ) { list, index, current ->
                val previous = prev ?: current
                if (previous far current) {
                    if (!wasRemoved) {
                        if (index - 1 == pageToRemoveIndex) {
                            cache.remove(page)
                            recalculateContext(page)
                        } else {
                            collapse(previous.page, index - 1 - pageToRemoveIndex)
                            recalculateContext(previous.page)
                        }
                        if (index == list.lastIndex) {
                            cache.remove(current.page)
                            recalculateContext(current.page)
                        }
                        wasRemoved = true
                    } else {
                        collapse(previous.page, index - 1 - startContextIndex)
                        recalculateContext(previous.page)
                    }
                    startContextIndex = index
                } else if (index == list.lastIndex) {
                    if (!wasRemoved) {
                        if (index == pageToRemoveIndex) {
                            cache.remove(page)
                            recalculateContext(page)
                        } else {
                            collapse(current.page, index - pageToRemoveIndex)
                            recalculateContext(current.page)
                        }
                        wasRemoved = true
                    } else {
                        collapse(current.page, index - startContextIndex)
                        recalculateContext(current.page)
                    }
                }
                prev = current
                return@smartForEach true
            }
        }
        if (!silently && pageStateToRemove != null) {
            snapshot()
        }
        return pageStateToRemove
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
                && nextPageState::class == pageState::class
            ) {
                while (updatedData.size < capacity
                    && nextPageState.data.isNotEmpty()
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
                page = page,
                silently = true
            )
        } else {
            setPageState(
                state = pageState.copy(data = updatedData),
                silently = true
            )
        }

        if (!silently) {
            val pageBefore = fastSearchPageBefore(cache[startContextPage])!!
            val pageAfter = fastSearchPageAfter(cache[endContextPage])!!
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
            page = page,
            index = index,
            silently = silently,
            initPageState = initPageState
        )
    }

    /**
     * Adds a list of elements at a specific index within a page.
     *
     * @param elements The elements to add.
     * @param page The page number where the elements should be added.
     * @param index The index within the page where the elements should be added.
     * @param silently If true, the change will not trigger snapshot update.
     * @param initPageState An optional function to initialize a page state if it doesn't exist.
     * @throws IndexOutOfBoundsException if the page is not found in the cache and initPageState is not provided.
     */
    fun addAllElements(
        elements: List<T>,
        page: UInt,
        index: Int,
        silently: Boolean = false,
        initPageState: ((page: UInt, data: List<T>) -> PageState<T>)? = null
    ) {
        val pageState: PageState<T> =
            (cache[page] ?: initPageState?.invoke(page, emptyList()))
                ?: throw IndexOutOfBoundsException(
                    "page-$page was not created"
                )

        val updatedData: MutableList<T> =
            pageState.data.let { it as MutableList }
                .also { it.addAll(index, elements) }

        val extraElements: MutableList<T>? =
            if (updatedData.size > capacity && !ignoreCapacity) {
                MutableList(size = updatedData.size - capacity) {
                    updatedData.removeAt(updatedData.lastIndex)
                }.apply(MutableList<T>::reverse)
            } else null

        setPageState(
            state = pageState.copy(data = updatedData),
            silently = true,
        )

        if (!extraElements.isNullOrEmpty()) {
            val nextPageState = cache[page + 1u]
            if ((nextPageState != null && nextPageState::class == pageState::class)
                || (nextPageState == null && initPageState != null)
            ) {
                addAllElements(
                    elements = extraElements,
                    page = page + 1u,
                    index = 0,
                    silently = true,
                    initPageState = initPageState
                )
            } else {
                val rangePageInvalidated: UIntRange = (page + 1u)..cache.keys.last()
                rangePageInvalidated.forEach(cache::remove)
            }
        }

        if (!silently) {
            val pageBefore = fastSearchPageBefore(cache[startContextPage])!!
            val pageAfter = fastSearchPageAfter(cache[endContextPage])!!
            val rangeSnapshot = pageBefore.page..pageAfter.page
            if (page in rangeSnapshot) {
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
            val pageBefore = fastSearchPageBefore(cache[startContextPage])!!
            val pageAfter = fastSearchPageAfter(cache[endContextPage])!!
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
    @Suppress("NOTHING_TO_INLINE")
    inline fun isValidSuccessState(pageState: PageState<T>?): Boolean {
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
        (pageRange ?: kotlin.run {
            if (!isStarted) return@run null
            val min = fastSearchPageBefore(cache[startContextPage])?.page
            val max = fastSearchPageAfter(cache[endContextPage])?.page
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
        pagesRange: UIntRange = kotlin.run {
            check(startContextPage != 0u) { "You cannot scan because startContextPage is 0" }
            check(endContextPage != 0u) { "You cannot scan because endContextPage is 0" }
            val min = fastSearchPageBefore(cache[startContextPage])?.page
            val max = fastSearchPageAfter(cache[endContextPage])?.page
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
     * Searches for the first PageState after the given pivotPage that matches the predicate.
     *
     * @param pivotPage The page to start searching from.
     * @param predicate A function to test each PageState.
     * @return The first PageState after the pivotPage that matches the predicate, or null if none found.
     */
    inline fun fastSearchPageAfter(
        pivotPage: PageState<T>?,
        predicate: (PageState<T>) -> Boolean = { true }
    ): PageState<T>? {
        if (pivotPage == null || !predicate(pivotPage)) return null
        var result: PageState<T> = pivotPage
        while (true) {
            val pageState = getPageState(result.page + 1u)
            if (pageState != null && predicate(pageState)) {
                result = pageState
            } else {
                return result
            }
        }
    }

    /**
     * Searches for the first PageState before the given pivotPage that matches the predicate.
     *
     * @param pivotPage The page to start searching from.
     * @param predicate A function to test each PageState.
     * @return The first PageState before the pivotPage that matches the predicate, or null if none found.
     */
    inline fun fastSearchPageBefore(
        pivotPage: PageState<T>?,
        predicate: (PageState<T>) -> Boolean = { true }
    ): PageState<T>? {
        if (pivotPage == null || !predicate(pivotPage)) return null
        var result: PageState<T> = pivotPage
        while (true) {
            val pageState = getPageState(result.page - 1u)
            if (pageState != null && predicate(pageState)) {
                result = pageState
            } else {
                return result
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
                fastSearchPageAfter(
                    pivotPage = cache[startContextPage],
                    predicate = { pageState: PageState<T> ->
                        pageState.isSuccessState()
                    }
                )
            val lastSuccessPageState: PageState<T>? =
                fastSearchPageBefore(
                    pivotPage = cache[endContextPage],
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
