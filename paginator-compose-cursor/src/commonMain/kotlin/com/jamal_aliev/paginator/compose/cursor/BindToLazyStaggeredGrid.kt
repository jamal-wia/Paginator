package com.jamal_aliev.paginator.compose.cursor

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jamal_aliev.paginator.compose.cursor.internal.BindScrollInternal
import com.jamal_aliev.paginator.compose.cursor.internal.ScrollCallback
import com.jamal_aliev.paginator.compose.cursor.internal.ScrollSignal
import com.jamal_aliev.paginator.compose.cursor.internal.ScrollSignalReader
import com.jamal_aliev.paginator.cursor.prefetch.CursorPaginatorPrefetchController

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
 * Cursor-paginator counterpart of `PaginatorPrefetchController.BindToLazyStaggeredGrid`.
 *
 * Behavior, parameters, and constraints are identical — see the page-based overload in the
 * offset module for the full contract.
 */
@Composable
fun CursorPaginatorPrefetchController<*>.BindToLazyStaggeredGrid(
    gridState: LazyStaggeredGridState,
    dataItemCount: Int,
    headerCount: Int = 0,
    @Suppress("UNUSED_PARAMETER") footerCount: Int = 0,
    restartKey: Any? = null,
    scrollSampleMillis: Long = 0L,
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
}
