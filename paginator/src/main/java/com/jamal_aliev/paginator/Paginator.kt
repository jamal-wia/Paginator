package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.Paginator.PageState.Empty
import com.jamal_aliev.paginator.Paginator.PageState.Error
import com.jamal_aliev.paginator.Paginator.PageState.Progress
import com.jamal_aliev.paginator.Paginator.PageState.Success
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max

class Paginator<T>(val source: suspend (page: UInt) -> List<T>) {

    var capacity: Int = 20
        private set

    private var currentPage = 0u
    private val cache = hashMapOf<UInt, PageState<T>>()

    val bookmarks: MutableList<Bookmark> = mutableListOf(BookmarkUInt(page = 1u))
    var recyclingBookmark = false
    private var bookmarkIterator = bookmarks.listIterator()

    private val _snapshot = MutableStateFlow(emptyList<PageState<T>>())
    val snapshot = _snapshot.asStateFlow()

    var initProgressState: ((page: UInt, data: List<T>) -> Progress<T>)? = null
    var initSuccessState: ((page: UInt, data: List<T>) -> Success<T>)? = null
    var initEmptyState: ((page: UInt, data: List<T>) -> Empty<T>)? = null
    var initErrorState: ((exception: Exception, page: UInt, data: List<T>) -> Error<T>)? = null

    fun ProgressState(page: UInt, data: List<T> = emptyList()) =
        Progress(page, data)

    fun SuccessState(page: UInt, data: List<T> = emptyList()) =
        if (data.isEmpty()) EmptyState(page, data) else Success(page, data)

    fun EmptyState(page: UInt, data: List<T> = emptyList()) =
        Empty(page, data)

    fun ErrorState(exception: Exception, page: UInt, data: List<T> = emptyList()) =
        Error(exception, page, data)

