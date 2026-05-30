package com.jamal_aliev.paginator.view.cursor

import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.jamal_aliev.paginator.cursor.prefetch.CursorPaginatorPrefetchController
import com.jamal_aliev.paginator.view.core.ScrollBinding
import com.jamal_aliev.paginator.view.core.internal.ScrollDispatcher
import com.jamal_aliev.paginator.view.core.internal.attachScrollPreservation


/**
 * Cursor-paginator counterpart of [PaginatorPrefetchController.bindToRecyclerView].
 *
 * Behaviour, parameters, and constraints are identical — see the page-based overload's KDoc,
 * including the `preserveScroll` / `scrollKey` pair.
 */
public fun CursorPaginatorPrefetchController<*, *>.bindToRecyclerView(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    dataItemCount: () -> Int,
    headerCount: () -> Int = ZERO,
    footerCount: () -> Int = ZERO,
    scrollSampleMillis: Long = 0L,
    preserveScroll: Boolean = false,
    scrollKey: String? = null,
): ScrollBinding = bindInternal(
    recyclerView = recyclerView,
    lifecycleOwner = lifecycleOwner,
    dataItemCount = dataItemCount,
    headerCount = headerCount,
    footerCount = footerCount,
    scrollSampleMillis = scrollSampleMillis,
    onScroll = ::onScroll,
    preservationKey = if (preserveScroll) (scrollKey ?: this) else null,
    scrollKey = if (preserveScroll) scrollKey else null,
)

/**
 * Cursor-paginator [Int]-overload of [bindToRecyclerView]. See the lambda overload's KDoc and
 * the page-based [PaginatorPrefetchController.bindToRecyclerView] for the full contract.
 */
public fun CursorPaginatorPrefetchController<*, *>.bindToRecyclerView(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    dataItemCount: () -> Int,
    headerCount: Int,
    footerCount: Int = 0,
    scrollSampleMillis: Long = 0L,
    preserveScroll: Boolean = false,
    scrollKey: String? = null,
): ScrollBinding = bindToRecyclerView(
    recyclerView = recyclerView,
    lifecycleOwner = lifecycleOwner,
    dataItemCount = dataItemCount,
    headerCount = { headerCount },
    footerCount = { footerCount },
    scrollSampleMillis = scrollSampleMillis,
    preserveScroll = preserveScroll,
    scrollKey = scrollKey,
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
    preservationKey: Any?,
    scrollKey: String?,
): ScrollBinding {
    val preservationHandle = preservationKey?.let { key ->
        attachScrollPreservation(
            recyclerView = recyclerView,
            lifecycleOwner = lifecycleOwner,
            key = key,
            scrollKey = scrollKey,
        )
    }
    val dispatcher = ScrollDispatcher(
        recyclerView = recyclerView,
        lifecycleOwner = lifecycleOwner,
        scrollSampleMillis = scrollSampleMillis,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        onScroll = onScroll,
        preservationHandle = preservationHandle,
    )
    dispatcher.start()
    return dispatcher
}
