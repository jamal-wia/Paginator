package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.Paginator.Bookmark.BookmarkUInt
import com.jamal_aliev.paginator.Paginator.LockedException.GoNextPageWasLockedException
import com.jamal_aliev.paginator.Paginator.LockedException.GoPreviousPageWasLockedException
import com.jamal_aliev.paginator.Paginator.LockedException.JumpWasLockedException
import com.jamal_aliev.paginator.Paginator.LockedException.RefreshWasLockedException
import com.jamal_aliev.paginator.Paginator.LockedException.RestartWasLockedException
import com.jamal_aliev.paginator.Paginator.PageState
import com.jamal_aliev.paginator.Paginator.PageState.EmptyPage
import com.jamal_aliev.paginator.Paginator.PageState.ErrorPage
import com.jamal_aliev.paginator.Paginator.PageState.ProgressPage
import com.jamal_aliev.paginator.Paginator.PageState.SuccessPage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlin.math.max

private typealias InitializerProgressPage<T> = (page: UInt, data: List<T>) -> ProgressPage<T>
private typealias InitializerSuccessPage<T> = (page: UInt, data: List<T>) -> SuccessPage<T>
private typealias InitializerEmptyPage<T> = (page: UInt, data: List<T>) -> EmptyPage<T>
private typealias InitializerErrorPage<T> = (e: Exception, page: UInt, data: List<T>) -> ErrorPage<T>

