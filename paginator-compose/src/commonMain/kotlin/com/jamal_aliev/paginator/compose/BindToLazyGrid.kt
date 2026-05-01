package com.jamal_aliev.paginator.compose

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jamal_aliev.paginator.compose.internal.BindScrollInternal
import com.jamal_aliev.paginator.compose.internal.ScrollCallback
import com.jamal_aliev.paginator.compose.internal.ScrollSignal
import com.jamal_aliev.paginator.compose.internal.ScrollSignalReader
import com.jamal_aliev.paginator.prefetch.CursorPaginatorPrefetchController
import com.jamal_aliev.paginator.prefetch.PaginatorPrefetchController

private fun LazyGridState.readScrollSignal(): ScrollSignal {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    val lastIndex = if (visible.isEmpty()) -1 else visible[visible.size - 1].index
    return ScrollSignal(
        firstVisibleIndex = firstVisibleItemIndex,
        lastVisibleIndex = lastIndex,
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
 * `layoutInfo.visibleItemsInfo.last().index` / `layoutInfo.totalItemsCount` (which are
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

/**
 * Cursor-paginator counterpart of [PaginatorPrefetchController.BindToLazyGrid].
 *
 * Behavior, parameters, and constraints are identical — see the page-based overload's KDoc
 * and [PaginatorPrefetchController.BindToLazyList] for the full contract.
 */
@Composable
fun CursorPaginatorPrefetchController<*>.BindToLazyGrid(
    gridState: LazyGridState,
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
