package com.jamal_aliev.paginator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jamal_aliev.paginator.MainViewState.DataState
import com.jamal_aliev.paginator.MainViewState.ProgressState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    private val _state: MutableStateFlow<MainViewState> = MutableStateFlow(ProgressState)
    val state = _state.asStateFlow()

    private val paginator = Paginator(source = { SampleRepository.loadPage(it.toInt()) })

    init {
        paginator.snapshot
            .filter { it.isNotEmpty() }
            .onEach { data -> _state.update { DataState(data) } }
            .flowOn(Dispatchers.Main)
            .launchIn(viewModelScope)

        viewModelScope.launch {
            val async1 = async { paginator.loadPageState(1u, forceLoading = true) }
            val async2 = async { paginator.loadPageState(2u, forceLoading = true) }
            val async3 = async { paginator.loadPageState(3u, forceLoading = true) }
            val pageState1 = async1.await()
            val pageState2 = async2.await()
            val pageState3 = async3.await()
            paginator.setPageState(state = pageState1, silently = true)
            paginator.setPageState(state = pageState2, silently = true)
            paginator.setPageState(state = pageState3, silently = true)
            paginator.jumpForward()
        }
    }


    fun endReached() {
        viewModelScope.launch {
            paginator.goNextPage()
        }
    }

    fun refreshPage(pageState: Paginator.PageState.Error<String>) {
        viewModelScope.launch {
            val newState = paginator.loadPageState(
                page = pageState.page,
                forceLoading = true,
                loading = { page, pageState ->
                    paginator.setPageState(paginator.ProgressState(page))
                }
            )
            paginator.setPageState(newState)
        }
    }

    override fun onCleared() {
        paginator.release()
        super.onCleared()
    }

    class Factory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel() as T
        }
    }
}