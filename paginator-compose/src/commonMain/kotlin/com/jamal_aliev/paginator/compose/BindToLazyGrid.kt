package com.jamal_aliev.paginator.compose

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import com.jamal_aliev.paginator.compose.internal.BindScrollInternal
import com.jamal_aliev.paginator.compose.internal.ScrollSignal
import com.jamal_aliev.paginator.prefetch.CursorPaginatorPrefetchController
import com.jamal_aliev.paginator.prefetch.PaginatorPrefetchController

private fun LazyGridState.readScrollSignal(): ScrollSignal {
    val info = layoutInfo
    return ScrollSignal(
        firstVisibleIndex = firstVisibleItemIndex,
        lastVisibleIndex = info.visibleItemsInfo.lastOrNull()?.index ?: -1,
        totalItemCount = info.totalItemsCount,
    )
}

/**
 * Binds this [PaginatorPrefetchController] to a [LazyGridState] so that scroll-driven prefetch
 * happens automatically.
 *
 * Behaviour, parameters, and constraints mirror the `LazyList` overload — see
 * [PaginatorPrefetchController.BindToLazyList] for the full contract. The only difference is
 * the source of the scroll signal: `LazyGridState.firstVisibleItemIndex` /
 * `layoutInfo.visibleItemsInfo.lastOrNull()?.index` / `layoutInfo.totalItemsCount` (which are
 * **item** indices, not row indices — exactly what the controller expects when [dataItemCount]
 * is also expressed in items).
 *
 * Note that grid headers spanning the full width still count as **one item each** for index
 * purposes (use `GridItemSpan(maxLineSpan)` in your DSL). Pass that count via [headerCount].
 */
@Composable
fun PaginatorPrefetchController<*>.BindToLazyGrid(
    gridState: LazyGridState,
    dataItemCount: Int,
    headerCount: Int = 0,
    footerCount: Int = 0,
) {
    @Suppress("UNUSED_EXPRESSION") footerCount
    val controller = this
    BindScrollInternal(
        controllerKey = controller,
        sourceKey = gridState,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        onScroll = controller::onScroll,
        readSignal = gridState::readScrollSignal,
    )
}

/**
 * Cursor-paginator counterpart of [PaginatorPrefetchController.BindToLazyGrid].
 *
 * Behaviour, parameters, and constraints are identical — see the page-based overload's KDoc
 * and [PaginatorPrefetchController.BindToLazyList] for the full contract.
 */
@Composable
fun CursorPaginatorPrefetchController<*>.BindToLazyGrid(
    gridState: LazyGridState,
    dataItemCount: Int,
    headerCount: Int = 0,
    footerCount: Int = 0,
) {
    @Suppress("UNUSED_EXPRESSION") footerCount
    val controller = this
    BindScrollInternal(
        controllerKey = controller,
        sourceKey = gridState,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        onScroll = controller::onScroll,
        readSignal = gridState::readScrollSignal,
    )
}
