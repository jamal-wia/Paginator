package com.jamal_aliev.paginator.core.extension

import com.jamal_aliev.paginator.core.page.PageState
import com.jamal_aliev.paginator.core.page.PageState.ErrorPage
import com.jamal_aliev.paginator.core.page.PageState.ProgressPage
import com.jamal_aliev.paginator.core.page.PageState.SuccessPage
import com.jamal_aliev.paginator.core.page.PaginatorUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Maps a snapshot list of [PageState] into a high-level [PaginatorUiState].
 *
 * Classification rules (checked in order):
 * 1. If [isStarted] is `false` or the list is empty → [PaginatorUiState.Idle].
 * 2. If the list has exactly one element:
 *    - [ProgressPage] with empty data → [PaginatorUiState.Loading].
 *    - [SuccessPage] with empty data → [PaginatorUiState.Empty].
 *    - [ErrorPage] with empty data → [PaginatorUiState.Error].
 *    - Otherwise falls through to [PaginatorUiState.Content].
 * 3. Everything else → [PaginatorUiState.Content], where:
 *    - [PaginatorUiState.Content.prependState] is the first element when it is
 *      not a [SuccessPage] carrying items, otherwise `null`.
 *    - [PaginatorUiState.Content.appendState] is the last element when it is
 *      not a [SuccessPage] carrying items, otherwise `null`.
 *    - [PaginatorUiState.Content.items] flattens the `data` of every state in
 *      the list.
 *
 * Bookmark-agnostic: classifies the snapshot solely by [PageState] type and
 * data payload, so both offset and cursor paginators reuse this directly.
 *
 * @param isStarted Whether the paginator is currently started. Pass
 *   `paginator.core.isStarted` when mapping from a paginator's snapshot.
 */
fun <T> List<PageState<T>>.toUiState(isStarted: Boolean): PaginatorUiState<T> {
    if (!isStarted || isEmpty()) return PaginatorUiState.Idle
    val snapshot = this

    if (size == 1) {
        val only: PageState<T> = single()
        when {
            only.isProgressState() && only.data.isEmpty() ->
                return PaginatorUiState.Loading(page = only.page)

            only.isEmptyState() ->
                return PaginatorUiState.Empty(page = only.page)

            only.isErrorState() && only.data.isEmpty() ->
                return PaginatorUiState.Error(page = only.page, exception = only.exception)
        }
    }

    val first: PageState<T> = first()
    val last: PageState<T> = last()
    val prependState: PageState<T>? =
        if (first.isSuccessState()) null else first
    val appendState: PageState<T>? =
        if (last.isSuccessState()) null else last

    val items: List<T> = buildList(
        capacity = snapshot.sumOf { it.data.size }
    ) {
        snapshot.forEach { state ->
            addAll(state.data)
        }
    }

    return PaginatorUiState.Content(
        prependState = prependState,
        items = items,
        appendState = appendState,
    )
}

/**
 * Maps a [Flow] of snapshot lists into a [Flow] of [PaginatorUiState].
 *
 * The [isStarted] provider is read on every emission, so its value stays in
 * sync with the emitted list. No `distinctUntilChanged` is applied: `PageState`
 * equality is based on per-instance ids, so consecutive emissions with the
 * same structural content are not considered equal. Apply it yourself if you
 * want deduplication on the derived UI state.
 */
fun <T> Flow<List<PageState<T>>>.asUiState(
    isStarted: () -> Boolean,
): Flow<PaginatorUiState<T>> = map { it.toUiState(isStarted()) }
