package com.jamal_aliev.paginator.page

import com.jamal_aliev.paginator.Paginator
import com.jamal_aliev.paginator.page.PageState.ErrorPage
import com.jamal_aliev.paginator.page.PageState.ProgressPage
import com.jamal_aliev.paginator.page.PageState.SuccessPage

/**
 * A high-level UI state derived from a [Paginator]'s visible page snapshot.
 *
 * [PaginatorUiState] collapses a `List<PageState<T>>` into a small set of UI-friendly
 * states that map cleanly onto typical screens (full-screen loader, empty state,
 * error state, and content with optional boundary indicators).
 *
 * Use this instead of subscribing to the raw snapshot when you do not need to
 * differentiate per-page states on the UI.
 *
 * **State transitions (typical flow):**
 * 1. [Idle] — before the first navigation call or after [Paginator.release].
 * 2. [Loading] — first `jump` / `restart` starts loading from an empty cache.
 * 3. [Empty] — load finished successfully but returned no data.
 * 4. [Error] — first load threw and there was no previously cached data.
 * 5. [Content] — at least one [SuccessPage] is visible. Boundary activity
 *    (refresh, pagination, failure on next/previous page) is reported via
 *    [Content.prependState] / [Content.appendState].
 *
 * @see com.jamal_aliev.paginator.extension.toUiState
 * @see com.jamal_aliev.paginator.extension.asUiState
 * @see com.jamal_aliev.paginator.extension.uiState
 */
sealed interface PaginatorUiState<out T> {

    /**
     * The paginator has not been started yet, or has just been released.
     *
     * This corresponds to `!paginator.core.isStarted`. Subscribers to
     * [Paginator.core]'s `snapshot` receive this state on the first
     * subscription and after [Paginator.release].
     */
    data object Idle : PaginatorUiState<Nothing>

    /**
     * A single [ProgressPage] with empty data is visible.
     *
     * Indicates a full-screen loading UI should be shown. Typically emitted on the
     * very first `jump` / `restart` when there is no previously cached data.
     *
     * @property page The page number being loaded.
     */
    data class Loading(val page: Int) : PaginatorUiState<Nothing>

    /**
     * A single [SuccessPage] with empty data is visible.
     *
     * Indicates the load finished successfully but returned no data,
     * so a "no results" UI should be shown.
     *
     * @property page The page number that returned no data.
     */
    data class Empty(val page: Int) : PaginatorUiState<Nothing>

    /**
     * A single [ErrorPage] with empty data is visible.
     *
     * Indicates the load failed and there was no previously cached data,
     * so a full-screen error UI should be shown.
     *
     * @property page The page number whose load failed.
     * @property exception The exception that caused the failure.
     */
    data class Error(
        val page: Int,
        val exception: Exception,
    ) : PaginatorUiState<Nothing>

    /**
     * At least one visible page contributes data, optionally flanked by non-success
     * boundary states on either end.
     *
     * Boundary states indicate pagination activity (loading more / failed to load)
     * at the top or bottom of the visible range:
     * - [prependState] — the first page in the visible snapshot when it is **not**
     *   a [SuccessPage]; otherwise `null`.
     * - [appendState] — the last page in the visible snapshot when it is **not**
     *   a [SuccessPage]; otherwise `null`.
     *
     * The [items] list contains the flattened `data` of every visible page.
     * This includes data the paginator forwards into a [ProgressPage] /
     * [ErrorPage] when a position is reloaded: if `goNextPage` /
     * `goPreviousPage` / `jump` / `refresh` hit a partially-filled
     * [SuccessPage], the paginator reloads that position and threads the
     * previous data into the replacement [ProgressPage] (and, on failure, into
     * the [ErrorPage]) so the UI keeps showing the last known items while the
     * load is in flight or has failed. Non-success boundary states at the top
     * and bottom of the snapshot are therefore represented twice: their carried
     * items flow into [items], and the state object itself is exposed via
     * [prependState] / [appendState] so the UI can render a "refreshing" or
     * "retry" indicator alongside the items. A [SuccessPage] with empty data
     * contributes nothing to [items].
     *
     * @property items Flattened data from every visible page, in snapshot order.
     * @property prependState The non-success state at the top of the visible
     *   snapshot (e.g. loading-previous / previous-load-failed), or `null` if the
     *   top is a [SuccessPage].
     * @property appendState The non-success state at the bottom of the visible
     *   snapshot (e.g. loading-next / next-load-failed / reload-of-partial-page),
     *   or `null` if the bottom is a [SuccessPage].
     */
    data class Content<T>(
        val prependState: PageState<T>?,
        val items: List<T>,
        val appendState: PageState<T>?,
    ) : PaginatorUiState<T>
}
