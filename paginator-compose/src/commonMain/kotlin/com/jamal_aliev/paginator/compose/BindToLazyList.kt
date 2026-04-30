package com.jamal_aliev.paginator.compose

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import com.jamal_aliev.paginator.compose.internal.BindScrollInternal
import com.jamal_aliev.paginator.compose.internal.ScrollSignal
import com.jamal_aliev.paginator.prefetch.CursorPaginatorPrefetchController
import com.jamal_aliev.paginator.prefetch.PaginatorPrefetchController

private fun LazyListState.readScrollSignal(): ScrollSignal {
    val info = layoutInfo
    return ScrollSignal(
        firstVisibleIndex = firstVisibleItemIndex,
        lastVisibleIndex = info.visibleItemsInfo.lastOrNull()?.index ?: -1,
        totalItemCount = info.totalItemsCount,
    )
}

/**
 * Binds this [PaginatorPrefetchController] to a [LazyListState] so that scroll-driven prefetch
 * happens automatically — without manually wiring `LaunchedEffect` / `snapshotFlow` /
 * `onScroll(...)` the way the platform-agnostic guide in `docs/7. prefetch.md` demonstrates.
 *
 * The binding observes [listState] through `snapshotFlow` and calls
 * [PaginatorPrefetchController.onScroll] with **data-only** indices: any [headerCount] is
 * subtracted from the visible-index range, the last index is clamped to `dataItemCount - 1`,
 * and `totalItemCount` is replaced with [dataItemCount]. This is what the controller's contract
 * requires (see the warning in `docs/7. prefetch.md`: *"`totalItemCount` must reflect only data
 * items"*) and getting it wrong is a common source of misfires.
 *
 * If the visible viewport sits entirely inside headers (the user hasn't scrolled into data yet)
 * or entirely inside footers, the binding skips the call. The controller's first real call
 * still acts as calibration as documented.
 *
 * Recompositions with new [dataItemCount] / [headerCount] / [footerCount] do **not** restart
 * the underlying flow — the values are read fresh on every emission, so a newly loaded page
 * does not trigger re-calibration. The flow restarts only when the controller identity or the
 * [listState] instance changes.
 *
 * Typical usage:
 * ```
 * val listState = rememberLazyListState()
 * val prefetch = remember(viewModel) {
 *     viewModel.paginator.prefetchController(scope = viewModelScope, prefetchDistance = 10)
 * }
 *
 * prefetch.BindToLazyList(
 *     listState = listState,
 *     dataItemCount = uiState.dataItemCount,
 *     headerCount = 1,   // sticky title item
 *     footerCount = 1,   // append-loader item
 * )
 *
 * LazyColumn(state = listState) {
 *     item { Header() }
 *     items(uiState.items, key = { it.id }) { Row(it) }
 *     item { AppendIndicator(uiState.appendState) }
 * }
 * ```
 *
 * @param listState The Compose lazy-list state to observe.
 * @param dataItemCount The number of **data** items currently rendered (excluding headers,
 *   footers, dividers, sticky labels, and loading indicators). For a [PaginatorPrefetchController]
 *   driven from `paginator.uiState`, this is the size of `PaginatorUiState.Content.items` (or `0`
 *   for any non-`Content` state).
 * @param headerCount Number of non-data items rendered **before** the data range. Used to shift
 *   the visible-index window down to the data origin.
 * @param footerCount Number of non-data items rendered **after** the data range. Currently
 *   accepted for self-documentation (it makes the layout intent explicit at the call site);
 *   the math relies on [dataItemCount] and [headerCount], so changing [footerCount] alone has
 *   no behavioural effect.
 */
@Composable
fun PaginatorPrefetchController<*>.BindToLazyList(
    listState: LazyListState,
    dataItemCount: Int,
    headerCount: Int = 0,
    footerCount: Int = 0,
    restartKey: Any? = null,
    scrollSampleMillis: Long = 0L,
) {
    @Suppress("UNUSED_EXPRESSION") footerCount
    val controller = this
    BindScrollInternal(
        controllerKey = controller,
        sourceKey = listState,
        restartKey = restartKey,
        scrollSampleMillis = scrollSampleMillis,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        onScroll = controller::onScroll,
        readSignal = listState::readScrollSignal,
    )
}

/**
 * Cursor-paginator counterpart of [PaginatorPrefetchController.BindToLazyList].
 *
 * Behaviour, parameters, and constraints are identical — see the page-based overload's KDoc.
 */
@Composable
fun CursorPaginatorPrefetchController<*>.BindToLazyList(
    listState: LazyListState,
    dataItemCount: Int,
    headerCount: Int = 0,
    footerCount: Int = 0,
    restartKey: Any? = null,
    scrollSampleMillis: Long = 0L,
) {
    @Suppress("UNUSED_EXPRESSION") footerCount
    val controller = this
    BindScrollInternal(
        controllerKey = controller,
        sourceKey = listState,
        restartKey = restartKey,
        scrollSampleMillis = scrollSampleMillis,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        onScroll = controller::onScroll,
        readSignal = listState::readScrollSignal,
    )
}