open class Paginator<T>(
    var source: suspend Paginator<T>.(page: UInt) -> List<T>
) : Comparable<Paginator<*>> {

    var capacity: Int = DEFAULT_CAPACITY
        private set

    val ignoreCapacity: Boolean
        get() = capacity == IGNORE_CAPACITY

    protected val cache = sortedMapOf<UInt, PageState<T>>()
    val pages get() = cache.keys.toList()
    val pageStates get() = cache.values.toList()
    val size get() = cache.size

    private var openCacheFlow = true
    private var enableCacheFlow = false
    private val _cacheFlow = MutableStateFlow<Map<UInt, PageState<T>>>(cache)
    fun asFlow(): Flow<Map<UInt, PageState<T>>> {
        enableCacheFlow = true
        return _cacheFlow.asStateFlow()
            .filter { openCacheFlow }
    }

    /**
     * repeat emit the cache variable into _cacheFlow
     * */
    suspend fun repeatCacheFlow() {
        openCacheFlow = false
        val plug = emptyMap<UInt, PageState<T>>()
        _cacheFlow.update { plug }
        do {
            delay(timeMillis = 1L)
        } while (_cacheFlow.value != plug)
        openCacheFlow = true
        _cacheFlow.update { cache }
    }

    /**
     * This is variable that holds the left valid success page expect jump situation
     * */
    var startContextPage = 0u
        private set

    /**
     * This is variable that holds the right valid success page expect jump situation
     * */
    var endContextPage = 0u
        private set

    val bookmarks: MutableList<Bookmark> = mutableListOf(BookmarkUInt(page = 1u))
    var recyclingBookmark = false
    private var bookmarkIterator = bookmarks.listIterator()

    private val _snapshot = MutableStateFlow(emptyList<PageState<T>>())
    val snapshot = _snapshot.asStateFlow()

    var initializerProgressPage: InitializerProgressPage<T>? = null
    var initializerSuccessPage: InitializerSuccessPage<T>? = null
    var initializerEmptyPage: InitializerEmptyPage<T>? = null
    var initializerErrorPage: InitializerErrorPage<T>? = null

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
        initProgressState: InitializerProgressPage<T>? = initializerProgressPage,
        initSuccessState: InitializerSuccessPage<T>? = initializerSuccessPage,
        initEmptyState: InitializerEmptyPage<T>? = initializerEmptyPage,
        initErrorState: InitializerErrorPage<T>? = initializerErrorPage
    ): Bookmark? {
        if (lockJump) throw JumpWasLockedException()

        var bookmark = bookmarkIterator
            .takeIf { it.hasNext() }
            ?.next()
        if (bookmark == null && recycling || bookmark != null) {
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
        } else {
            return null
        }
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
        initProgressState: InitializerProgressPage<T>? = initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T>? = initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T>? = initializerSuccessPage,
        initErrorState: InitializerErrorPage<T>? = initializerErrorPage
    ): Bookmark? {
        if (lockJump) throw JumpWasLockedException()

        var bookmark = bookmarkIterator
            .takeIf { it.hasPrevious() }
            ?.previous()
        if (bookmark == null && recycling || bookmark != null) {
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
        } else {
            return null
        }
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
        initProgressState: InitializerProgressPage<T>? = initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T>? = initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T>? = initializerSuccessPage,
        initErrorState: InitializerErrorPage<T>? = initializerErrorPage
    ): Bookmark {
        if (lockJump) throw JumpWasLockedException()

        check(bookmark.page > 0u) { "bookmark.page should be greater than 0" }

        val probablySuccessBookmarkPage = cache[bookmark.page]
        if (isValidSuccessState(probablySuccessBookmarkPage)) {
            fastSearchPageBefore(probablySuccessBookmarkPage) { isValidSuccessState(it) }
                ?.also { startContextPage = it.page }
            fastSearchPageAfter(probablySuccessBookmarkPage) { isValidSuccessState(it) }
                ?.also { endContextPage = it.page }
            resnapshot()
            return bookmark
        }

        startContextPage = bookmark.page
        endContextPage = bookmark.page

        loadOrGetPageState(
            page = bookmark.page,
            forceLoading = true,
            loading = { page, pageState ->
                setPageState(
                    state = ProgressPageFactory(
                        page = page,
                        data = pageState?.data.orEmpty(),
                        initProgressState = initProgressState
                    ),
                    silently = true,
                )
                if (enableCacheFlow) {
                    repeatCacheFlow()
                }
                if (!silentlyLoading) {
                    resnapshot()
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

            fastSearchPageBefore(cache[startContextPage]) { isValidSuccessState(it) }
                ?.also { startContextPage = it.page }
            fastSearchPageAfter(cache[endContextPage]) { isValidSuccessState(it) }
                ?.also { endContextPage = it.page }

            if (enableCacheFlow) {
                repeatCacheFlow()
            }
            if (!silentlyResult) {
                resnapshot()
            }
        }

        return bookmark
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
        initProgressState: InitializerProgressPage<T>? = this.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T>? = this.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T>? = this.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T>? = this.initializerErrorPage
    ): UInt = coroutineScope {
        if (lockGoNextPage) throw GoNextPageWasLockedException()

        var pivotContextPage = endContextPage
        var pivotContextPageState = cache[pivotContextPage]
        val pivotContextPageValid = isValidSuccessState(pivotContextPageState)
        if (pivotContextPageValid) {
            fastSearchPageAfter(cache[pivotContextPage + 1u]) { isValidSuccessState(it) }
                ?.also {
                    pivotContextPage = it.page
                    pivotContextPageState = it
                    endContextPage = pivotContextPage
                }
        }

        val nextPage = if (pivotContextPageValid) pivotContextPage + 1u
        else if (pivotContextPage == 0u) 1u
        else pivotContextPage
        val nextPageState = if (nextPage == pivotContextPage) pivotContextPageState
        else cache[nextPage]

        if (nextPageState.isProgressState())
            return@coroutineScope nextPage

        loadOrGetPageState(
            page = nextPage,
            forceLoading = true,
            loading = { page, pageState ->
                setPageState(
                    state = ProgressPageFactory(
                        page = page,
                        data = pageState?.data.orEmpty(),
                        initProgressState = initProgressState
                    ),
                    silently = true,
                )
                if (enableCacheFlow) {
                    repeatCacheFlow()
                }
                if (!silentlyLoading) {
                    resnapshot()
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
                resnapshot()
            }
        }

        return@coroutineScope nextPage
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
        initProgressState: InitializerProgressPage<T>? = this.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T>? = this.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T>? = this.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T>? = this.initializerErrorPage
    ): UInt = coroutineScope {
        if (lockGoPreviousPage) throw GoPreviousPageWasLockedException()

        var pivotContextPage = startContextPage
        var pivotContextPageState = cache[pivotContextPage]
        val pivotContextPageValid = isValidSuccessState(pivotContextPageState)
        if (pivotContextPageValid) {
            fastSearchPageBefore(cache[pivotContextPage - 1u]) { isValidSuccessState(it) }
                ?.also {
                    pivotContextPage = it.page
                    pivotContextPageState = it
                    startContextPage = pivotContextPage
                }
        }

        val previousPage = if (pivotContextPageValid) pivotContextPage - 1u
        else pivotContextPage
        if (previousPage == 0u) return@coroutineScope previousPage
        val previousPageState = if (previousPage == pivotContextPage) pivotContextPageState
        else cache[previousPage]

        if (previousPageState.isProgressState())
            return@coroutineScope previousPage

        loadOrGetPageState(
            page = previousPage,
            forceLoading = true,
            loading = { page, pageState ->
                setPageState(
                    state = ProgressPageFactory(
                        page = page,
                        data = pageState?.data.orEmpty(),
                        initProgressState = initProgressState
                    ),
                    silently = true
                )
                if (enableCacheFlow) {
                    repeatCacheFlow()
                }
                if (!silentlyLoading) {
                    resnapshot()
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
                resnapshot()
            }
        }

        return@coroutineScope previousPage
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
        initProgressState: InitializerProgressPage<T>? = this.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T>? = this.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T>? = this.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T>? = this.initializerErrorPage
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
                    state = ProgressPageFactory(
                        page = page,
                        data = pageState?.data.orEmpty(),
                        initProgressState = initProgressState
                    ),
                    silently = true
                )
                if (enableCacheFlow) {
                    repeatCacheFlow()
                }
                if (!silentlyLoading) {
                    resnapshot(1u..1u)
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
                resnapshot(1u..1u)
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
        initProgressState: InitializerProgressPage<T>? = this.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T>? = this.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T>? = this.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T>? = this.initializerErrorPage
    ): Unit = coroutineScope {
        if (lockRefresh) throw RefreshWasLockedException()

        pages.forEach { page ->
            setPageState(
                state = ProgressPageFactory(
                    page = page,
                    data = cache[page]?.data.orEmpty(),
                    initProgressState = initProgressState
                ),
                silently = true
            )
        }
        if (enableCacheFlow) {
            repeatCacheFlow()
        }
        if (!loadingSilently) {
            resnapshot()
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
            resnapshot()
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
        initProgressState: InitializerProgressPage<T>? = this.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T>? = this.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T>? = this.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T>? = this.initializerErrorPage
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
        noinline source: suspend Paginator<T>.(page: UInt) -> List<T> = this.source,
        noinline initEmptyState: InitializerEmptyPage<T>? = initializerEmptyPage,
        noinline initSuccessState: InitializerSuccessPage<T>? = initializerSuccessPage,
        noinline initErrorState: InitializerErrorPage<T>? = initializerErrorPage
    ): PageState<T> {
        return try {
            val cachedState = if (forceLoading) null else getPageState(page)
            if (isValidSuccessState(cachedState)) return cachedState!!
            loading.invoke(page, cachedState)
            SuccessPageFactory(
                page = page,
                data = source.invoke(this, page)
                    .toMutableList(),
                initSuccessState = initSuccessState,
                initEmptyState = initEmptyState
            )
        } catch (exception: Exception) {
            ErrorPageFactory(
                e = exception,
                page = page,
                data = emptyList(),
                initErrorState = initErrorState
            )
        }
    }

    /**
     * Removes the state of a page and sets its state to empty.
     *
     * @param page The page number to remove the state.
     * @param emitIntoAsFlow If true, the change will be emitted into the flow.
     * @param silently If true, the change will not trigger snapshot update.
     * @param initEmptyState Initializer for the empty state.
     * @return The removed page state, or null if it was not found.
     */
    fun removePageState(
        page: UInt,
        silently: Boolean = false,
        initEmptyState: InitializerEmptyPage<T>? = initializerEmptyPage
    ): PageState<T>? {
        val removed = cache.remove(page)
        if (removed != null) {
            setPageState(
                state = EmptyPageFactory(
                    page = page,
                    data = emptyList(),
                    initEmptyState = initEmptyState
                ),
                silently = true
            )
        }
        if (!silently) {
            resnapshot()
        }
        return removed
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
            resnapshot()
        }
    }

    /**
     * Removes a bookmark from the paginator.
     *
     * @param bookmark The bookmark to remove.
     * @return True if the bookmark was removed, false otherwise.
     */
    fun removeBookmark(bookmark: Bookmark): Boolean {
        return bookmarks.remove(bookmark)
    }

    /**
     * Adds a bookmark to the paginator.
     *
     * @param bookmark The bookmark to add.
     * @return True if the bookmark was added, false otherwise.
     */
    fun addBookmark(bookmark: Bookmark): Boolean {
        return bookmarks.add(bookmark)
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

        setPageState(
            state = pageState.copy(data = updatedData),
            silently = true
        )

        if (!silently) {
            val pageBefore = fastSearchPageBefore(cache[startContextPage])!!
            val pageAfter = fastSearchPageAfter(cache[endContextPage])!!
            val rangeSnapshot = pageBefore.page..pageAfter.page
            if (page in rangeSnapshot) {
                resnapshot(rangeSnapshot)
            }
        }

        return removed
    }

    fun addElement(
        element: T,
        silently: Boolean = false,
        initSuccessPageState: ((page: UInt, data: List<T>) -> PageState<T>)? = null
    ): Boolean {
        val lastPage = cache.keys.maxOrNull() ?: return false
        val lastIndex = cache.getValue(lastPage).data.lastIndex
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
        val pageState = (cache[page] ?: initPageState?.invoke(page, emptyList()))
            ?: throw IndexOutOfBoundsException(
                "page-$page was not created"
            )

        val updatedData = pageState.data
            .let { it as MutableList }
            .also { it.addAll(index, elements) }

        val extraElements: MutableList<T>? =
            if (updatedData.size > capacity && !ignoreCapacity) {
                MutableList(size = updatedData.size - capacity) {
                    updatedData.removeLast()
                }.also { it.reverse() }
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
                val rangePageInvalidated = (page + 1u)..cache.keys.last()
                for (invalid in rangePageInvalidated) cache.remove(invalid)
            }
        }

        if (!silently) {
            val pageBefore = fastSearchPageBefore(cache[startContextPage])!!
            val pageAfter = fastSearchPageAfter(cache[endContextPage])!!
            val rangeSnapshot = pageBefore.page..pageAfter.page
            if (page in rangeSnapshot) {
                resnapshot(rangeSnapshot)
            }
        }
    }

    inline fun getElement(
        predicate: (T) -> Boolean
    ): T? {
        this.safeForeEach { pageState ->
            for (e in pageState.data) {
                if (predicate(e)) {
                    return e
                }
            }
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
        providerElement: (current: T, pageState: PageState<T>, indext: Int) -> T?,
        silently: Boolean = false,
        predicate: (current: T, pageState: PageState<T>, indext: Int) -> Boolean
    ) {
        safeForeEach { pageState ->
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
        }
        if (!silently) {
            resnapshot()
        }
    }

    inline fun setElement(
        element: T,
        silently: Boolean = false,
        predicate: (T) -> Boolean
    ) {
        this.safeForeEach { pageState ->
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
                resnapshot(rangeSnapshot)
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
     * @param range The range of pages to include in the snapshot. Defaults to the range from startContextPage to endContextPage.
     * @throws IllegalStateException if startContextPage or endContextPage is 0, or if min or max values are null.
     */
    fun resnapshot(
        range: UIntRange = kotlin.run {
            check(startContextPage != 0u) { "You cannot scan because startContextPage is 0" }
            check(endContextPage != 0u) { "You cannot scan because endContextPage is 0" }
            val min = fastSearchPageBefore(cache[startContextPage])?.page
            val max = fastSearchPageAfter(cache[endContextPage])?.page
            checkNotNull(min) { "min is null the data structure is broken!" }
            checkNotNull(max) { "max is null the data structure is broken!" }
            return@run min..max
        }
    ) {
        _snapshot.update {
            scan(range)
        }
    }

    /**
     * Scans and returns a list of PageState within the given range.
     *
     * @param range The range of pages to scan. Defaults to the range from startContextPage to endContextPage.
     * @return A list of PageState within the specified range.
     * @throws IllegalStateException if startContextPage or endContextPage is 0, or if min or max values are null.
     */
    fun scan(
        range: UIntRange = kotlin.run {
            check(startContextPage != 0u) { "You cannot scan because startContextPage is 0" }
            check(endContextPage != 0u) { "You cannot scan because endContextPage is 0" }
            val min = fastSearchPageBefore(cache[startContextPage])?.page
            val max = fastSearchPageAfter(cache[endContextPage])?.page
            checkNotNull(min) { "min is null the data structure is broken!" }
            checkNotNull(max) { "max is null the data structure is broken!" }
            return@run min..max
        }
    ): List<PageState<T>> {
        val capacity = max(range.last - range.first, 1u)
        val result = ArrayList<PageState<T>>(capacity.toInt())
        for (item in range) {
            val page = cache[item] ?: break
            result.add(page)
        }
        return result
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
        initSuccessState: InitializerSuccessPage<T>? = initializerSuccessPage
    ) {
        if (this.capacity == capacity) return
        check(capacity >= 0) { "capacity must be greater or equal than zero" }
        this.capacity = capacity

        if (resize && capacity > 0) {
            val firstSuccessPage =
                fastSearchPageAfter(cache[startContextPage]) { it.isSuccessState() }
            val lastSuccessPage =
                fastSearchPageBefore(cache[endContextPage]) { it.isSuccessState() }
            firstSuccessPage!!; lastSuccessPage!!
            val successStatesRange = firstSuccessPage.page..lastSuccessPage.page
            val successStates = successStatesRange.map { cache.getValue(it) }
            val items = successStates.flatMap { it.data }.toMutableList()

            cache.clear()

            var pageIndex = firstSuccessPage.page
            while (items.isNotEmpty()) {
                val successData = mutableListOf<T>()
                while (items.isNotEmpty() && successData.size < capacity) {
                    successData.add(items.removeFirst())
                }

                setPageState(
                    state = SuccessPageFactory(
                        page = pageIndex++,
                        data = successData,
                        initSuccessState = initSuccessState
                    ),
                    silently = true
                )
            }
        }

        if (!silently && capacity > 0) {
            resnapshot()
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
        if (!silently) _snapshot.update { emptyList() }
        resize(capacity, resize = false, silently = true)
        lockJump = false
        lockGoNextPage = false
        lockGoPreviousPage = false
        lockRestart = false
        lockRefresh = false
    }

    override operator fun compareTo(other: Paginator<*>): Int = this.size - other.size

    operator fun iterator() = cache.iterator()

    operator fun contains(page: UInt) = getPageState(page) != null

    operator fun contains(pageState: PageState<T>) = getPageState(pageState.page) != null

    operator fun minusAssign(page: UInt) {
        removePageState(page)
    }

    operator fun minusAssign(pageState: PageState<T>) {
        removePageState(pageState.page)
    }

    operator fun plusAssign(pageState: PageState<T>) = setPageState(pageState)

    operator fun get(page: UInt): PageState<T>? = getPageState(page)

    operator fun get(page: UInt, index: Int): T? = getElement(page, index)

    override fun toString() = "Paginator(pages=$cache, bookmarks=$bookmarks)"

    override fun hashCode() = cache.hashCode()

    override fun equals(other: Any?) = (other as? Paginator<*>)?.cache === this.cache

    @Suppress("FunctionName", "NOTHING_TO_INLINE")
    inline fun ProgressPageFactory(
        page: UInt,
        data: List<T> = emptyList(),
        noinline initProgressState: InitializerProgressPage<T>? = initializerProgressPage
    ): ProgressPage<T> {
        return initProgressState?.invoke(page, data)
            ?: ProgressPage(page, data)
    }

    @Suppress("FunctionName", "NOTHING_TO_INLINE")
    inline fun SuccessPageFactory(
        page: UInt,
        data: List<T> = emptyList(),
        noinline initSuccessState: InitializerSuccessPage<T>? = initializerSuccessPage,
        noinline initEmptyState: InitializerEmptyPage<T>? = initializerEmptyPage
    ): SuccessPage<T> {
        return if (data.isEmpty()) EmptyPageFactory(page, data, initEmptyState)
        else initSuccessState?.invoke(page, data) ?: SuccessPage(page, data)
    }

    @Suppress("FunctionName", "NOTHING_TO_INLINE")
    inline fun EmptyPageFactory(
        page: UInt,
        data: List<T> = emptyList(),
        noinline initEmptyState: InitializerEmptyPage<T>? = initializerEmptyPage
    ): EmptyPage<T> {
        return initEmptyState?.invoke(page, data)
            ?: EmptyPage(page, data)
    }

    @Suppress("FunctionName", "NOTHING_TO_INLINE")
    inline fun ErrorPageFactory(
        e: Exception,
        page: UInt,
        data: List<T> = emptyList(),
        noinline initErrorState: InitializerErrorPage<T>? = initializerErrorPage
    ): ErrorPage<T> {
        return initErrorState?.invoke(e, page, data)
            ?: ErrorPage(e, page, data)
    }

    sealed class LockedException(
        override val message: String?
    ) : Exception(message) {
        open class JumpWasLockedException :
            LockedException("Jump was locked. Please try set false to field lockJump")

        open class GoNextPageWasLockedException :
            LockedException("NextPage was locked. Please try set false to field lockGoNextPage")

        open class GoPreviousPageWasLockedException :
            LockedException("PreviousPage was locked. Please try set false to field lockGoPreviousPage")

        open class RestartWasLockedException
            : LockedException("Restart was locked. Please try set false to field lockRestart")

        open class RefreshWasLockedException :
            LockedException("Refresh was locked. Please try set false to field lockRefresh")
    }

    sealed class PageState<E>(
        open val page: UInt,
        open val data: List<E>
    ) : Comparable<PageState<*>> {

        open class ProgressPage<T>(
            override val page: UInt,
            override val data: List<T>,
        ) : PageState<T>(page, data) {

            override fun copy(page: UInt, data: List<T>) = ProgressPage(page, data)
        }

        open class SuccessPage<T>(
            override val page: UInt,
            override val data: List<T>,
        ) : PageState<T>(page, data) {

            init {
                checkData()
            }

            @Suppress("NOTHING_TO_INLINE")
            private inline fun checkData() {
                if (this !is EmptyPage) {
                    check(data.isNotEmpty()) { "data must not be empty" }
                }
            }

            /**
             * If you want to override this function you should check the data because it can't be empty
             * */
            override fun copy(page: UInt, data: List<T>): SuccessPage<T> {
                return if (data.isEmpty()) EmptyPage(page, data)
                else SuccessPage(page, data)
            }
        }

        open class EmptyPage<T>(
            override val page: UInt,
            override val data: List<T>,
        ) : SuccessPage<T>(page, data) {
            override fun copy(page: UInt, data: List<T>) = EmptyPage(page, data)
        }

        open class ErrorPage<T>(
            val exception: Exception,
            override val page: UInt,
            override val data: List<T>,
        ) : PageState<T>(page, data) {
            open fun copy(e: Exception, page: UInt, data: List<T>): ErrorPage<T> {
                return ErrorPage(e, page, data)
            }

            override fun copy(page: UInt, data: List<T>): ErrorPage<T> {
                return copy(e = this.exception, page = page, data = data)
            }

            override fun toString(): String {
                return "${this::class.simpleName}(exception=${exception}, data=${this.data})"
            }

            override fun hashCode(): Int = this.page.hashCode()

            override fun equals(other: Any?): Boolean {
                if (other !is ErrorPage<*>) return false
                return this.page == other.page
                        && other::class == this::class
                        && other.data === this.data
                        && other.exception === this.exception
            }
        }

        abstract fun copy(page: UInt = this.page, data: List<E> = this.data): PageState<E>

        override fun toString() = "${this::class.simpleName}(data=${this.data})"

        override fun hashCode(): Int = this.page.hashCode()

        override fun equals(other: Any?): Boolean {
            if (other !is PageState<*>) return false
            return this.page == other.page
                    && other::class == this::class
                    && other.data === this.data
        }

        override operator fun compareTo(other: PageState<*>): Int {
            return this.page.compareTo(other.page)
        }
    }

    interface Bookmark {
        val page: UInt

        @JvmInline
        value class BookmarkUInt(
            override val page: UInt
        ) : Bookmark
    }

    companion object {
        const val DEFAULT_CAPACITY = 20
        const val IGNORE_CAPACITY = 0
    }
}

/**
 * Checks if the PageState is in progress state.
 *
 * @return True if the PageState is ProgressPage, false otherwise.
 */
@Suppress("NOTHING_TO_INLINE")
inline fun PageState<*>?.isProgressState() = this is ProgressPage<*>

/**
 * Checks if the PageState is a real progress state.
 *
 * @return True if the PageState is ProgressPage of type T, false otherwise.
 */
inline fun <reified T> PageState<T>.isRealProgressState() =
    this is ProgressPage<T> && this::class == T::class

/**
 * Checks if the PageState is in empty state.
 *
 * @return True if the PageState is EmptyPage, false otherwise.
 */
@Suppress("NOTHING_TO_INLINE")
inline fun PageState<*>?.isEmptyState() = this is EmptyPage<*>

/**
 * Checks if the PageState is a real empty state.
 *
 * @return True if the PageState is EmptyPage of type T, false otherwise.
 */
inline fun <reified T> PageState<T>.isRealEmptyState() =
    this is EmptyPage<T> && this::class == T::class

/**
 * Checks if the PageState is in success state.
 *
 * @return True if the PageState is SuccessPage and not EmptyPage, false otherwise.
 */
@Suppress("NOTHING_TO_INLINE")
inline fun PageState<*>?.isSuccessState() =
    this is SuccessPage<*> && this !is EmptyPage<*>

/**
 * Checks if the PageState is a real success state.
 *
 * @return True if the PageState is SuccessPage of type T, false otherwise.
 */
inline fun <reified T> PageState<T>.isRealSuccessState() =
    this.isSuccessState() && this::class == T::class

/**
 * Checks if the PageState is in error state.
 *
 * @return True if the PageState is ErrorPage, false otherwise.
 */
@Suppress("NOTHING_TO_INLINE")
inline fun PageState<*>?.isErrorState() = this is ErrorPage<*>

/**
 * Checks if the PageState is a real error state.
 *
 * @return True if the PageState is ErrorPage of type T, false otherwise.
 */
inline fun <reified T> PageState<T>.isRealErrorState() =
    this is ErrorPage<T> && this::class == T::class

/**
 * Iterates through each PageState in the paginator and performs the given action on it.
 *
 * @param action The action to be performed on each PageState.
 */
inline fun <T> Paginator<T>.foreEach(
    action: (PageState<T>) -> Unit
) {
    for (state in this) {
        action(state.value)
    }
}

/**
 * Iterates through each PageState in the paginator safely and performs the given action on it.
 *
 * @param action The action to be performed on each PageState.
 */
inline fun <T> Paginator<T>.safeForeEach(
    action: (PageState<T>) -> Unit
) {
    var i = 0
    val data = this.pageStates
    while (i < data.size) {
        action(data[i++])
    }
}

/**
 * Finds the index of the first element matching the given predicate in the paginator.
 *
 * @param predicate The predicate to match elements.
 * @return A pair containing the page number and index of the first matching element, or null if none found.
 */
inline fun <T> Paginator<T>.indexOfFirst(
    predicate: (T) -> Boolean
): Pair<UInt, Int>? {
    for (page in pageStates) {
        val result = page.data.indexOfFirst(predicate)
        if (result != -1) return page.page to result
    }
    return null
}

/**
 * Finds the index of the first element matching the given predicate in the specified page.
 *
 * @param page The page number to search in.
 * @param predicate The predicate to match elements.
 * @return A pair containing the page number and index of the first matching element, or null if none found.
 * @throws IllegalArgumentException if the page is not found.
 */
inline fun <T> Paginator<T>.indexOfFirst(
    page: UInt,
    predicate: (T) -> Boolean
): Pair<UInt, Int>? {
    val pageState = checkNotNull(getPageState(page)) { "Page $page is not found" }
    for ((i, e) in pageState.data.withIndex()) {
        if (predicate(e)) {
            return page to i
        }
    }
    return null
}

/**
 * Finds the index of the last element matching the given predicate in the paginator.
 *
 * @param predicate The predicate to match elements.
 * @return A pair containing the page number and index of the last matching element, or null if none found.
 */
inline fun <T> Paginator<T>.indexOfLast(
    predicate: (T) -> Boolean
): Pair<UInt, Int>? {
    for (page in pageStates.reversed()) {
        val result = page.data.indexOfLast(predicate)
        if (result != -1) return page.page to result
    }
    return null
}

/**
 * Finds the index of the last element matching the given predicate in the specified page.
 *
 * @param page The page number to search in.
 * @param predicate The predicate to match elements.
 * @return A pair containing the page number and index of the last matching element, or null if none found.
 * @throws IllegalArgumentException if the page is not found.
 */
inline fun <T> Paginator<T>.indexOfLast(
    page: UInt,
    predicate: (T) -> Boolean
): Pair<UInt, Int>? {
    val pageState = checkNotNull(getPageState(page)) { "Page $page is not found" }
    for ((i, e) in pageState.data.reversed().withIndex()) {
        if (predicate(e)) {
            return page to i
        }
    }
    return null
}