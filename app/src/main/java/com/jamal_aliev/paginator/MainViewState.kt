package com.jamal_aliev.paginator

sealed class MainViewState {
    data class DataState(
        val data: List<String>
    ) : MainViewState()
}