package com.jamal_aliev.paginator.compose

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import com.jamal_aliev.paginator.compose.internal.BindScrollInternal
import com.jamal_aliev.paginator.compose.internal.ScrollSignal
import com.jamal_aliev.paginator.prefetch.CursorPaginatorPrefetchController
import com.jamal_aliev.paginator.prefetch.PaginatorPrefetchController

private fun LazyStaggeredGridState.readScrollSignal(): ScrollSignal {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    var min = Int.MAX_VALUE
    var max = -1
    for (i in visible.indices) {
        val idx = visible[i].index
        if (idx < min) min = idx
        if (idx > max) max = idx
    }
    return ScrollSignal(
        firstVisibleIndex = if (max < 0) -1 else min,
        lastVisibleIndex = max,
        totalItemCount = info.totalItemsCount,
    )
}

/**
 * Binds this [PaginatorPrefetchController] to a [LazyStaggeredGridState] so that scroll-driven
 * prefetch happens automatically.
 *
 * Behaviour, parameters, and constraints mirror the `LazyList` overload — see
 * [PaginatorPrefetchController.BindToLazyList] for the full contract.
 *
 * Staggered grids reorder items across lanes for visual balance, so
 * `firstVisibleItemIndex` / `visibleItemsInfo.last().index` may not be strictly contiguous in
 * the linear data sequence — but the **range** between them still bounds the visible items,
 * which is what the controller's edge-distance check needs.
 */
@Composable
fun PaginatorPrefetchController<*>.BindToLazyStaggeredGrid(
    gridState: LazyStaggeredGridState,
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
        sourceKey = gridState,
        restartKey = restartKey,
        scrollSampleMillis = scrollSampleMillis,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        onScroll = controller::onScroll,
        readSignal = gridState::readScrollSignal,
    )
}

/**
 * Cursor-paginator counterpart of [PaginatorPrefetchController.BindToLazyStaggeredGrid].
 *
 * Behavior, parameters, and constraints are identical — see the page-based overload's KDoc
 * and [PaginatorPrefetchController.BindToLazyList] for the full contract.
 */
@Composable
fun CursorPaginatorPrefetchController<*>.BindToLazyStaggeredGrid(
    gridState: LazyStaggeredGridState,
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
        sourceKey = gridState,
        restartKey = restartKey,
        scrollSampleMillis = scrollSampleMillis,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        onScroll = controller::onScroll,
        readSignal = gridState::readScrollSignal,
    )
}
