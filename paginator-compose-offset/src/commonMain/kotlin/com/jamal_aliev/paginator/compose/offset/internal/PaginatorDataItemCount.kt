package com.jamal_aliev.paginator.compose.offset.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import com.jamal_aliev.paginator.core.page.PaginatorUiState
import com.jamal_aliev.paginator.offset.Paginator
import com.jamal_aliev.paginator.offset.extension.uiState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Subscribes to [Paginator.uiState] and exposes only the rendered items count as a primitive
 * [MutableIntState].
 *
 * Implementation notes — kept here because every choice is load-bearing:
 *   - the projection (`PaginatorUiState` → `Int`) and the de-duplication run on the flow itself,
 *     so the snapshot system never sees a `PaginatorUiState` value and downstream observers are
 *     invalidated only when the integer changes;
 *   - the result is held in a primitive [MutableIntState] (no `Int` boxing per emission, no
 *     intermediate `State<PaginatorUiState>` reference that would otherwise be created by
 *     `collectAsState`);
 *   - the collector restarts only when the [Paginator] identity changes — `LaunchedEffect`
 *     keyed on the paginator handles that on its own.
 */
@Composable
internal fun <T> rememberPaginatorDataItemCount(paginator: Paginator<T>): MutableIntState {
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
