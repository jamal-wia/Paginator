package com.jamal_aliev.paginator.compose.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import com.jamal_aliev.paginator.CursorPaginator
import com.jamal_aliev.paginator.Paginator
import com.jamal_aliev.paginator.extension.uiState
import com.jamal_aliev.paginator.page.PaginatorUiState

/**
 * Subscribes once to [Paginator.uiState] and exposes only the rendered items count.
 *
 * The flow itself fires on every state transition (loading / result / errors), but the
 * returned [State] only changes when the integer count itself changes — [derivedStateOf]
 * collapses every emission whose `.items.size` matches the previous one. This keeps any
 * downstream consumer (e.g., the prefetch binding) from recomposing on transient state changes
 * such as a loading flag flip.
 */
@Composable
internal fun <T> rememberPaginatorDataItemCount(paginator: Paginator<T>): State<Int> {
    val collected = paginator.uiState.collectAsState(initial = PaginatorUiState.Idle)
    return remember(collected) {
        derivedStateOf { dataItemCountOf(collected.value) }
    }
}

/** Cursor-paginator counterpart. */
@Composable
internal fun <T> rememberCursorPaginatorDataItemCount(paginator: CursorPaginator<T>): State<Int> {
    val collected = paginator.uiState.collectAsState(initial = PaginatorUiState.Idle)
    return remember(collected) {
        derivedStateOf { dataItemCountOf(collected.value) }
    }
}

private fun dataItemCountOf(state: PaginatorUiState<*>): Int =
    (state as? PaginatorUiState.Content<*>)?.items?.size ?: 0
