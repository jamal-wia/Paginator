package com.jamal_aliev.paginator.view.cursor

import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.jamal_aliev.paginator.cursor.CursorPaginator
import com.jamal_aliev.paginator.cursor.prefetch.CursorLoadGuard
import com.jamal_aliev.paginator.cursor.prefetch.CursorPaginatorPrefetchController
import com.jamal_aliev.paginator.core.prefetch.PageLoadGuard
import com.jamal_aliev.paginator.core.prefetch.PrefetchOptions
import com.jamal_aliev.paginator.view.cursor.internal.DataItemCountTracker
import com.jamal_aliev.paginator.view.cursor.internal.ScrollDispatcher


/** Cursor-paginator counterpart of [Paginator.bindPaginated]. */
public fun <T> CursorPaginator<T>.bindPaginated(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    headerCount: () -> Int = { 0 },
    footerCount: () -> Int = { 0 },
    options: PrefetchOptions = PrefetchOptions(),
    enableCacheFlow: Boolean = core.enableCacheFlow,
    loadGuard: CursorLoadGuard<T> = CursorLoadGuard.allowAll(),
    onPrefetchError: ((Exception) -> Unit)? = null,
): PrefetchBinding<CursorPaginatorPrefetchController<T>> {
    val controller = prefetchController(
        lifecycleOwner = lifecycleOwner,
        options = options,
        enableCacheFlow = enableCacheFlow,
        loadGuard = loadGuard,
        onPrefetchError = onPrefetchError,
    )
    return bindPaginatedInternal(
        controller = controller,
        recyclerView = recyclerView,
        lifecycleOwner = lifecycleOwner,
        dataItemCountSource = DataItemCountTracker.forCursorPaginator(this),
        headerCount = headerCount,
        footerCount = footerCount,
        scrollSampleMillis = options.scrollSampleMillis,
        onScroll = controller::onScroll,
    )
}

/** Cursor-paginator [Int]-overload of [bindPaginated]. */
public fun <T> CursorPaginator<T>.bindPaginated(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    headerCount: Int,
    footerCount: Int = 0,
    options: PrefetchOptions = PrefetchOptions(),
    enableCacheFlow: Boolean = core.enableCacheFlow,
    loadGuard: CursorLoadGuard<T> = CursorLoadGuard.allowAll(),
    onPrefetchError: ((Exception) -> Unit)? = null,
): PrefetchBinding<CursorPaginatorPrefetchController<T>> = bindPaginated(
    recyclerView = recyclerView,
    lifecycleOwner = lifecycleOwner,
    headerCount = { headerCount },
    footerCount = { footerCount },
    options = options,
    enableCacheFlow = enableCacheFlow,
    loadGuard = loadGuard,
    onPrefetchError = onPrefetchError,
)

private fun <C : Any> bindPaginatedInternal(
    controller: C,
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    dataItemCountSource: kotlinx.coroutines.flow.Flow<Int>,
    headerCount: () -> Int,
    footerCount: () -> Int,
    scrollSampleMillis: Long,
    onScroll: (firstVisibleIndex: Int, lastVisibleIndex: Int, totalItemCount: Int) -> Unit,
): PrefetchBinding<C> {
    @Suppress("UNUSED_EXPRESSION") footerCount

    lateinit var dispatcher: ScrollDispatcher
    val tracker = DataItemCountTracker(
        source = dataItemCountSource,
        lifecycleOwner = lifecycleOwner,
        onChanged = { dispatcher.recalibrate() },
    )

    dispatcher = ScrollDispatcher(
        recyclerView = recyclerView,
        lifecycleOwner = lifecycleOwner,
        scrollSampleMillis = scrollSampleMillis,
        dataItemCount = tracker.count,
        headerCount = headerCount,
        onScroll = onScroll,
    )
    dispatcher.start()
    tracker.start()

    return PrefetchBinding(controller, dispatcher)
}
