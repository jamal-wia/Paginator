package com.jamal_aliev.paginator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max

class Paginator<T>(
    val source: suspend (page: UInt) -> List<T>,
    val pageCapacity: Int = 20,
) {

    init {
        check(pageCapacity > 0)
    }

    private var currentPage = 0u
    private val pages = hashMapOf<UInt, PageState<T>>()

    private val bookmarks = mutableListOf(Bookmark(value = 1u))
    private val bookmarkIterator = bookmarks.listIterator()

    private val _snapshot = MutableStateFlow(emptyList<PageState<T>>())
    val snapshot = _snapshot.asStateFlow()

    protected val paginatorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * jump to next bookmark if it excise
     * */
    suspend fun jumpForward(
        initProgressState: (() -> PageState.ProgressState<T>)? = null,
        initEmptyState: (() -> PageState.EmptyState<T>)? = null,
        initDataState: ((List<T>) -> PageState.DataState<T>)? = null,
        initErrorState: ((Exception) -> PageState.ErrorState<T>)? = null
    ) {
        val bookmark = if (bookmarkIterator.hasNext()) bookmarkIterator.next() else return
        return jump(
            bookmark = bookmark,
            initProgressState = initProgressState,
            initEmptyState = initEmptyState,
            initDataState = initDataState,
            initErrorState = initErrorState,
        )
    }

    /**
     * jump to previous bookmark if it excise
     * */
    suspend fun jumpBack(
        initProgressState: (() -> PageState.ProgressState<T>)? = null,
        initEmptyState: (() -> PageState.EmptyState<T>)? = null,
        initDataState: ((List<T>) -> PageState.DataState<T>)? = null,
        initErrorState: ((Exception) -> PageState.ErrorState<T>)? = null
    ) {
        val bookmark = if (bookmarkIterator.hasPrevious()) bookmarkIterator.previous() else return
        return jump(
            bookmark = bookmark,
            initProgressState = initProgressState,
            initEmptyState = initEmptyState,
            initDataState = initDataState,
            initErrorState = initErrorState,
        )
    }

    suspend fun jump(
        bookmark: Bookmark,
        initProgressState: (() -> PageState.ProgressState<T>)? = null,
        initEmptyState: (() -> PageState.EmptyState<T>)? = null,
        initDataState: ((List<T>) -> PageState.DataState<T>)? = null,
        initErrorState: ((Exception) -> PageState.ErrorState<T>)? = null
    ) {
        refreshJob?.join()

        val pageOfBookmark = bookmark.value
        if (pageOfBookmark < 1u) return
        currentPage = pageOfBookmark

        loadPageState(
            page = pageOfBookmark,
            loading = { page ->
                val progressState = initProgressState?.invoke() ?: ProgressState()
                pages[page] = progressState
                _snapshot.update { scan() }
            },
            initEmptyState = initEmptyState,
            initDataState = initDataState,
            initErrorState = initErrorState
        ).also { finalPageState ->
            pages[pageOfBookmark] = finalPageState
            _snapshot.update { scan() }
        }
    }

    suspend fun nextPage(
        initProgressState: (() -> PageState.ProgressState<T>)? = null,
        initEmptyState: (() -> PageState.EmptyState<T>)? = null,
        initDataState: ((List<T>) -> PageState.DataState<T>)? = null,
        initErrorState: ((Exception) -> PageState.ErrorState<T>)? = null
    ) {
        refreshJob?.join()

        val nextPage = getMaxPageFrom(currentPage, predicate = { it.isDataState() }) + 1u
        if (nextPage < 1u) return
        val lastSnapshot = _snapshot.value

        loadPageState(
            page = nextPage,
            loading = { page ->
                val progressState = initProgressState?.invoke() ?: ProgressState()
                pages[page] = progressState
                _snapshot.update { lastSnapshot + progressState }
            },
            initEmptyState = initEmptyState,
            initDataState = initDataState,
            initErrorState = initErrorState
        ).also { finalPageState ->
            if (finalPageState.isDataState()) currentPage = nextPage
            pages[nextPage] = finalPageState
            _snapshot.update { lastSnapshot + finalPageState }
        }
    }

    suspend fun previousPage(
        initProgressState: (() -> PageState.ProgressState<T>)? = null,
        initEmptyState: (() -> PageState.EmptyState<T>)? = null,
        initDataState: ((List<T>) -> PageState.DataState<T>)? = null,
        initErrorState: ((Exception) -> PageState.ErrorState<T>)? = null
    ) {
        refreshJob?.join()

        val previousPage = getMinPageFrom(currentPage, predicate = { it.isDataState() }) - 1u
        if (previousPage < 1u) return
        val lastSnapshot = _snapshot.value

        loadPageState(
            page = previousPage,
            loading = { page ->
                val progressState = initProgressState?.invoke() ?: ProgressState()
                pages[page] = progressState
                _snapshot.update { listOf(progressState) + lastSnapshot }
            },
            initEmptyState = initEmptyState,
            initDataState = initDataState,
            initErrorState = initErrorState
        ).also { finalPageState ->
            if (finalPageState.isDataState()) currentPage = previousPage
            pages[previousPage] = finalPageState
            _snapshot.update { listOf(finalPageState) + lastSnapshot }
        }
    }

    private var refreshJob: Job? = null
    fun refresh(
        initProgressState: ((data: List<T>) -> PageState.ProgressState<T>)? = null,
        initEmptyState: (() -> PageState.EmptyState<T>)? = null,
        initDataState: ((List<T>) -> PageState.DataState<T>)? = null,
        initErrorState: ((Exception) -> PageState.ErrorState<T>)? = null
    ) {
        refreshJob = paginatorScope.launch {
            delay(timeMillis = 1L)

            pages.forEach { (k, v) ->
                pages[k] = initProgressState?.invoke(v.data)
                    ?: ProgressState(v.data)
            }
            _snapshot.update {
                it.map { pageState ->
                    initProgressState?.invoke(pageState.data)
                        ?: ProgressState(pageState.data)
                }
            }

            pages.keys.toList()
                .map { page ->
                    page to async {
                        loadPageState(
                            page = page,
                            forceLoading = true,
                            initEmptyState = initEmptyState,
                            initDataState = initDataState,
                            initErrorState = initErrorState
                        )
                    }
                }
                .forEach { (page, async) ->
                    pages[page] = async.await()
                    _snapshot.update { scan() }
                }

            refreshJob = null
        }
    }

    suspend fun loadPageState(
        page: UInt,
        forceLoading: Boolean = false,
        loading: ((page: UInt) -> Unit)? = null,
        source: suspend (page: UInt) -> List<T> = this.source,
        initEmptyState: (() -> PageState.EmptyState<T>)? = null,
        initDataState: ((List<T>) -> PageState.DataState<T>)? = null,
        initErrorState: ((Exception) -> PageState.ErrorState<T>)? = null
    ): PageState<T> {
        return try {
            val cachedState = if (forceLoading) null else pages[page]
            if (cachedState is PageState.DataState<*>)
                return cachedState
            loading?.invoke(page)
            val data = source.invoke(page)
            if (data.isEmpty()) initEmptyState?.invoke() ?: EmptyState()
            else initDataState?.invoke(data) ?: DataState(data)
        } catch (e: Exception) {
            initErrorState?.invoke(e) ?: ErrorState(e)
        }
    }

    fun setPageState(page: UInt, state: PageState<T>) {
        pages[page] = state
    }

    fun removeBookmark(bookmark: Bookmark): Boolean {
        return bookmarks.remove(bookmark)
    }

    fun removePageState(
        page: UInt,
        initEmptyState: (() -> PageState<T>)? = null
    ): PageState<T>? {
        val removed = pages.remove(page)
        if (removed != null) {
            pages[page] = initEmptyState?.invoke()
                ?: EmptyState()
        }
        return removed
    }

    fun removeElement(predicate: (T) -> Boolean): T? {
        for (k in pages.keys.toList()) {
            val v = pages.getValue(k)
            for ((i, e) in v.data.withIndex()) {
                if (predicate(e)) {
                    return removeElement(page = k, index = i)
                }
            }
        }
        return null
    }

    fun removeElement(
        page: UInt,
        index: Int,
        silently: Boolean = false,
    ): T {
        val pageState = pages.getValue(page)
        val removed: T

        val updatedData = pageState.data.toMutableList()
            .also { removed = it.removeAt(index) }

        if (updatedData.size < pageCapacity) {
            val nextPageState = pages[page + 1u]
            if (nextPageState != null
                && nextPageState::class == pageState::class
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

        pages[page] = pageState.copy(updatedData)

        if (!silently) {
            val rangeSnapshot = getMinPageFrom(currentPage)..getMaxPageFrom(currentPage)
            if (page in rangeSnapshot) {
                _snapshot.update { scan(rangeSnapshot) }
            }
        }

        return removed
    }

    fun addBookmark(bookmark: Bookmark): Boolean {
        return bookmarks.add(bookmark)
    }

    fun addElement(
        element: T,
        silently: Boolean = false,
        initPageState: (() -> PageState<T>)? = null
    ): Boolean {
        val lastPage = pages.keys.maxOrNull() ?: return false
        val lastIndex = pages.getValue(lastPage).data.lastIndex
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
        val pageState = (initPageState?.invoke() ?: pages[page])
            ?: throw IndexOutOfBoundsException(
                "page-$page was not created"
            )

        val updatedData = pageState.data.toMutableList()
            .also { it.add(index, element) }
        val extraElement =
            if (updatedData.size > pageCapacity)
                updatedData.removeLast()
            else null

        pages[page] = pageState.copy(data = updatedData)

        if (extraElement != null) {
            val nextPageState = pages[page + 1u]
            if ((nextPageState != null && nextPageState::class == pageState::class) ||
                (nextPageState == null && initPageState != null)
            ) {
                addElement(
                    element = extraElement,
                    page = page + 1u,
                    index = 0,
                    silently = true,
                    initPageState = initPageState
                )
            }
        }

        if (!silently) {
            val rangeSnapshot = getMinPageFrom(currentPage)..getMaxPageFrom(currentPage)
            if (page in rangeSnapshot) {
                _snapshot.update { scan(rangeSnapshot) }
            }
        }
    }

    fun scan(
        range: UIntRange = kotlin.run {
            val min = getMinPageFrom(currentPage)
            val max = getMaxPageFrom(currentPage)
            return@run min..max
        }
    ): List<PageState<T>> {
        val capacity = max(range.last - range.first, 1u)
        val result = ArrayList<PageState<T>>(capacity.toInt())
        for (item in range) {
            val page = pages[item] ?: break
            result.add(page)
        }
        return result
    }

    fun getMaxPageFrom(
        page: UInt,
        predicate: (PageState<T>) -> Boolean = { true }
    ): UInt {
        var max = page
        while (true) {
            val pageState = pages[max + 1u]
            if (pageState != null && predicate(pageState)) max++
            else break
        }
        return max
    }

    fun getMinPageFrom(
        page: UInt,
        predicate: (PageState<T>) -> Boolean = { true }
    ): UInt {
        var min = page
        while (true) {
            val pageState = pages[min - 1u]
            if (pageState != null && predicate(pageState)) min--
            else break
        }
        return min
    }

    fun release() {
        pages.clear()
        bookmarks.clear()
        currentPage = 0u
        _snapshot.update { emptyList() }
        refreshJob = null
        paginatorScope.cancel()
    }

    fun ProgressState(data: List<T> = emptyList()) =
        PageState.ProgressState(data)

    fun DataState(data: List<T> = emptyList()) =
        if (data.isEmpty()) EmptyState() else PageState.DataState(data)

    fun EmptyState(data: List<T> = emptyList()) =
        PageState.EmptyState(data)

    fun ErrorState(e: Exception, data: List<T> = emptyList()) =
        PageState.ErrorState(e, data)

    override fun toString() = "Paginator(pages=$pages, bookmarks=$bookmarks)"

    override fun hashCode() = pages.hashCode()

    override fun equals(other: Any?) = (other as? Paginator<*>)?.pages === this.pages

    sealed class PageState<E>(open val data: List<E>) {

        open class ProgressState<T>(
            override val data: List<T>
        ) : PageState<T>(data)

        open class DataState<T>(
            override val data: List<T>
        ) : PageState<T>(data)

        open class EmptyState<T>(
            override val data: List<T>
        ) : PageState<T>(data)

        open class ErrorState<T>(
            val e: Exception,
            override val data: List<T>
        ) : PageState<T>(data)

        fun isProgressState() = this is ProgressState<E>
        fun isEmptyState() = this is DataState<E>
        fun isDataState() = this is EmptyState<E>
        fun isErrorState() = this is ErrorState<E>

        fun copy(data: List<E> = this.data): PageState<E> = when (this) {
            is ProgressState -> ProgressState(data)
            is DataState -> if (data.isEmpty()) EmptyState(data) else DataState(data)
            is EmptyState -> EmptyState(data)
            is ErrorState -> ErrorState(this.e, data)
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
    value class Bookmark(val value: UInt)
}
