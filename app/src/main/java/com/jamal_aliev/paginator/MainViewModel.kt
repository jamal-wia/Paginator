package com.jamal_aliev.paginator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jamal_aliev.paginator.MainViewState.DataState
import com.jamal_aliev.paginator.MainViewState.ProgressState
import com.jamal_aliev.paginator.page.PageState
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

    private val paginator = MutablePaginator(source = { SampleRepository.loadPage(it.toInt()) })

    init {
        paginator.snapshot
            .filter { it.isNotEmpty() }
            .onEach { data -> _state.update { DataState(data) } }
            .flowOn(Dispatchers.Main)
            .launchIn(viewModelScope)

        viewModelScope.launch {
            val async1 = async { paginator.loadOrGetPageState(1u, forceLoading = true) }
            val async2 = async { paginator.loadOrGetPageState(2u, forceLoading = true) }
            val async3 = async { paginator.loadOrGetPageState(3u, forceLoading = true) }
            val pageState1 = async1.await()
            val pageState2 = async2.await()
            val pageState3 = async3.await()
            paginator.setState(state = pageState1, silently = true)
            paginator.setState(state = pageState2, silently = true)
            paginator.setState(state = pageState3, silently = true)
            paginator.jumpForward()
        }
    }

    // 1,2,3 ... 11,12,13
    // 0,1,2     3 ,4 ,5

    //


    fun endReached() {
        viewModelScope.launch {
            paginator.goNextPage()
        }
    }

    fun refreshPage(pageState: PageState.ErrorPage<String>) {
        viewModelScope.launch {
            paginator.refresh(pages = listOf(pageState.page))
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