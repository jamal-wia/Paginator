package com.jamal_aliev.paginator.compose.offset

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jamal_aliev.paginator.compose.core.PaginatorInternalApi
import com.jamal_aliev.paginator.compose.core.internal.BindLazyStaggeredGridScrollPreservation
import com.jamal_aliev.paginator.compose.core.internal.BindScrollInternal
import com.jamal_aliev.paginator.compose.core.internal.ScrollCallback
import com.jamal_aliev.paginator.compose.core.internal.ScrollSignal
import com.jamal_aliev.paginator.compose.core.internal.ScrollSignalReader
import com.jamal_aliev.paginator.offset.prefetch.PaginatorPrefetchController

@OptIn(PaginatorInternalApi::class)
private fun LazyStaggeredGridState.readScrollSignal(): ScrollSignal {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    val size = visible.size
    if (size == 0) {
        return ScrollSignal(
            firstVisibleIndex = -1,
            lastVisibleIndex = -1,
        )
    }
    var min = Int.MAX_VALUE
    var max = -1
    var i = 0
    while (i < size) {
        val idx = visible[i].index
        if (idx < min) min = idx
        if (idx > max) max = idx
        i++
    }
    return ScrollSignal(
        firstVisibleIndex = min,
        lastVisibleIndex = max,
    )
}

/**
 * Binds this [PaginatorPrefetchController] to a [LazyStaggeredGridState] so that scroll-driven
 * prefetch happens automatically.
 *
 * Behavior, parameters, and constraints mirror the `LazyList` overload — see
 * [PaginatorPrefetchController.BindToLazyList] for the full contract.
 *
 * Staggered grids reorder items across lanes for visual balance, so
 * `firstVisibleItemIndex` / `visibleItemsInfo.last().index` may not be strictly contiguous in
 * the linear data sequence — but the **range** between them still bounds the visible items,
 * which is what the controller's edge-distance check needs.
 */
@OptIn(PaginatorInternalApi::class)
@Composable
fun PaginatorPrefetchController<*>.BindToLazyStaggeredGrid(
    gridState: LazyStaggeredGridState,
    dataItemCount: Int,
    headerCount: Int = 0,
    @Suppress("UNUSED_PARAMETER") footerCount: Int = 0,
    restartKey: Any? = null,
    scrollSampleMillis: Long = 0L,
    preserveScroll: Boolean = false,
    scrollKey: String? = null,
) {
    val controller = this
    val reader = remember(gridState) { ScrollSignalReader { gridState.readScrollSignal() } }
    val callback = remember(controller) {
        ScrollCallback { f, l, t -> controller.onScroll(f, l, t) }
    }
    BindScrollInternal(
        controllerKey = controller,
        sourceKey = gridState,
        restartKey = restartKey,
        scrollSampleMillis = scrollSampleMillis,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        reader = reader,
        callback = callback,
    )
    if (preserveScroll) {
        BindLazyStaggeredGridScrollPreservation(
            gridState = gridState,
            key = scrollKey ?: controller,
            scrollKey = scrollKey,
        )
    }
}
