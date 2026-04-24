package com.jamal_aliev.paginator.extension

import com.jamal_aliev.paginator.CursorPaginator
import com.jamal_aliev.paginator.page.PaginatorUiState
import kotlinx.coroutines.flow.Flow

/**
 * A [Flow] of high-level [PaginatorUiState] derived from this [CursorPaginator]'s
 * visible snapshot.
 *
 * Equivalent to `core.snapshot.asUiState { core.isStarted }`. The underlying
 * [toUiState]/[asUiState] functions are **bookmark-agnostic** — they classify
 * the snapshot solely by [PageState] type and data payload — so the cursor
 * paginator re-uses them without modification.
 */
val <T> CursorPaginator<T>.uiState: Flow<PaginatorUiState<T>>
    get() = core.snapshot.asUiState(isStarted = { core.isStarted })
