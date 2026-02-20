package com.jamal_aliev.paginator

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkUInt
import com.jamal_aliev.paginator.exception.FinalPageExceededException
import com.jamal_aliev.paginator.extension.isEmptyState
import com.jamal_aliev.paginator.extension.isErrorState
import com.jamal_aliev.paginator.extension.isProgressState
import com.jamal_aliev.paginator.extension.isRealProgressState
import com.jamal_aliev.paginator.extension.isSuccessState
import com.jamal_aliev.paginator.logger.PaginatorLogger
import com.jamal_aliev.paginator.page.PageState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    private val _state = MutableStateFlow(MainViewState())
    val state = _state.asStateFlow()

    val paginator = MutablePaginator<String>(source = { page ->
        SampleRepository.loadPage(page.toInt())
    }).apply {
        resize(SampleRepository.PAGE_SIZE, resize = false, silently = true)
        finalPage = SampleRepository.FINAL_PAGE.toUInt()
        // Default bookmarks already has page 1, add more
        bookmarks.addAll(
            listOf(
                BookmarkUInt(5u),
                BookmarkUInt(10u),
                BookmarkUInt(15u),
            )
        )
        recyclingBookmark = true
        logger = AndroidLogger
    }

    init {
        paginator.snapshot
            .filter { it.isNotEmpty() }
            .onEach { data ->
                updateStateFromPaginator(data)
            }
            .flowOn(Dispatchers.Main)
            .launchIn(viewModelScope)

        viewModelScope.launch {
            paginator.jump(bookmark = BookmarkUInt(1u))
            _state.update { it.copy(isInitialLoading = false) }
        }
    }

    private fun updateStateFromPaginator(data: List<PageState<String>>? = null) {
        val snapshotData = data ?: _state.value.data
        _state.update { current ->
            current.copy(
                data = snapshotData,
                startContextPage = paginator.startContextPage,
                endContextPage = paginator.endContextPage,
                finalPage = paginator.finalPage,
                bookmarks = paginator.bookmarks.map { it.page },
                cachedPages = paginator.pages.map { page ->
                    val pageState = paginator.getStateOf(page)
                    CachedPageInfo(
                        page = page,
                        type = when {
                            pageState != null && pageState.isRealProgressState(PreviousProgressState::class) -> "Progress ↑"
                            pageState != null && pageState.isRealProgressState(NextProgressState::class) -> "Progress ↓"
                            pageState.isProgressState() -> "Progress"
                            pageState.isErrorState() -> "Error"
                            pageState.isEmptyState() -> "Empty"
                            pageState.isSuccessState() -> "Success(${pageState.data.size})"
                            else -> "Unknown"
                        },
                        itemCount = pageState?.data?.size ?: 0
                    )
                },
                totalCachedItems = paginator.pageStates.sumOf { it.data.size },
            )
        }
    }

    fun goNextPage() {
        viewModelScope.launch {
            try {
                paginator.goNextPage(
                    initProgressState = { page: UInt, data: List<String> ->
                        NextProgressState(page, data)
                    }
                )
            } catch (e: FinalPageExceededException) {
                _state.update { it.copy(errorMessage = "Reached final page ${e.finalPage}") }
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    fun goPreviousPage() {
        viewModelScope.launch {
            try {
                paginator.goPreviousPage(
                    initProgressState = { page: UInt, data: List<String> ->
                        PreviousProgressState(page, data)
                    }
                )
            } catch (e: IllegalStateException) {
                _state.update { it.copy(errorMessage = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    fun jumpToPage(page: UInt) {
        viewModelScope.launch {
            try {
                paginator.jump(bookmark = BookmarkUInt(page))
            } catch (e: FinalPageExceededException) {
                _state.update { it.copy(errorMessage = "Page ${e.attemptedPage} exceeds final page ${e.finalPage}") }
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    fun jumpForward() {
        viewModelScope.launch {
            try {
                val result = paginator.jumpForward()
                if (result == null) {
                    _state.update { it.copy(errorMessage = "No more bookmarks forward") }
                }
            } catch (e: FinalPageExceededException) {
                _state.update { it.copy(errorMessage = "Bookmark exceeds final page ${e.finalPage}") }
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    fun jumpBackward() {
        viewModelScope.launch {
            try {
                val result = paginator.jumpBack()
                if (result == null) {
                    _state.update { it.copy(errorMessage = "No more bookmarks backward") }
                }
            } catch (e: FinalPageExceededException) {
                _state.update { it.copy(errorMessage = "Bookmark exceeds final page ${e.finalPage}") }
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    fun restart() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            try {
                paginator.restart()
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.message) }
            } finally {
                _state.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun refreshPage(page: UInt) {
        viewModelScope.launch {
            try {
                paginator.refresh(pages = listOf(page))
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        paginator.release()
        super.onCleared()
    }

    class Factory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel() as T
        }
    }
}

class NextProgressState(
    page: UInt, data: List<String>
) : PageState.ProgressPage<String>(
    page = page,
    data = data,
)


class PreviousProgressState(
    page: UInt, data: List<String>
) : PageState.ProgressPage<String>(
    page = page,
    data = data,
)

object AndroidLogger : PaginatorLogger {
    override fun log(tag: String, message: String) {
        Log.d(tag, message)
    }
}
