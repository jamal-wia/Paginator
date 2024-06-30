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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val asFlow = MutableStateFlow<Map<UInt, PageState<T>>>(cache)
    fun asFlow() = asFlow.asStateFlow()

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

    suspend fun jumpForward(
        recycling: Boolean = recyclingBookmark,
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
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
                initProgressState = initProgressState,
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState,
            )
        } else {
            return null
        }
    }

    suspend fun jumpBack(
        recycling: Boolean = recyclingBookmark,

        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
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

    suspend fun jump(
        bookmark: Bookmark,
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
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
            _snapshot.update { scan() }
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
                if (!silentlyLoading) {
                    _snapshot.update { scan() }
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

            if (!silentlyResult) {
                _snapshot.update { scan() }
            }
        }

        return bookmark
    }

    var lockGoNextPage: Boolean = false

    suspend fun goNextPage(
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
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
                if (!silentlyLoading) {
                    _snapshot.update { scan() }
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
            if (!silentlyResult) {
                _snapshot.update { scan() }
            }
        }

        return@coroutineScope nextPage
    }

    var lockGoPreviousPage: Boolean = false

    suspend fun goPreviousPage(
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
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
                if (!silentlyLoading) {
                    _snapshot.update { scan() }
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
            if (!silentlyResult) {
                _snapshot.update { scan() }
            }
        }

        return@coroutineScope previousPage
    }

    var lockRestart: Boolean = false

    suspend fun restart(
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
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
                if (!silentlyLoading) {
                    _snapshot.update {
                        scan(range = 1u..1u)
                    }
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
            if (!silentlyResult) {
                _snapshot.update {
                    scan(range = 1u..1u)
                }
            }
        }
    }

    var lockRefresh: Boolean = false

    suspend fun refresh(
        pages: List<UInt>,
        loadingSilently: Boolean = false,
        finalSilently: Boolean = false,
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
        if (!loadingSilently) {
            _snapshot.update { scan() }
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

        if (!finalSilently) {
            _snapshot.update { scan() }
        }
    }

    suspend fun refreshAll(
        loadingSilently: Boolean = false,
        finalSilently: Boolean = false,
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
            initProgressState = initProgressState,
            initEmptyState = initEmptyState,
            initSuccessState = initSuccessState,
            initErrorState = initErrorState
        )
    }

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
                data = source.invoke(this, page),
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

    fun removePageState(
        page: UInt,
        emitIntoAsFlow: Boolean = true,
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
                emitIntoAsFlow = false,
                silently = true
            )
        }
        if (emitIntoAsFlow) {
            asFlow.tryEmit(cache)
        }
        if (!silently) {
            _snapshot.update { scan() }
        }
        return removed
    }

    fun getPageState(page: UInt): PageState<T>? {
        return cache[page]
    }

    fun setPageState(
        state: PageState<T>,
        emitIntoAsFlow: Boolean = true,
        silently: Boolean = false
    ) {
        cache[state.page] = state
        if (emitIntoAsFlow) {
            asFlow.tryEmit(cache)
        }
        if (!silently) {
            _snapshot.update { scan() }
        }
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

    fun removeElement(
        page: UInt,
        index: Int,
        emitIntoAsFlow: Boolean = true,
        silently: Boolean = false,
    ): T {
        val pageState = cache.getValue(page)
        val removed: T

        val updatedData = pageState.data.toMutableList()
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
                            emitIntoAsFlow = false,
                            silently = true
                        )
                    )
                }
            }
        }
        setPageState(
            state = pageState.copy(data = updatedData),
            emitIntoAsFlow = emitIntoAsFlow,
            silently = true
        )

        if (!silently) {
            val pageBefore = fastSearchPageBefore(cache[startContextPage])!!
            val pageAfter = fastSearchPageAfter(cache[endContextPage])!!
            val rangeSnapshot = pageBefore.page..pageAfter.page
            if (page in rangeSnapshot) {
                _snapshot.update { scan(rangeSnapshot) }
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

    fun addAllElements(
        elements: List<T>,
        page: UInt,
        index: Int,
        emitIntoAsFlow: Boolean = true,
        silently: Boolean = false,
        initPageState: ((page: UInt, data: List<T>) -> PageState<T>)? = null
    ) {
        val pageState = (cache[page] ?: initPageState?.invoke(page, emptyList()))
            ?: throw IndexOutOfBoundsException(
                "page-$page was not created"
            )

        val updatedData = pageState.data.toMutableList()
            .also { it.addAll(index, elements) }

        val extraElements: MutableList<T>? =
            if (updatedData.size > capacity && !ignoreCapacity) {
                MutableList(size = updatedData.size - capacity) {
                    updatedData.removeLast()
                }.also { it.reverse() }
            } else null

        setPageState(
            state = pageState.copy(data = updatedData),
            emitIntoAsFlow = emitIntoAsFlow,
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
                    emitIntoAsFlow = false,
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
                _snapshot.update { scan(rangeSnapshot) }
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

    fun getElement(
        page: UInt,
        index: Int,
    ): T? {
        return getPageState(page)
            ?.data?.get(index)
    }

    inline fun setElement(
        element: T,
        silently: Boolean = false,
        predicate: (T) -> Boolean
    ) {
        this.safeForeEach { pageState ->
            for ((index, e) in pageState.data.withIndex()) {
                if (predicate(e)) {
                    setElement(element, pageState.page, index, silently)
                }
            }
        }
    }

    fun setElement(
        element: T,
        page: UInt,
        index: Int,
        silently: Boolean = false
    ) {
        val pageState = cache.getValue(page)
        setPageState(
            state = pageState.copy(
                data = pageState.data.toMutableList()
                    .also { it[index] = element }
            ),
            silently = true
        )

        if (!silently) {
            val pageBefore = fastSearchPageBefore(cache[startContextPage])!!
            val pageAfter = fastSearchPageAfter(cache[endContextPage])!!
            val rangeSnapshot = pageBefore.page..pageAfter.page
            if (page in rangeSnapshot) {
                _snapshot.update { scan(rangeSnapshot) }
            }
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun isValidSuccessState(pageState: PageState<T>?): Boolean {
        if (pageState is EmptyPage) return false
        if (pageState !is SuccessPage) return false
        if (ignoreCapacity) return true
        return pageState.data.size == capacity
    }

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
            _snapshot.update { scan() }
        }
    }

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
                check(data.isNotEmpty()) { "data must not be empty" }
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

@Suppress("NOTHING_TO_INLINE")
inline fun PageState<*>?.isProgressState() = this is ProgressPage<*>

inline fun <reified T> PageState<T>.isRealProgressState() =
    this is PageState.ProgressPage<T> && this::class == T::class

@Suppress("NOTHING_TO_INLINE")
inline fun PageState<*>?.isEmptyState() = this is PageState.EmptyPage<*>

inline fun <reified T> PageState<T>.isRealEmptyState() =
    this is PageState.EmptyPage<T> && this::class == T::class

@Suppress("NOTHING_TO_INLINE")
inline fun PageState<*>?.isSuccessState() =
    this is PageState.SuccessPage<*> && this !is PageState.EmptyPage<*>

inline fun <reified T> PageState<T>.isRealSuccessState() =
    this.isSuccessState() && this::class == T::class

@Suppress("NOTHING_TO_INLINE")
inline fun PageState<*>?.isErrorState() = this is PageState.ErrorPage<*>

inline fun <reified T> PageState<T>.isRealErrorState() =
    this is PageState.ErrorPage<T> && this::class == T::class

inline fun <T> Paginator<T>.foreEach(
    action: (PageState<T>) -> Unit
) {
    for (state in this) {
        action(state.value)
    }
}

inline fun <T> Paginator<T>.safeForeEach(
    action: (PageState<T>) -> Unit
) {
    var i = 0
    val data = this.pageStates
    while (i < data.size) {
        action(data[i++])
    }
}

inline fun <T> Paginator<T>.indexOfFirst(
    predicate: (T) -> Boolean
): Pair<UInt, Int>? {
    for (page in pageStates) {
        val result = page.data.indexOfFirst(predicate)
        if (result != -1) return page.page to result
    }
    return null
}

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

inline fun <T> Paginator<T>.indexOfLast(
    predicate: (T) -> Boolean
): Pair<UInt, Int>? {
    for (page in pageStates.reversed()) {
        val result = page.data.indexOfLast(predicate)
        if (result != -1) return page.page to result
    }
    return null
}

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