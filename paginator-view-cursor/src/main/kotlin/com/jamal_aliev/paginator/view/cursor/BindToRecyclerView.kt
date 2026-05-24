package com.jamal_aliev.paginator.view.cursor

import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.jamal_aliev.paginator.cursor.prefetch.CursorPaginatorPrefetchController
import com.jamal_aliev.paginator.view.cursor.internal.ScrollDispatcher


/**
 * Cursor-paginator counterpart of [PaginatorPrefetchController.bindToRecyclerView].
 *
 * Behaviour, parameters, and constraints are identical — see the page-based overload's KDoc.
 */
public fun CursorPaginatorPrefetchController<*>.bindToRecyclerView(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    dataItemCount: () -> Int,
    headerCount: () -> Int = ZERO,
    footerCount: () -> Int = ZERO,
    scrollSampleMillis: Long = 0L,
): ScrollBinding = bindInternal(
    recyclerView = recyclerView,
    lifecycleOwner = lifecycleOwner,
    dataItemCount = dataItemCount,
    headerCount = headerCount,
    footerCount = footerCount,
    scrollSampleMillis = scrollSampleMillis,
    onScroll = ::onScroll,
)

/**
 * Cursor-paginator [Int]-overload of [bindToRecyclerView]. See the lambda overload's KDoc and
 * the page-based [PaginatorPrefetchController.bindToRecyclerView] for the full contract.
 */
public fun CursorPaginatorPrefetchController<*>.bindToRecyclerView(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    dataItemCount: () -> Int,
    headerCount: Int,
    footerCount: Int = 0,
    scrollSampleMillis: Long = 0L,
): ScrollBinding = bindToRecyclerView(
    recyclerView = recyclerView,
    lifecycleOwner = lifecycleOwner,
    dataItemCount = dataItemCount,
    headerCount = { headerCount },
    footerCount = { footerCount },
    scrollSampleMillis = scrollSampleMillis,
)

private val ZERO: () -> Int = { 0 }

private fun bindInternal(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    dataItemCount: () -> Int,
    headerCount: () -> Int,
    @Suppress("UNUSED_PARAMETER") footerCount: () -> Int,
    scrollSampleMillis: Long,
    onScroll: (firstVisibleIndex: Int, lastVisibleIndex: Int, totalItemCount: Int) -> Unit,
): ScrollBinding {
    val dispatcher = ScrollDispatcher(
        recyclerView = recyclerView,
        lifecycleOwner = lifecycleOwner,
        scrollSampleMillis = scrollSampleMillis,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        onScroll = onScroll,
    )
    dispatcher.start()
    return dispatcher
}
