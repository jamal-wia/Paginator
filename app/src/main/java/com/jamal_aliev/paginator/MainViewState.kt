package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.page.PageState

sealed class MainViewState {

    data class DataState(
        val data: List<PageState<String>>
    ) : MainViewState()

    data object ProgressState : MainViewState()
}