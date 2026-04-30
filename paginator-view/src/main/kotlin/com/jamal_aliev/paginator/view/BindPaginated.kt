package com.jamal_aliev.paginator.view

import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.jamal_aliev.paginator.CursorPaginator
import com.jamal_aliev.paginator.Paginator
import com.jamal_aliev.paginator.prefetch.CursorLoadGuard
import com.jamal_aliev.paginator.prefetch.CursorPaginatorPrefetchController
import com.jamal_aliev.paginator.prefetch.PageLoadGuard
import com.jamal_aliev.paginator.prefetch.PaginatorPrefetchController
import com.jamal_aliev.paginator.prefetch.PrefetchOptions
import com.jamal_aliev.paginator.view.internal.DataItemCountTracker
import com.jamal_aliev.paginator.view.internal.ScrollDispatcher

/**
 * Auto-everything entry point for `RecyclerView` pagination — the View counterpart of
 * `paginator-compose`'s `rememberPaginated`.
 *
 * What you get for free:
 *
 * - **`dataItemCount`** is derived from [Paginator.uiState] — only the items count from
 *   `PaginatorUiState.Content` is observed (transient loading / error states do not trigger
 *   re-dispatch).
 * - **Lifecycle-scoped controller** is created against `lifecycleOwner.lifecycleScope` and
 *   cancelled on `ON_DESTROY` (toggleable via [PrefetchOptions.cancelOnDispose]).
 * - **Scroll + layout listeners** are wired automatically; the binding tears itself down on
 *   `ON_DESTROY` and supports manual [ScrollBinding.recalibrate] /
 *   [ScrollBinding.unbind] via the returned handle.
 * - **Partial-first-page handling** out of the box — a freshly loaded first page that's shorter
 *   than the viewport triggers prefetch without requiring the user to scroll.
 *
 * What you still own:
 *
 * - The `RecyclerView.Adapter` (and any `ConcatAdapter` composition with header / footer
 *   adapters) is fully under your control. Pass [headerCount] / [footerCount] lambdas reading
 *   from your adapter setup — they are called on every dispatch, so dynamic header / footer
 *   counts (e.g., a sticky title that appears only after the first page lands) are supported.
 *
 * Pair with your `RecyclerView` setup as you would normally:
 *
 * ```kotlin
 * binding.recyclerView.adapter = ConcatAdapter(headerAdapter, dataAdapter, appendIndicatorAdapter)
 * binding.recyclerView.layoutManager = LinearLayoutManager(context)
 *
 * paginator.bindPaginated(
 *     recyclerView = binding.recyclerView,
 *     lifecycleOwner = viewLifecycleOwner,
 *     headerCount = { headerAdapter.itemCount },
 *     footerCount = { appendIndicatorAdapter.itemCount },
 * )
 * ```
 *
 * @return A [PrefetchBinding] exposing the created [PaginatorPrefetchController] and the
 *   underlying [ScrollBinding] (for `recalibrate()` / `unbind()`).
 */
public fun <T> Paginator<T>.bindPaginated(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    headerCount: () -> Int = { 0 },
    footerCount: () -> Int = { 0 },
    options: PrefetchOptions = PrefetchOptions(),
    enableCacheFlow: Boolean = core.enableCacheFlow,
    loadGuard: PageLoadGuard<T> = PageLoadGuard.allowAll(),
    onPrefetchError: ((Exception) -> Unit)? = null,
): PrefetchBinding<PaginatorPrefetchController<T>> {
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
        dataItemCountSource = DataItemCountTracker.forPaginator(this),
        headerCount = headerCount,
        footerCount = footerCount,
        scrollSampleMillis = options.scrollSampleMillis,
        onScroll = controller::onScroll,
    )
}

/** [Int]-overload of [bindPaginated] for static header/footer layouts. */
public fun <T> Paginator<T>.bindPaginated(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    headerCount: Int,
    footerCount: Int = 0,
    options: PrefetchOptions = PrefetchOptions(),
    enableCacheFlow: Boolean = core.enableCacheFlow,
    loadGuard: PageLoadGuard<T> = PageLoadGuard.allowAll(),
    onPrefetchError: ((Exception) -> Unit)? = null,
): PrefetchBinding<PaginatorPrefetchController<T>> = bindPaginated(
    recyclerView = recyclerView,
    lifecycleOwner = lifecycleOwner,
    headerCount = { headerCount },
    footerCount = { footerCount },
    options = options,
    enableCacheFlow = enableCacheFlow,
    loadGuard = loadGuard,
    onPrefetchError = onPrefetchError,
)

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
