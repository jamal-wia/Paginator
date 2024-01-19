package com.jamal_aliev.paginator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlin.math.max

class Paginator<T>(
    val source: suspend (page: UInt) -> List<T>,
) {

    val pages = hashMapOf<UInt, PageState<T>>()
    private var currentPage = 0u

    val bookmarks = mutableListOf(Bookmark(value = 1u))
    private val bookmarkIterator = bookmarks.listIterator()

    val snapshot = MutableStateFlow(emptyList<PageState<T>>())

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
                snapshot.update { scan() }
            },
            initEmptyState = initEmptyState,
            initDataState = initDataState,
            initErrorState = initErrorState
        ).also { finalPageState ->
            pages[pageOfBookmark] = finalPageState
            snapshot.update { scan() }
        }
    }

    suspend fun nextPage(
        initProgressState: (() -> PageState.ProgressState<T>)? = null,
        initEmptyState: (() -> PageState.EmptyState<T>)? = null,
        initDataState: ((List<T>) -> PageState.DataState<T>)? = null,
        initErrorState: ((Exception) -> PageState.ErrorState<T>)? = null
    ) {
        refreshJob?.join()

        val nextPage = getMaxPageFrom(currentPage) + 1u
        if (nextPage < 1u) return
        currentPage = nextPage
        val lastSnapshot = snapshot.value

        loadPageState(
            page = nextPage,
            loading = { page ->
                val progressState = initProgressState?.invoke() ?: ProgressState()
                pages[page] = progressState
                snapshot.update { lastSnapshot + progressState }
            },
            initEmptyState = initEmptyState,
            initDataState = initDataState,
            initErrorState = initErrorState
        ).also { finalPageState ->
            pages[nextPage] = finalPageState
            snapshot.update { lastSnapshot + finalPageState }
        }
    }

    suspend fun previousPage(
        initProgressState: (() -> PageState.ProgressState<T>)? = null,
        initEmptyState: (() -> PageState.EmptyState<T>)? = null,
        initDataState: ((List<T>) -> PageState.DataState<T>)? = null,
        initErrorState: ((Exception) -> PageState.ErrorState<T>)? = null
    ) {
        refreshJob?.join()

        val previousPage = getMinPageFrom(currentPage) - 1u
        if (previousPage < 1u) return
        currentPage = previousPage
        val lastSnapshot = snapshot.value

        loadPageState(
            page = previousPage,
            loading = { page ->
                val progressState = initProgressState?.invoke() ?: ProgressState()
                pages[page] = progressState
                snapshot.update { listOf(progressState) + lastSnapshot }
            },
            initEmptyState = initEmptyState,
            initDataState = initDataState,
            initErrorState = initErrorState
        ).also { finalPageState ->
            pages[previousPage] = finalPageState
            snapshot.update { listOf(finalPageState) + lastSnapshot }
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
            snapshot.update {
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
                    snapshot.update { scan() }
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

    fun remove(
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

    fun remove(page: UInt, index: Int): T {
        val pageState = pages.getValue(page)
        val removed: T
        pages[page] = pageState.copy(
            data = pageState.data.toMutableList()
                .also { removed = it.removeAt(index) }
        )
        return removed
    }

    fun add(
        element: T,
        initPageState: (() -> PageState<T>)? = null
    ): Boolean {
        val lastPage = pages.keys.maxOrNull() ?: return false
        val lastIndex = pages.getValue(lastPage).data.lastIndex
        add(element, lastPage, lastIndex, initPageState)
        return true
    }

    fun add(
        element: T,
        page: UInt,
        index: Int,
        initPageState: (() -> PageState<T>)? = null
    ) {
        val pageState = (initPageState?.invoke() ?: pages[page])
            ?: throw IndexOutOfBoundsException(
                "page-$page was not created"
            )
        pages[page] = pageState.copy(
            data = pageState.data.toMutableList()
                .also { it.add(index, element) }
        )
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

    fun getMaxPageFrom(page: UInt): UInt {
        var max = page
        while (true) {
            if (pages.containsKey(max + 1u)) max++
            else break
        }
        return max
    }

    fun getMinPageFrom(page: UInt): UInt {
        var min = page
        while (true) {
            if (pages.containsKey(min - 1u)) min--
            else break
        }
        return min
    }

    fun release() {
        pages.clear()
        bookmarks.clear()
        currentPage = 0u
        snapshot.update { emptyList() }
        refreshJob = null
        paginatorScope.cancel()
    }

    fun ProgressState(data: List<T> = emptyList()) =
        PageState.ProgressState(data)

    fun DataState(data: List<T> = emptyList()) =
        PageState.DataState(data)

    fun EmptyState(data: List<T> = emptyList()) =
        PageState.EmptyState(data)

    fun ErrorState(e: Exception, data: List<T> = emptyList()) =
        PageState.ErrorState(e, data)

    override fun toString() = "Paginator(pages=$pages, bookmarks=$bookmarks)"

    override fun hashCode() = pages.hashCode()

    override fun equals(other: Any?) = (other as? Paginator<*>)?.pages === this.pages

    sealed class PageState<T>(open val data: List<T>) {

        class ProgressState<T> internal constructor(
            override val data: List<T>
        ) : PageState<T>(data)

        class DataState<T> internal constructor(
            override val data: List<T>
        ) : PageState<T>(data)

        class EmptyState<T> internal constructor(
            override val data: List<T>
        ) : PageState<T>(data)

        class ErrorState<T> internal constructor(
            val e: Exception,
            override val data: List<T>
        ) : PageState<T>(data)

        fun copy(data: List<T> = this.data): PageState<T> = when (this) {
            is ProgressState -> ProgressState(data)
            is DataState -> DataState(data)
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
