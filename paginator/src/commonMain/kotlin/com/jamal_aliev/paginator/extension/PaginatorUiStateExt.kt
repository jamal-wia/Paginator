package com.jamal_aliev.paginator.extension

import com.jamal_aliev.paginator.Paginator
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PageState.EmptyPage
import com.jamal_aliev.paginator.page.PageState.ErrorPage
import com.jamal_aliev.paginator.page.PageState.ProgressPage
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import com.jamal_aliev.paginator.page.PaginatorUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Maps a snapshot list of [PageState] into a high-level [PaginatorUiState].
 *
 * Classification rules (checked in order):
 * 1. If [isStarted] is `false` or the list is empty → [PaginatorUiState.Idle].
 * 2. If the list has exactly one element:
 *    - [ProgressPage] with empty data → [PaginatorUiState.Loading].
 *    - [EmptyPage] with empty data → [PaginatorUiState.Empty].
 *    - [ErrorPage] with empty data → [PaginatorUiState.Error].
 *    - Otherwise falls through to [PaginatorUiState.Content] (see below).
 * 3. Everything else → [PaginatorUiState.Content], where:
 *    - [PaginatorUiState.Content.prependState] is the first element when it is
 *      not a [SuccessPage], otherwise `null`.
 *    - [PaginatorUiState.Content.appendState] is the last element when it is
 *      not a [SuccessPage], otherwise `null`.
 *    - [PaginatorUiState.Content.items] flattens the `data` of every state in
 *      the list. This covers data carried into [ProgressPage] / [ErrorPage] by
 *      the paginator when a position is reloaded: the `loading` callback in
 *      [Paginator.goNextPage] / [Paginator.goPreviousPage] / [Paginator.jump]
 *      threads the previous `pageState?.data` into the replacement
 *      [ProgressPage], and the catch branch of [Paginator.loadOrGetPageState]
 *      threads the same `cachedState?.data` into the fallback [ErrorPage]. For
 *      example, when [Paginator.goNextPage] encounters a partially-filled
 *      [SuccessPage] at the end of the context window, the paginator reloads
 *      that position, and during the in-flight load (and on failure) the
 *      previously loaded items stay visible through
 *      [PaginatorUiState.Content.items]. An [EmptyPage] normally carries empty
 *      `data` and therefore contributes nothing on its own.
 *
 * The single-element branches for [PaginatorUiState.Loading],
 * [PaginatorUiState.Empty] and [PaginatorUiState.Error] explicitly check
 * `data.isEmpty()` so that a state whose `data` happens to be non-empty (for
 * example during a reload, or an [EmptyPage] whose `data` bypasses the
 * [PageState.SuccessPage] `checkData` invariant) falls through to
 * [PaginatorUiState.Content] instead of hiding already-visible items behind a
 * full-screen indicator.
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

            only.isEmptyState() && only.data.isEmpty() ->
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
        items = items,
        prependState = prependState,
        appendState = appendState,
    )
}

/**
 * Maps a [Flow] of snapshot lists into a [Flow] of [PaginatorUiState].
 *
 * The [isStarted] provider is read on every emission, so its value stays in
 * sync with the emitted list. When mapping from a [Paginator]'s snapshot,
 * pass `{ paginator.core.isStarted }` — reads and writes of context pages
 * (which drive `isStarted`) are serialized through the navigation mutex, and
 * the snapshot is emitted after the context is updated, so the flag is
 * consistent with the emitted list.
 *
 * No [kotlinx.coroutines.flow.distinctUntilChanged] is applied: `PageState`
 * equality is based on per-instance ids, so consecutive emissions with the
 * same structural content are not considered equal. Apply it yourself if you
 * want deduplication on the derived UI state (which does have structural
 * equality via data classes).
 */
fun <T> Flow<List<PageState<T>>>.asUiState(
    isStarted: () -> Boolean,
): Flow<PaginatorUiState<T>> = map { it.toUiState(isStarted()) }

/**
 * A [Flow] of high-level [PaginatorUiState] derived from this [Paginator]'s
 * visible snapshot.
 *
 * Equivalent to `core.snapshot.asUiState { core.isStarted }`. Collect this from
 * your UI layer instead of the raw snapshot when you only need full-screen
 * Idle/Loading/Empty/Error/Content states and boundary indicators for
 * pagination.
 *
 * @see asUiState
 * @see toUiState
 */
val <T> Paginator<T>.uiState: Flow<PaginatorUiState<T>>
    get() = core.snapshot.asUiState(isStarted = { core.isStarted })
