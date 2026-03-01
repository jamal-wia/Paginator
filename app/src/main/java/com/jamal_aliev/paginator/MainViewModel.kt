package com.jamal_aliev.paginator

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import com.jamal_aliev.paginator.exception.FinalPageExceededException
import com.jamal_aliev.paginator.extension.isEmptyState
import com.jamal_aliev.paginator.extension.isErrorState
import com.jamal_aliev.paginator.extension.isProgressState
import com.jamal_aliev.paginator.extension.isRealProgressState
import com.jamal_aliev.paginator.extension.isSuccessState
import com.jamal_aliev.paginator.logger.PaginatorLogger
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.serialization.restoreStateFromJson
import com.jamal_aliev.paginator.serialization.saveStateToJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer

class MainViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(MainViewState())
    val state = _state.asStateFlow()

    val paginator = MutablePaginator<String>(source = { page ->
        SampleRepository.loadPage(page)
    }).apply {
        core.resize(SampleRepository.PAGE_SIZE, resize = false, silently = true)
        finalPage = SampleRepository.FINAL_PAGE
        // Default bookmarks already has page 1, add more
        bookmarks.addAll(
            listOf(
                BookmarkInt(5),
                BookmarkInt(10),
                BookmarkInt(15),
            )
        )
        recyclingBookmark = true
        logger = AndroidLogger
    }

    init {
        paginator.core.snapshot
            .filter { it.isNotEmpty() }
            .onEach { data ->
                updateStateFromPaginator(data)
                // Save paginator state to SavedStateHandle after each snapshot update,
                // so it survives process death.
                savePaginatorState()
            }
            .flowOn(Dispatchers.Main)
            .launchIn(viewModelScope)

        // Try to restore state from SavedStateHandle (after process death).
        // If no saved state exists, start fresh by jumping to page 1.
        val restored = restorePaginatorState()
        if (!restored) {
            viewModelScope.launch {
                paginator.jump(bookmark = BookmarkInt(1))
                _state.update { it.copy(isInitialLoading = false) }
            }
        } else {
            _state.update { it.copy(isInitialLoading = false) }
        }
    }

    /**
     * Saves the current paginator cache state to [SavedStateHandle] as a JSON string.
     * Called after each snapshot update so the state is always up to date.
     */
    private fun savePaginatorState() {
        val json = paginator.core.saveStateToJson(String.serializer())
        savedStateHandle[KEY_PAGINATOR_STATE] = json
    }

    /**
     * Restores paginator state from [SavedStateHandle] if available.
     * This happens after process death when the system recreates the Activity/ViewModel.
     *
     * @return `true` if state was restored, `false` if no saved state was found.
     */
    private fun restorePaginatorState(): Boolean {
        val json = savedStateHandle.get<String>(KEY_PAGINATOR_STATE) ?: return false
        return try {
            paginator.core.restoreStateFromJson(json, String.serializer())
            Log.d("MainViewModel", "Paginator state restored from SavedStateHandle")
            true
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to restore paginator state", e)
            false
        }
    }

    private fun updateStateFromPaginator(data: List<PageState<String>>? = null) {
        val snapshotData = data ?: _state.value.data
        _state.update { current ->
            current.copy(
                data = snapshotData,
                startContextPage = paginator.core.startContextPage,
                endContextPage = paginator.core.endContextPage,
                finalPage = paginator.finalPage,
                bookmarks = paginator.bookmarks.map { it.page },
                cachedPages = paginator.core.pages.map { page ->
                    val pageState = paginator.core.getStateOf(page)
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
                totalCachedItems = paginator.core.states.sumOf { it.data.size },
            )
        }
    }

    fun goNextPage() {
        viewModelScope.launch {
            try {
                paginator.goNextPage(
                    initProgressState = { page: Int, data: List<String> ->
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
                    initProgressState = { page: Int, data: List<String> ->
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

    fun jumpToPage(page: Int) {
        viewModelScope.launch {
            try {
                paginator.jump(bookmark = BookmarkInt(page))
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

    fun refreshPage(page: Int) {
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
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            val savedStateHandle = extras.createSavedStateHandle()
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(savedStateHandle) as T
        }
    }

    companion object {
        private const val KEY_PAGINATOR_STATE = "paginator_state"
    }
}

class NextProgressState(
    page: Int, data: List<String>
) : PageState.ProgressPage<String>(
    page = page,
    data = data,
)


class PreviousProgressState(
    page: Int, data: List<String>
) : PageState.ProgressPage<String>(
    page = page,
    data = data,
)

object AndroidLogger : PaginatorLogger {
    override fun log(tag: String, message: String) {
        Log.d(tag, message)
    }
}
