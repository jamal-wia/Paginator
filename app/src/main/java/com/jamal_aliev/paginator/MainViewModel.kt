package com.jamal_aliev.paginator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jamal_aliev.paginator.MainViewState.DataState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {
    private val _state = MutableStateFlow(DataState(emptyList()))

    val state = _state.asStateFlow()
    private val paginator = Paginator { SampleRepository.loadPage(it.toInt()) }

    init {
        paginator.snapshot
            .map { snapshot ->
                val result = arrayListOf<String>()
                snapshot.forEach { it.data.forEach { str -> result.add(str) } }
                result
            }
            .flowOn(Dispatchers.Default)
            .onEach { data -> _state.update { DataState(data) } }
            .flowOn(Dispatchers.Main)
            .launchIn(viewModelScope)

        viewModelScope.launch {
            val async1 = async { paginator.loadPageState(1u) }
            val async2 = async { paginator.loadPageState(2u) }
            val async3 = async { paginator.loadPageState(3u) }
            paginator.pages[1u] = async1.await()
            paginator.pages[2u] = async2.await()
            paginator.pages[3u] = async3.await()
            paginator.jumpForward()
        }
    }

    fun endReached() {
        viewModelScope.launch {
            paginator.nextPage()
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