package com.jamal_aliev.paginator.compose.cursor.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import com.jamal_aliev.paginator.core.page.PaginatorUiState
import com.jamal_aliev.paginator.cursor.CursorPaginator
import com.jamal_aliev.paginator.cursor.extension.uiState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Subscribes to [CursorPaginator.uiState] and exposes only the rendered items count as a
 * primitive [MutableIntState]. Cursor-paginator counterpart of `rememberPaginatorDataItemCount`
 * in the offset module — see its KDoc for the implementation contract.
 */
@Composable
internal fun <T> rememberCursorPaginatorDataItemCount(paginator: CursorPaginator<T>): MutableIntState {
    val state = remember(paginator) { mutableIntStateOf(0) }
    LaunchedEffect(paginator) {
        paginator.uiState
            .map(::dataItemCountOf)
            .distinctUntilChanged()
            .collect { count -> if (state.intValue != count) state.intValue = count }
    }
    return state
}

private fun dataItemCountOf(state: PaginatorUiState<*>): Int =
    (state as? PaginatorUiState.Content<*>)?.items?.size ?: 0
