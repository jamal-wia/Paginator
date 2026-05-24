package com.jamal_aliev.paginator.compose.cursor

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jamal_aliev.paginator.compose.cursor.internal.BindScrollInternal
import com.jamal_aliev.paginator.compose.cursor.internal.ScrollCallback
import com.jamal_aliev.paginator.compose.cursor.internal.ScrollSignal
import com.jamal_aliev.paginator.compose.cursor.internal.ScrollSignalReader
import com.jamal_aliev.paginator.cursor.prefetch.CursorPaginatorPrefetchController

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
 * Cursor-paginator counterpart of `PaginatorPrefetchController.BindToLazyList`.
 *
 * Behavior, parameters, and constraints are identical — see the page-based overload in the
 * offset module for the full contract.
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
