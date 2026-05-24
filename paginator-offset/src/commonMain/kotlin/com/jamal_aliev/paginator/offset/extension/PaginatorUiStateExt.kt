package com.jamal_aliev.paginator.offset.extension

import com.jamal_aliev.paginator.core.extension.asUiState
import com.jamal_aliev.paginator.core.page.PaginatorUiState
import com.jamal_aliev.paginator.offset.Paginator
import kotlinx.coroutines.flow.Flow

/**
 * A [Flow] of high-level [PaginatorUiState] derived from this [Paginator]'s
 * visible snapshot.
 *
 * Equivalent to `core.snapshot.asUiState { core.isStarted }`. Collect this from
 * your UI layer instead of the raw snapshot when you only need full-screen
 * Idle/Loading/Empty/Error/Content states and boundary indicators for
 * pagination.
 *
 * @see com.jamal_aliev.paginator.core.extension.asUiState
 * @see com.jamal_aliev.paginator.core.extension.toUiState
 */
val <T> Paginator<T>.uiState: Flow<PaginatorUiState<T>>
    get() = core.snapshot.asUiState(isStarted = { core.isStarted })
