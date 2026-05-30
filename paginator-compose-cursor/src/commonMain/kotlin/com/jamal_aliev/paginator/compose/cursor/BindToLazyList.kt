package com.jamal_aliev.paginator.compose.cursor

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jamal_aliev.paginator.compose.core.PaginatorInternalApi
import com.jamal_aliev.paginator.compose.core.internal.BindLazyListScrollPreservation
import com.jamal_aliev.paginator.compose.core.internal.BindScrollInternal
import com.jamal_aliev.paginator.compose.core.internal.ScrollCallback
import com.jamal_aliev.paginator.compose.core.internal.ScrollSignal
import com.jamal_aliev.paginator.compose.core.internal.ScrollSignalReader
import com.jamal_aliev.paginator.cursor.prefetch.CursorPaginatorPrefetchController

@OptIn(PaginatorInternalApi::class)
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
 * offset module for the full contract, including the `preserveScroll` / `scrollKey` pair.
 */
@OptIn(PaginatorInternalApi::class)
@Composable
fun CursorPaginatorPrefetchController<*, *>.BindToLazyList(
    listState: LazyListState,
    dataItemCount: Int,
    headerCount: Int = 0,
    @Suppress("UNUSED_PARAMETER") footerCount: Int = 0,
    restartKey: Any? = null,
    scrollSampleMillis: Long = 0L,
    preserveScroll: Boolean = false,
    scrollKey: String? = null,
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
    if (preserveScroll) {
        BindLazyListScrollPreservation(
            listState = listState,
            key = scrollKey ?: controller,
            scrollKey = scrollKey,
        )
    }
}
