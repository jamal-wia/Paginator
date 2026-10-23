package com.jamal_aliev.paginator.cursor.extension

import com.jamal_aliev.paginator.core.extension.toUiState
import com.jamal_aliev.paginator.core.page.PaginatorUiState
import com.jamal_aliev.paginator.cursor.CursorPaginator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * A [Flow] of high-level [PaginatorUiState] derived from this [CursorPaginator]'s
 * visible snapshot.
 *
 * Maps [core.visibleSnapshot][com.jamal_aliev.paginator.cursor.CursorPagingCore.visibleSnapshot]
 * through the bookmark-agnostic `toUiState` of `paginator-core`, with the "started" flag captured
 * in the same emission. During the anchorless first load the visible list is the single
 * [bootstrap state][com.jamal_aliev.paginator.cursor.CursorPagingCore.bootstrapState], so this
 * yields [PaginatorUiState.Loading] while `load(null)` is in flight and [PaginatorUiState.Error]
 * after it failed — even though no page is cached yet.
 */
val <K : Any, T> CursorPaginator<K, T>.uiState: Flow<PaginatorUiState<T>>
    get() = core.visibleEmissions.map { (pages, started) -> pages.toUiState(isStarted = started) }
