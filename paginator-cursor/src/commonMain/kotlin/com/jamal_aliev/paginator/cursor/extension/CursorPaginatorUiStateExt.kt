package com.jamal_aliev.paginator.cursor.extension

import com.jamal_aliev.paginator.core.extension.asUiState
import com.jamal_aliev.paginator.core.page.PaginatorUiState
import com.jamal_aliev.paginator.cursor.CursorPaginator
import kotlinx.coroutines.flow.Flow

/**
 * A [Flow] of high-level [PaginatorUiState] derived from this [CursorPaginator]'s
 * visible snapshot.
 *
 * Equivalent to `core.snapshot.asUiState { core.isStarted }`. The underlying
 * `toUiState`/`asUiState` functions in `paginator-core` are bookmark-agnostic —
 * they classify the snapshot solely by `PageState` type and data payload — so
 * the cursor paginator re-uses them without modification.
 */
val <K : Any, T> CursorPaginator<K, T>.uiState: Flow<PaginatorUiState<T>>
    get() = core.snapshot.asUiState(isStarted = { core.isStarted })
