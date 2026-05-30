package com.jamal_aliev.paginator.compose.cursor

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jamal_aliev.paginator.compose.core.PaginatorInternalApi
import com.jamal_aliev.paginator.compose.core.internal.BindLazyGridScrollPreservation
import com.jamal_aliev.paginator.compose.core.internal.BindScrollInternal
import com.jamal_aliev.paginator.compose.core.internal.ScrollCallback
import com.jamal_aliev.paginator.compose.core.internal.ScrollSignal
import com.jamal_aliev.paginator.compose.core.internal.ScrollSignalReader
import com.jamal_aliev.paginator.cursor.prefetch.CursorPaginatorPrefetchController

@OptIn(PaginatorInternalApi::class)
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
 * and `PaginatorPrefetchController.BindToLazyList` for the full contract, including the
 * `preserveScroll` / `scrollKey` pair.
 */
@OptIn(PaginatorInternalApi::class)
@Composable
fun CursorPaginatorPrefetchController<*, *>.BindToLazyGrid(
    gridState: LazyGridState,
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
        BindLazyGridScrollPreservation(
            gridState = gridState,
            key = scrollKey ?: controller,
            scrollKey = scrollKey,
        )
    }
}
