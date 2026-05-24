package com.jamal_aliev.paginator.compose.cursor

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jamal_aliev.paginator.compose.cursor.internal.BindScrollInternal
import com.jamal_aliev.paginator.compose.cursor.internal.ScrollCallback
import com.jamal_aliev.paginator.compose.cursor.internal.ScrollSignal
import com.jamal_aliev.paginator.compose.cursor.internal.ScrollSignalReader
import com.jamal_aliev.paginator.cursor.prefetch.CursorPaginatorPrefetchController

private fun LazyGridState.readScrollSignal(): ScrollSignal {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    val lastIndex = if (visible.isEmpty()) -1 else visible[visible.size - 1].index
    return ScrollSignal(
        firstVisibleIndex = firstVisibleItemIndex,
        lastVisibleIndex = lastIndex,
    )
}

/**
 * Cursor-paginator counterpart of `PaginatorPrefetchController.BindToLazyGrid`.
 *
 * Behavior, parameters, and constraints are identical — see the page-based overload's KDoc
 * and `PaginatorPrefetchController.BindToLazyList` for the full contract.
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
