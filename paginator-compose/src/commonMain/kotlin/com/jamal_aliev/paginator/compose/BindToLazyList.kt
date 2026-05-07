package com.jamal_aliev.paginator.compose

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jamal_aliev.paginator.compose.internal.BindScrollInternal
import com.jamal_aliev.paginator.compose.internal.ScrollCallback
import com.jamal_aliev.paginator.compose.internal.ScrollSignal
import com.jamal_aliev.paginator.compose.internal.ScrollSignalReader
import com.jamal_aliev.paginator.prefetch.CursorPaginatorPrefetchController
import com.jamal_aliev.paginator.prefetch.PaginatorPrefetchController

private fun LazyListState.readScrollSignal(): ScrollSignal {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    val lastIndex = if (visible.isEmpty()) -1 else visible[visible.size - 1].index
    return ScrollSignal(
        firstVisibleIndex = firstVisibleItemIndex,
        lastVisibleIndex = lastIndex,
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
 * and `totalItemCount` forwarded to the controller equals [dataItemCount]. This is what the
 * controller's contract requires and getting it wrong is a common source of misfires.
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
 * @param footerCount Reserved for self-documentation at the call site; the math relies on
 *   [dataItemCount] and [headerCount] only.
 */
@Composable
fun PaginatorPrefetchController<*>.BindToLazyList(
    listState: LazyListState,
    dataItemCount: Int,
    headerCount: Int = 0,
    @Suppress("UNUSED_PARAMETER") footerCount: Int = 0,
    restartKey: Any? = null,
    scrollSampleMillis: Long = 0L,
) {
    val controller = this
    val reader = remember(listState) { ScrollSignalReader { listState.readScrollSignal() } }
    val callback = remember(controller) {
        ScrollCallback { f, l, t -> controller.onScroll(f, l, t) }
    }
    BindScrollInternal(
        controllerKey = controller,
        sourceKey = listState,
        restartKey = restartKey,
        scrollSampleMillis = scrollSampleMillis,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        reader = reader,
        callback = callback,
    )
}

/**
 * Cursor-paginator counterpart of [PaginatorPrefetchController.BindToLazyList].
 *
 * Behavior, parameters, and constraints are identical — see the page-based overload's KDoc.
 */
@Composable
fun CursorPaginatorPrefetchController<*>.BindToLazyList(
    listState: LazyListState,
    dataItemCount: Int,
    headerCount: Int = 0,
    @Suppress("UNUSED_PARAMETER") footerCount: Int = 0,
    restartKey: Any? = null,
    scrollSampleMillis: Long = 0L,
) {
    val controller = this
    val reader = remember(listState) { ScrollSignalReader { listState.readScrollSignal() } }
    val callback = remember(controller) {
        ScrollCallback { f, l, t -> controller.onScroll(f, l, t) }
    }
    BindScrollInternal(
        controllerKey = controller,
        sourceKey = listState,
        restartKey = restartKey,
        scrollSampleMillis = scrollSampleMillis,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        reader = reader,
        callback = callback,
    )
}
