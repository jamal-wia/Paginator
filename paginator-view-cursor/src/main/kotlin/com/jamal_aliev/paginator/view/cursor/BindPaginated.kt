package com.jamal_aliev.paginator.view.cursor

import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.jamal_aliev.paginator.core.prefetch.PrefetchOptions
import com.jamal_aliev.paginator.cursor.CursorPaginator
import com.jamal_aliev.paginator.cursor.prefetch.CursorLoadGuard
import com.jamal_aliev.paginator.cursor.prefetch.CursorPaginatorPrefetchController
import com.jamal_aliev.paginator.view.core.internal.ScrollDispatcher
import com.jamal_aliev.paginator.view.core.internal.attachScrollPreservation
import com.jamal_aliev.paginator.view.cursor.internal.DataItemCountTracker


/** Cursor-paginator counterpart of [Paginator.bindPaginated]. */
public fun <K : Any, T> CursorPaginator<K, T>.bindPaginated(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    headerCount: () -> Int = { 0 },
    footerCount: () -> Int = { 0 },
    options: PrefetchOptions = PrefetchOptions(),
    enableCacheFlow: Boolean = core.enableCacheFlow,
    loadGuard: CursorLoadGuard<K, T> = CursorLoadGuard.allowAll(),
    onPrefetchError: ((Exception) -> Unit)? = null,
    preserveScroll: Boolean = false,
    scrollKey: String? = null,
): PrefetchBinding<CursorPaginatorPrefetchController<K, T>> {
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
        preservationKey = if (preserveScroll) (scrollKey ?: controller) else null,
        scrollKey = if (preserveScroll) scrollKey else null,
    )
}

/** Cursor-paginator [Int]-overload of [bindPaginated]. */
public fun <K : Any, T> CursorPaginator<K, T>.bindPaginated(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    headerCount: Int,
    footerCount: Int = 0,
    options: PrefetchOptions = PrefetchOptions(),
    enableCacheFlow: Boolean = core.enableCacheFlow,
    loadGuard: CursorLoadGuard<K, T> = CursorLoadGuard.allowAll(),
    onPrefetchError: ((Exception) -> Unit)? = null,
    preserveScroll: Boolean = false,
    scrollKey: String? = null,
): PrefetchBinding<CursorPaginatorPrefetchController<K, T>> = bindPaginated(
    recyclerView = recyclerView,
    lifecycleOwner = lifecycleOwner,
    headerCount = { headerCount },
    footerCount = { footerCount },
    options = options,
    enableCacheFlow = enableCacheFlow,
    loadGuard = loadGuard,
    onPrefetchError = onPrefetchError,
    preserveScroll = preserveScroll,
    scrollKey = scrollKey,
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
    preservationKey: Any?,
    scrollKey: String?,
): PrefetchBinding<C> {
    @Suppress("UNUSED_EXPRESSION") footerCount

    val preservationHandle = preservationKey?.let { key ->
        attachScrollPreservation(
            recyclerView = recyclerView,
            lifecycleOwner = lifecycleOwner,
            key = key,
            scrollKey = scrollKey,
        )
    }

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
        preservationHandle = preservationHandle,
    )
    dispatcher.start()
    tracker.start()

    return PrefetchBinding(controller, dispatcher)
}