    /**
     * This suspend function is used to jump forward to a specific page in the pagination system based on the current bookmark iterator. It updates the current page, loads the state of the new page, and updates the snapshot of the paginator.
     *
     * @param recycling A Boolean that determines whether to recycle the bookmark iterator when it has no next element. Default is the recyclingBookmark property of the Paginator class.
     * @param initProgressState A function that initializes the state of a page in progress. It takes the page number and the data as parameters. Default is the initProgressState function defined in the Paginator class.
     * @param initEmptyState A function that initializes the state of an empty page. It takes the page number and the data as parameters. Default is the initEmptyState function defined in the Paginator class.
     * @param initSuccessState A function that initializes the state of a successful page. It takes the page number and the data as parameters. Default is the initSuccessState function defined in the Paginator class.
     * @param initErrorState A function that initializes the state of an error page. It takes the exception, the page number, and the data as parameters. Default is the initErrorState function defined in the Paginator class.
     *
     * @return The bookmark of the page that was jumped to. If the bookmark iterator has no next element and recycling is false, it returns null.
     *
     * Note: This function uses the cache of the Paginator class, so it can only jump to pages that have been loaded and cached. Also, it updates the snapshot of the paginator, which can trigger UI updates in an Android app. Therefore, it should be called from a coroutine scope that can handle main thread updates.
     */
    suspend fun jumpForward(
        recycling: Boolean = this.recyclingBookmark,
        initProgressState: ((page: UInt, data: List<T>) -> Progress<T>)? = this.initProgressState,
        initEmptyState: ((page: UInt, data: List<T>) -> Empty<T>)? = this.initEmptyState,
        initSuccessState: ((page: UInt, data: List<T>) -> Success<T>)? = this.initSuccessState,
        initErrorState: ((exception: Exception, page: UInt, data: List<T>) -> Error<T>)? = this.initErrorState
    ): Bookmark? {
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
     * This suspend function is used to jump backward to a specific page in the pagination system based on the current bookmark iterator. It updates the current page, loads the state of the new page, and updates the snapshot of the paginator.
     *
     * @param recycling A Boolean that determines whether to recycle the bookmark iterator when it has no previous element. Default is the recyclingBookmark property of the Paginator class.
     * @param initProgressState A function that initializes the state of a page in progress. It takes the page number and the data as parameters. Default is the initProgressState function defined in the Paginator class.
     * @param initEmptyState A function that initializes the state of an empty page. It takes the page number and the data as parameters. Default is the initEmptyState function defined in the Paginator class.
     * @param initSuccessState A function that initializes the state of a successful page. It takes the page number and the data as parameters. Default is the initSuccessState function defined in the Paginator class.
     * @param initErrorState A function that initializes the state of an error page. It takes the exception, the page number, and the data as parameters. Default is the initErrorState function defined in the Paginator class.
     *
     * @return The bookmark of the page that was jumped to. If the bookmark iterator has no previous element and recycling is false, it returns null.
     *
     * Note: This function uses the cache of the Paginator class, so it can only jump to pages that have been loaded and cached. Also, it updates the snapshot of the paginator, which can trigger UI updates in an Android app. Therefore, it should be called from a coroutine scope that can handle main thread updates.
     */
    suspend fun jumpBack(
        recycling: Boolean = this.recyclingBookmark,
        initProgressState: ((page: UInt, data: List<T>) -> Progress<T>)? = this.initProgressState,
        initEmptyState: ((page: UInt, data: List<T>) -> Empty<T>)? = this.initEmptyState,
        initSuccessState: ((page: UInt, data: List<T>) -> Success<T>)? = this.initSuccessState,
        initErrorState: ((exception: Exception, page: UInt, data: List<T>) -> Error<T>)? = this.initErrorState
    ): Bookmark? {
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
     * This suspend function is used to jump to a specific page in the pagination system. It updates the current page, loads the state of the new page, and updates the snapshot of the paginator.
     *
     * @param bookmark The bookmark of the page to jump to. It must have a page number greater than 0.
     * @param initProgressState A function that initializes the state of a page in progress. It takes the page number and the data as parameters. Default is the initProgressState function defined in the Paginator class.
     * @param initEmptyState A function that initializes the state of an empty page. It takes the page number and the data as parameters. Default is the initEmptyState function defined in the Paginator class.
     * @param initSuccessState A function that initializes the state of a successful page. It takes the page number and the data as parameters. Default is the initSuccessState function defined in the Paginator class.
     * @param initErrorState A function that initializes the state of an error page. It takes the exception, the page number, and the data as parameters. Default is the initErrorState function defined in the Paginator class.
     *
     * @return The bookmark of the page that was jumped to.
     *
     * Note: This function uses the cache of the Paginator class, so it can only jump to pages that have been loaded and cached. Also, it updates the snapshot of the paginator, which can trigger UI updates in an Android app. Therefore, it should be called from a coroutine scope that can handle main thread updates.
     */
    suspend fun jump(
        bookmark: Bookmark,
        initProgressState: ((page: UInt, data: List<T>) -> Progress<T>)? = this.initProgressState,
        initEmptyState: ((page: UInt, data: List<T>) -> Empty<T>)? = this.initEmptyState,
        initSuccessState: ((page: UInt, data: List<T>) -> Success<T>)? = this.initSuccessState,
        initErrorState: ((exception: Exception, page: UInt, data: List<T>) -> Error<T>)? = this.initErrorState
    ): Bookmark {
        check(bookmark.page > 0u)
        currentPage = bookmark.page

        if (cache[bookmark.page].isValidSuccessState()) {
            _snapshot.update { scan() }
            return bookmark
        }

        loadPageState(
            page = bookmark.page,
            forceLoading = true,
            loading = { page, pageState ->
                cache[page] = initProgressState?.invoke(
                    page, pageState?.data.orEmpty()
                ) ?: ProgressState(page)
                _snapshot.update { scan() }
            },
            initEmptyState = initEmptyState,
            initSuccessState = initSuccessState,
            initErrorState = initErrorState
        ).also { finalPageState ->
            cache[bookmark.page] = finalPageState
            _snapshot.update { scan() }
        }

        return bookmark
    }

    /**
     * This suspend function is used to navigate to the next page in the pagination system. It updates the current page, loads the state of the new page, and updates the snapshot of the paginator.
     *
     * @param initProgressState A function that initializes the state of a page in progress. It takes the page number and the data as parameters. Default is the initProgressState function defined in the Paginator class.
     * @param initEmptyState A function that initializes the state of an empty page. It takes the page number and the data as parameters. Default is the initEmptyState function defined in the Paginator class.
     * @param initSuccessState A function that initializes the state of a successful page. It takes the page number and the data as parameters. Default is the initSuccessState function defined in the Paginator class.
     * @param initErrorState A function that initializes the state of an error page. It takes the exception, the page number, and the data as parameters. Default is the initErrorState function defined in the Paginator class.
     *
     * @return The page number of the next page. If the next page is already in a progress state, it returns the page number without loading the page state.
     *
     * Note: This function uses the cache of the Paginator class, so it can only navigate to pages that have been loaded and cached. Also, it updates the snapshot of the paginator, which can trigger UI updates in an Android app. Therefore, it should be called from a coroutine scope that can handle main thread updates. The function runs asynchronously for each page, so the order in which the pages are refreshed is not guaranteed.
     */
    suspend fun nextPage(
        initProgressState: ((page: UInt, data: List<T>) -> Progress<T>)? = this.initProgressState,
        initEmptyState: ((page: UInt, data: List<T>) -> Empty<T>)? = this.initEmptyState,
        initSuccessState: ((page: UInt, data: List<T>) -> Success<T>)? = this.initSuccessState,
        initErrorState: ((exception: Exception, page: UInt, data: List<T>) -> Error<T>)? = this.initErrorState
    ): UInt = coroutineScope {
        val nextPage = searchPageAfter(currentPage) { it.isValidSuccessState() } + 1u
        check(nextPage > 0u)
        if (cache[nextPage].isProgressState())
            return@coroutineScope nextPage

        val refreshJob = if (cache[currentPage].isValidSuccessState()) null
        else launch {
            refresh(
                pages = listOf(currentPage),
                loadingSilently = true,
                finalSilently = true,
                initProgressState = initProgressState,
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState
            )
        }

        loadPageState(
            page = nextPage,
            loading = { page, pageState ->
                cache[page] = initProgressState?.invoke(
                    page, pageState?.data.orEmpty()
                ) ?: ProgressState(page)
                _snapshot.update { scan() }
            },
            initEmptyState = initEmptyState,
            initSuccessState = initSuccessState,
            initErrorState = initErrorState
        ).also { finalPageState ->
            if (finalPageState.isSuccessState()) currentPage = nextPage
            cache[nextPage] = finalPageState
            _snapshot.update { scan() }
        }

        refreshJob?.join()
        return@coroutineScope nextPage
    }

    /**
     * This suspend function is used to navigate to the previous page in the pagination system. It updates the current page, loads the state of the new page, and updates the snapshot of the paginator.
     *
     * @param initProgressState A function that initializes the state of a page in progress. It takes the page number and the data as parameters. Default is the initProgressState function defined in the Paginator class.
     * @param initEmptyState A function that initializes the state of an empty page. It takes the page number and the data as parameters. Default is the initEmptyState function defined in the Paginator class.
     * @param initSuccessState A function that initializes the state of a successful page. It takes the page number and the data as parameters. Default is the initSuccessState function defined in the Paginator class.
     * @param initErrorState A function that initializes the state of an error page. It takes the exception, the page number, and the data as parameters. Default is the initErrorState function defined in the Paginator class.
     *
     * @return The page number of the previous page. If the previous page is already in a progress state, it returns the page number without loading the page state.
     *
     * Note: This function uses the cache of the Paginator class, so it can only navigate to pages that have been loaded and cached. Also, it updates the snapshot of the paginator, which can trigger UI updates in an Android app. Therefore, it should be called from a coroutine scope that can handle main thread updates. The function runs asynchronously for each page, so the order in which the pages are refreshed is not guaranteed. If the previous page is already in a progress state, it returns the page number without loading the page state. This can be useful for avoiding unnecessary network requests or database queries. However, it also means that the state of the previous page may not be up-to-date when the function returns. If you need the most up-to-date state of the previous page, you should force a refresh of the page state before calling this function.
     */
    suspend fun previousPage(
        initProgressState: ((page: UInt, data: List<T>) -> Progress<T>)? = this.initProgressState,
        initEmptyState: ((page: UInt, data: List<T>) -> Empty<T>)? = this.initEmptyState,
        initSuccessState: ((page: UInt, data: List<T>) -> Success<T>)? = this.initSuccessState,
        initErrorState: ((exception: Exception, page: UInt, data: List<T>) -> Error<T>)? = this.initErrorState
    ): UInt = coroutineScope {
        val previousPage = searchPageBefore(currentPage) { it.isValidSuccessState() } - 1u
        check(previousPage > 0u)
        if (cache[previousPage].isProgressState())
            return@coroutineScope previousPage

        val refreshJob = if (cache[currentPage].isValidSuccessState()) null
        else launch {
            refresh(
                pages = listOf(currentPage),
                loadingSilently = true,
                finalSilently = true,
                initProgressState = initProgressState,
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState
            )
        }

        loadPageState(
            page = previousPage,
            loading = { page, pageState ->
                cache[page] = initProgressState?.invoke(
                    page, pageState?.data.orEmpty()
                ) ?: ProgressState(page)
                _snapshot.update { scan() }
            },
            initEmptyState = initEmptyState,
            initSuccessState = initSuccessState,
            initErrorState = initErrorState
        ).also { finalPageState ->
            if (finalPageState.isSuccessState()) currentPage = previousPage
            cache[previousPage] = finalPageState
            _snapshot.update { scan() }
        }

        refreshJob?.join()
        return@coroutineScope previousPage
    }

    /**
     * This suspend function is used to refresh a list of pages in the pagination system. It updates the state of each page, loads the new state of each page, and updates the snapshot of the paginator.
     *
     * @param pages A list of page numbers to refresh. Each page number must be a positive integer.
     * @param loadingSilently A Boolean that determines whether to update the snapshot after setting the initial state of each page. If true, the snapshot is not updated. Default is false.
     * @param finalSilently A Boolean that determines whether to update the snapshot after loading the final state of each page. If true, the snapshot is not updated. Default is false.
     * @param initProgressState A function that initializes the state of a page in progress. It takes the page number and the data as parameters. Default is the initProgressState function defined in the Paginator class.
     * @param initEmptyState A function that initializes the state of an empty page. It takes the page number and the data as parameters. Default is the initEmptyState function defined in the Paginator class.
     * @param initSuccessState A function that initializes the state of a successful page. It takes the page number and the data as parameters. Default is the initSuccessState function defined in the Paginator class.
     * @param initErrorState A function that initializes the state of an error page. It takes the exception, the page number, and the data as parameters. Default is the initErrorState function defined in the Paginator class.
     *
     * Note: This function uses the cache of the Paginator class, so it can only refresh pages that have been loaded and cached. Also, it updates the snapshot of the paginator, which can trigger UI updates in an Android app. Therefore, it should be called from a coroutine scope that can handle main thread updates. The function runs asynchronously for each page, so the order in which the pages are refreshed is not guaranteed.
     */
    suspend fun refresh(
        pages: List<UInt>,
        loadingSilently: Boolean = false,
        finalSilently: Boolean = false,
        initProgressState: ((page: UInt, data: List<T>) -> Progress<T>)? = this.initProgressState,
        initEmptyState: ((page: UInt, data: List<T>) -> Empty<T>)? = this.initEmptyState,
        initSuccessState: ((page: UInt, data: List<T>) -> Success<T>)? = this.initSuccessState,
        initErrorState: ((exception: Exception, page: UInt, data: List<T>) -> Error<T>)? = this.initErrorState
    ): Unit = coroutineScope {
        pages.forEach { page ->
            cache[page] = initProgressState?.invoke(
                page, cache[page]?.data.orEmpty()
            ) ?: ProgressState(page)
        }
        if (!loadingSilently) {
            _snapshot.update { scan() }
        }

        pages.map { page ->
            async {
                loadPageState(
                    page = page,
                    forceLoading = true,
                    initEmptyState = initEmptyState,
                    initSuccessState = initSuccessState,
                    initErrorState = initErrorState
                ).also { finalPageState ->
                    cache[page] = finalPageState
                }
            }
        }.forEach { it.await() }

        if (!finalSilently) {
            _snapshot.update { scan() }
        }
    }

    suspend fun refreshAll(
        loadingSilently: Boolean = false,
        finalSilently: Boolean = false,
        initProgressState: ((page: UInt, data: List<T>) -> Progress<T>)? = this.initProgressState,
        initEmptyState: ((page: UInt, data: List<T>) -> Empty<T>)? = this.initEmptyState,
        initSuccessState: ((page: UInt, data: List<T>) -> Success<T>)? = this.initSuccessState,
        initErrorState: ((exception: Exception, page: UInt, data: List<T>) -> Error<T>)? = this.initErrorState
    ) {
        return refresh(
            pages = cache.keys.toList(),
            loadingSilently = loadingSilently,
            finalSilently = finalSilently,
            initProgressState = initProgressState,
            initEmptyState = initEmptyState,
            initSuccessState = initSuccessState,
            initErrorState = initErrorState
        )
    }

    /**
     * This function is responsible for loading the state of a specific page. It can either return the cached state of the page or force a new load.
     *
     * @param page The page number to load. This is a UInt, so it must be a positive integer.
     * @param forceLoading A Boolean that determines whether to force a new load or use the cached state. Default is false.
     * @param loading A callback function that is invoked when the page is loading. It takes the page number and the current page state as parameters. Default is null.
     * @param source A suspend function that fetches the data for a specific page. Default is the source function defined in the Paginator class.
     * @param initEmptyState A function that initializes the state of an empty page. It takes the page number and the data as parameters. Default is the initEmptyState function defined in the Paginator class.
     * @param initSuccessState A function that initializes the state of a successful page. It takes the page number and the data as parameters. Default is the initSuccessState function defined in the Paginator class.
     * @param initErrorState A function that initializes the state of an error page. It takes the exception, the page number, and the data as parameters. Default is the initErrorState function defined in the Paginator class.
     *
     * @return The state of the page. It can be one of the following: Progress, Success, Empty, or Error.
     *
     * @throws Exception If there's an error while fetching the data, an exception is thrown and caught within the function. The initErrorState function is then invoked to return an Error state.
     */
    suspend fun loadPageState(
        page: UInt,
        forceLoading: Boolean = false,
        loading: ((page: UInt, pageState: PageState<T>?) -> Unit)? = null,
        source: suspend (page: UInt) -> List<T> = this.source,
        initEmptyState: ((page: UInt, data: List<T>) -> Empty<T>)? = this.initEmptyState,
        initSuccessState: ((page: UInt, data: List<T>) -> Success<T>)? = this.initSuccessState,
        initErrorState: ((exception: Exception, page: UInt, data: List<T>) -> Error<T>)? = this.initErrorState
    ): PageState<T> {
        return try {
            val cachedState = if (forceLoading) null else cache[page]
            if (cachedState.isValidSuccessState()) return cachedState!!
            loading?.invoke(page, cachedState)
            val data = source.invoke(page)
            if (data.isEmpty()) initEmptyState?.invoke(page, data) ?: EmptyState(page)
            else initSuccessState?.invoke(page, data) ?: SuccessState(page, data)
        } catch (exception: Exception) {
            initErrorState?.invoke(exception, page, emptyList()) ?: ErrorState(exception, page)
        }
    }

    /**
     * This function is used to remove the state of a specific page from the cache of the Paginator class. If the state is successfully removed, it replaces the state with an empty state and updates the snapshot of the paginator.
     *
     * @param page The page number of the state to remove. This is a UInt, so it must be a positive integer.
     * @param silently A Boolean that determines whether to update the snapshot after removing the state. If true, the snapshot is not updated. Default is false.
     * @param initEmptyState A function that initializes an empty state. It takes no parameters and returns a PageState. Default is a function that creates an EmptyState with the given page number.
     *
     * @return The state that was removed. If no state was removed, it returns null.
     *
     * Note: This function uses the cache of the Paginator class, so it can only remove states that have been loaded and cached. Also, it replaces the removed state with an empty state, so the cache will always contain a state for the given page number after calling this function. However, the empty state may not accurately represent the actual state of the page, especially if the page contains data. Therefore, you should refresh the page state after calling this function to ensure that the cache contains the most up-to-date state. If the silently parameter is set to true, the snapshot of the paginator is not updated after removing the state. This can be useful for avoiding unnecessary UI updates in an Android app. However, it also means that the snapshot may not accurately represent the current state of the paginator after calling this function. If you need the most up-to-date snapshot, you should set the silently parameter to false or manually update the snapshot after calling this function.
     */
    fun removePageState(
        page: UInt,
        silently: Boolean = false,
        initEmptyState: (() -> PageState<T>)? = null
    ): PageState<T>? {
        val removed = cache.remove(page)
        if (removed != null) {
            cache[page] = initEmptyState?.invoke()
                ?: EmptyState(page = page)
        }
        if (!silently) _snapshot.update { scan() }
        return removed
    }

    /**
     * This function is used to set the state of a specific page in the cache of the Paginator class. After setting the state, it updates the snapshot of the paginator.
     *
     * @param state The new state to set for the page. This is a PageState object, so it must contain a valid page number and data.
     * @param silently A Boolean that determines whether to update the snapshot after setting the state. If true, the snapshot is not updated. Default is false.
     *
     * Note: This function uses the cache of the Paginator class, so it can only set the state of pages that have been loaded and cached. Also, it updates the snapshot of the paginator, which can trigger UI updates in an Android app. Therefore, it should be called from a main thread that can handle UI updates. If the silently parameter is set to true, the snapshot of the paginator is not updated after setting the state. This can be useful for avoiding unnecessary UI updates in an Android app. However, it also means that the snapshot may not accurately represent the current state of the paginator after calling this function. If you need the most up-to-date snapshot, you should set the silently parameter to false or manually update the snapshot after calling this function.
     */
    fun setPageState(
        state: PageState<T>,
        silently: Boolean = false
    ) {
        cache[state.page] = state
        if (!silently) _snapshot.update { scan() }
    }

    fun removeBookmark(bookmark: Bookmark): Boolean {
        return bookmarks.remove(bookmark)
    }

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
     * This function is used to remove a specific element from the data of a specific page in the cache of the Paginator class. If the removal causes the data of the page to fall below the capacity, it fills the page with elements from the next page. After removing the element, it optionally updates the snapshot of the paginator.
     *
     * @param page The page number of the data to remove the element from. This is a UInt, so it must be a positive integer.
     * @param index The index of the element to remove in the data of the page. This is an Int, so it can be any valid index in the data.
     * @param silently A Boolean that determines whether to update the snapshot after removing the element. If true, the snapshot is not updated. Default is false.
     *
     * @return The element that was removed.
     *
     * @throws NoSuchElementException If the page does not exist in the cache.
     * @throws IndexOutOfBoundsException If the index is not a valid index in the data of the page.
     *
     * Note: This function uses the cache of the Paginator class, so it can only remove elements from pages that have been loaded and cached. Also, it updates the snapshot of the paginator, which can trigger UI updates in an Android app. Therefore, it should be called from a main thread that can handle UI updates. If the silently parameter is set to true, the snapshot of the paginator is not updated after removing the element. This can be useful for avoiding unnecessary UI updates in an Android app. However, it also means that the snapshot may not accurately represent the current state of the paginator after calling this function. If you need the most up-to-date snapshot, you should set the silently parameter to false or manually update the snapshot after calling this function.
     */
    fun removeElement(
        page: UInt,
        index: Int,
        silently: Boolean = false,
    ): T {
        val pageState = cache.getValue(page)
        val removed: T

        val updatedData = pageState.data.toMutableList()
            .also { removed = it.removeAt(index) }

        if (updatedData.size < capacity) {
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

        cache[page] = pageState.copy(data = updatedData)

        if (!silently) {
            val rangeSnapshot = searchPageBefore(currentPage)..searchPageAfter(currentPage)
            if (page in rangeSnapshot) {
                _snapshot.update { scan(rangeSnapshot) }
            }
        }

        return removed
    }

    fun addElement(
        element: T,
        silently: Boolean = false,
        initPageState: (() -> PageState<T>)? = null
    ): Boolean {
        val lastPage = cache.keys.maxOrNull() ?: return false
        val lastIndex = cache.getValue(lastPage).data.lastIndex
        addElement(element, lastPage, lastIndex, silently, initPageState)
        return true
    }

    fun addElement(
        element: T,
        page: UInt,
        index: Int,
        silently: Boolean = false,
        initPageState: (() -> PageState<T>)? = null
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
     * This function is used to add a list of elements to the data of a specific page in the cache of the Paginator class. If the added elements exceed the capacity of the page, they are moved to the next page. After adding the elements, it optionally updates the snapshot of the paginator.
     *
     * @param elements The list of elements to add to the data of the page. This is a List of generic type T, so it can contain any type that is compatible with the data of the page.
     * @param page The page number of the data to add the elements to. This is a UInt, so it must be a positive integer.
     * @param index The index at which to add the elements in the data of the page. This is an Int, so it can be any valid index in the data.
     * @param silently A Boolean that determines whether to update the snapshot after adding the elements. If true, the snapshot is not updated. Default is false.
     * @param initPageState A function that initializes a page state. It takes no parameters and returns a PageState. Default is null, which means the current state of the page in the cache is used.
     *
     * @throws IndexOutOfBoundsException If the page does not exist in the cache and no initPageState function is provided.
     *
     * Note: This function uses the cache of the Paginator class, so it can only add elements to pages that have been loaded and cached or can be initialized with the initPageState function. Also, it updates the snapshot of the paginator, which can trigger UI updates in an Android app. Therefore, it should be called from a main thread that can handle UI updates. If the silently parameter is set to true, the snapshot of the paginator is not updated after adding the elements. This can be useful for avoiding unnecessary UI updates in an Android app. However, it also means that the snapshot may not accurately represent the current state of the paginator after calling this function. If you need the most up-to-date snapshot, you should set the silently parameter to false or manually update the snapshot after calling this function.
     */
    fun addAllElements(
        elements: List<T>,
        page: UInt,
        index: Int,
        silently: Boolean = false,
        initPageState: (() -> PageState<T>)? = null
    ) {
        val pageState = (initPageState?.invoke() ?: cache[page])
            ?: throw IndexOutOfBoundsException(
                "page-$page was not created"
            )

        val updatedData = pageState.data.toMutableList()
            .also { it.addAll(index, elements) }
        val extraElements =
            if (updatedData.size > capacity) {
                val initialCapacity = updatedData.size - capacity
                ArrayList<T>(initialCapacity)
                    .apply {
                        repeat(initialCapacity) { add(updatedData.removeLast()) }
                        reverse()
                    }
            } else null

        cache[page] = pageState.copy(data = updatedData)

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
                currentPage = page
            }
        }

        if (!silently) {
            val rangeSnapshot = searchPageBefore(currentPage)..searchPageAfter(currentPage)
            if (page in rangeSnapshot) {
                _snapshot.update { scan(rangeSnapshot) }
            }
        }
    }


    fun setElement(
        element: T,
        silently: Boolean = false,
        predicate: (T) -> Boolean
    ) {
        for (page in cache.keys.toList()) {
            val pageState = cache.getValue(page)
            for ((index, e) in pageState.data.withIndex()) {
                if (predicate(e)) {
                    setElement(element, page, index, silently)
                }
            }
        }
    }

    /**
     * This function is used to update a specific element in the data of a specific page in the cache of the Paginator class. After updating the element, it optionally updates the snapshot of the paginator.
     *
     * @param element The new element to set in the data of the page. This is a generic type T, so it can be any type that is compatible with the data of the page.
     * @param page The page number of the data to update. This is a UInt, so it must be a positive integer.
     * @param index The index of the element to update in the data of the page. This is an Int, so it can be any valid index in the data.
     * @param silently A Boolean that determines whether to update the snapshot after updating the element. If true, the snapshot is not updated. Default is false.
     *
     * Note: This function uses the cache of the Paginator class, so it can only update elements of pages that have been loaded and cached. Also, it updates the snapshot of the paginator, which can trigger UI updates in an Android app. Therefore, it should be called from a main thread that can handle UI updates. If the silently parameter is set to true, the snapshot of the paginator is not updated after updating the element. This can be useful for avoiding unnecessary UI updates in an Android app. However, it also means that the snapshot may not accurately represent the current state of the paginator after calling this function. If you need the most up-to-date snapshot, you should set the silently parameter to false or manually update the snapshot after calling this function.
     */
    fun setElement(
        element: T,
        page: UInt,
        index: Int,
        silently: Boolean = false
    ) {
        val pageState = cache.getValue(page)
        cache[page] = pageState.copy(
            data = pageState.data.toMutableList()
                .also { it[index] = element }
        )

        if (!silently) {
            val rangeSnapshot = searchPageBefore(currentPage)..searchPageAfter(currentPage)
            if (page in rangeSnapshot) {
                _snapshot.update { scan(rangeSnapshot) }
            }
        }
    }

    fun indexOfFirst(predicate: (T) -> Boolean): Pair<UInt, Int>? {
        for (k in cache.keys.toList()) {
            val v = cache.getValue(k)
            for ((i, e) in v.data.withIndex()) {
                if (predicate(e)) {
                    return k to i
                }
            }
        }
        return null
    }

    fun indexOfFirst(predicate: (T) -> Boolean, page: UInt): Pair<UInt, Int>? {
        val pageState = cache.getValue(page)
        for ((i, e) in pageState.data.withIndex()) {
            if (predicate(e)) {
                return page to i
            }
        }
        return null
    }

    fun indexOfFirst(element: T): Pair<UInt, Int>? {
        return indexOfFirst { it == element }
    }

    fun indexOfLast(predicate: (T) -> Boolean): Pair<UInt, Int>? {
        for (k in cache.keys.toList().reversed()) {
            val v = cache.getValue(k)
            for ((i, e) in v.data.reversed().withIndex()) {
                if (predicate(e)) {
                    return k to i
                }
            }
        }
        return null
    }

    fun indexOfLast(predicate: (T) -> Boolean, page: UInt): Pair<UInt, Int>? {
        val pageState = cache.getValue(page)
        for ((i, e) in pageState.data.reversed().withIndex()) {
            if (predicate(e)) {
                return page to i
            }
        }
        return null
    }

    fun indexOfLast(element: T): Pair<UInt, Int>? {
        return indexOfLast { it == element }
    }

    fun PageState<T>?.isValidSuccessState(): Boolean {
        return (this as? Success)?.data?.size == capacity
    }

    /**
     * This function scans a range of pages and returns their states.
     *
     * @param range The range of pages to scan. This is a UIntRange, so it must be a range of positive integers. The default range is from the first page before the current page to the first page after the current page.
     *
     * @return A list of the states of the pages in the given range. If a page in the range is not in the cache, the function breaks and returns the states of the pages found so far.
     *
     * Note: This function uses the cache of the Paginator class, so it can only find pages that have been loaded and cached. Also, it breaks as soon as it encounters a page that is not in the cache, so it may not return the states of all the pages in the given range. To ensure that all the pages in the range are scanned, make sure to load and cache all the pages in the range before calling this function.
     */
    fun scan(
        range: UIntRange = kotlin.run {
            val min = searchPageBefore(currentPage)
            val max = searchPageAfter(currentPage)
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
     * This function searches for a page after the given page that satisfies a certain condition.
     *
     * @param page The page number from which the search begins. This is a UInt, so it must be a positive integer.
     * @param predicate A function that takes a PageState and returns a Boolean. It defines the condition that a page must satisfy. The default condition is true, which means all pages are considered valid.
     *
     * @return The page number of the first page after the given page that satisfies the condition. If no such page is found, it returns the given page number.
     *
     * Note: This function assumes that the pages are numbered in ascending order starting from 1. Therefore, it will not search for a page number less than 1. Also, it uses the cache of the Paginator class, so it can only find pages that have been loaded and cached.
     */
    fun searchPageAfter(
        page: UInt,
        predicate: (PageState<T>) -> Boolean = { true }
    ): UInt {
        var max = page
        while (true) {
            val pageState = cache[max + 1u]
            if (pageState != null && predicate(pageState)) max++
            else break
        }
        return max
    }

    /**
     * This function searches for a page before the given page that satisfies a certain condition.
     *
     * @param page The page number from which the search begins. This is a UInt, so it must be a positive integer.
     * @param predicate A function that takes a PageState and returns a Boolean. It defines the condition that a page must satisfy. The default condition is true, which means all pages are considered valid.
     *
     * @return The page number of the first page before the given page that satisfies the condition. If no such page is found, it returns the given page number.
     *
     * Note: This function assumes that the pages are numbered in ascending order starting from 1. Therefore, it will not search for a page number less than 1. Also, it uses the cache of the Paginator class, so it can only find pages that have been loaded and cached.
     */
    fun searchPageBefore(
        page: UInt,
        predicate: (PageState<T>) -> Boolean = { true }
    ): UInt {
        var min = page
        while (true) {
            val pageState = cache[min - 1u]
            if (pageState != null && predicate(pageState)) min--
            else break
        }
        return min
    }

    /**
     * This function is used to resize the capacity of the Paginator class. It updates the capacity, optionally resizes the data in the cache to fit the new capacity, and updates the snapshot of the paginator.
     *
     * @param capacity The new capacity. This is an Int, so it must be a positive integer.
     * @param resize A Boolean that determines whether to resize the data in the cache to fit the new capacity. If true, the data is resized. Default is true.
     * @param silently A Boolean that determines whether to update the snapshot after resizing the data. If true, the snapshot is not updated. Default is false.
     * @param initSuccessState A function that initializes a successful state. It takes the page number and the data as parameters. Default is the initSuccessState function defined in the Paginator class.
     *
     * Note: This function uses the cache of the Paginator class, so it can only resize the data of pages that have been loaded and cached. Also, it updates the snapshot of the paginator, which can trigger UI updates in an Android app. Therefore, it should be called from a main thread that can handle UI updates. If the silently parameter is set to true, the snapshot of the paginator is not updated after resizing the data. This can be useful for avoiding unnecessary UI updates in an Android app. However, it also means that the snapshot may not accurately represent the current state of the paginator after calling this function. If you need the most up-to-date snapshot, you should set the silently parameter to false or manually update the snapshot after calling this function.
     */
    fun resize(
        capacity: Int,
        resize: Boolean = true,
        silently: Boolean = false,
        initSuccessState: ((page: UInt, data: List<T>) -> Success<T>)? = this.initSuccessState
    ) {
        if (this.capacity == capacity) return
        check(capacity > 0)
        this.capacity = capacity

        if (resize) {
            val firstSuccessPage = searchPageBefore(currentPage) { it.isSuccessState() }
            val lastSuccessPage = searchPageAfter(currentPage) { it.isSuccessState() }
            val successStatesRange = firstSuccessPage..lastSuccessPage
            val successStates = successStatesRange.map { cache.getValue(it) }
            val items = successStates.flatMap { it.data }.toMutableList()

            cache.clear()

            var pageIndex = firstSuccessPage
            while (items.isNotEmpty()) {
                val successData = mutableListOf<T>()
                while (items.isNotEmpty() && successData.size < capacity) {
                    successData.add(items.removeFirst())
                }

                cache[pageIndex] = initSuccessState?.invoke(pageIndex, successData)
                    ?: SuccessState(pageIndex, successData)
                pageIndex++
            }
        }

        if (!silently) {
            _snapshot.update { scan() }
        }
    }

    /**
     * This function is used to reset the Paginator class. It clears the cache and the bookmarks, resets the bookmark iterator, sets the current page to 0, and updates the snapshot of the paginator to an empty list.
     *
     * Note: This function does not take any parameters and does not return any value. After calling this function, the Paginator class will be in its initial state, as if it was just created. All the data in the cache and the bookmarks will be lost, so you should only call this function when you no longer need the current state of the paginator. Also, this function updates the snapshot of the paginator, which can trigger UI updates in an Android app. Therefore, it should be called from a main thread that can handle UI updates.
     */
    fun release() {
        cache.clear()
        bookmarks.clear()
        bookmarks.add(BookmarkUInt(page = 1u))
        bookmarkIterator = bookmarks.listIterator()
        currentPage = 0u
        _snapshot.update { emptyList() }
    }

    override fun toString() = "Paginator(pages=$cache, bookmarks=$bookmarks)"

    override fun hashCode() = cache.hashCode()

    override fun equals(other: Any?) = (other as? Paginator<*>)?.cache === this.cache

    companion object {
        fun PageState<*>?.isProgressState() = this is Progress<*>
        fun PageState<*>?.isEmptyState() = this is Empty<*>
        fun PageState<*>?.isSuccessState() = this is Success<*>
        fun PageState<*>?.isErrorState() = this is Error<*>
    }

    sealed class PageState<E>(
        open val page: UInt,
        open val data: List<E>
    ) {

        open class Progress<T>(
            override val page: UInt,
            override val data: List<T>,
        ) : PageState<T>(page, data)

        open class Success<T>(
            override val page: UInt,
            override val data: List<T>,
        ) : PageState<T>(page, data)

        open class Empty<T>(
            override val page: UInt,
            override val data: List<T>,
        ) : PageState<T>(page, data)

        open class Error<T>(
            val exception: Exception,
            override val page: UInt,
            override val data: List<T>,
        ) : PageState<T>(page, data)

        fun copy(
            page: UInt = this.page,
            data: List<E> = this.data
        ): PageState<E> = when (this) {
            is Progress -> Progress(page, data)
            is Success -> if (data.isEmpty()) Empty(page, data) else Success(page, data)
            is Empty -> Empty(page, data)
            is Error -> Error(exception, page, data)
        }

        override fun toString() = "${this::class.simpleName}(data=${this.data})"

        override fun hashCode(): Int = this.data.hashCode()

        override fun equals(other: Any?): Boolean {
            if (other !is PageState<*>) return false
            return other::class == this::class
                    && other.data === this.data
        }

    }

    @JvmInline
    value class BookmarkUInt(
        override val page: UInt
    ) : Bookmark

    interface Bookmark {
        val page: UInt
    }
}
