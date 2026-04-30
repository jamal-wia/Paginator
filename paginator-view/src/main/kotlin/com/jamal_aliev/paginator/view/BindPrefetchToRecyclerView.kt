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

/**
 * Binding handle returned by [bindPrefetchToRecyclerView] / [bindPaginated]: bundles the
 * created [PaginatorPrefetchController] with the [ScrollBinding] that wires it to a
 * [RecyclerView]. The `ScrollBinding` interface is delegated, so call sites can use this object
 * as a `ScrollBinding` directly while keeping access to the underlying [controller].
 */
public class PrefetchBinding<C> internal constructor(
    public val controller: C,
    private val binding: ScrollBinding,
) : ScrollBinding by binding

/**
 * One-call helper that creates a lifecycle-scoped [PaginatorPrefetchController] and binds it to
 * [recyclerView] in a single call. Combines [Paginator.prefetchController] (lifecycle-aware
 * factory) with [PaginatorPrefetchController.bindToRecyclerView] under one entry point.
 *
 * Use this when prefetch should live for the lifetime of the screen and the controller does
 * not need to be referenced elsewhere. For a `ViewModel`-scoped controller, build the
 * controller in the view-model and call `controller.bindToRecyclerView(...)` directly.
 *
 * Equivalent to the `paginator-compose` `PrefetchOnScroll(state, dataItemCount, …)` composable.
 *
 * @return A [PrefetchBinding] exposing both the [PaginatorPrefetchController] (`controller`)
 *   and the [ScrollBinding] handle (via delegation — call `recalibrate()` / `unbind()`
 *   directly on the result).
 */
public fun <T> Paginator<T>.bindPrefetchToRecyclerView(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    dataItemCount: () -> Int,
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
    val binding = controller.bindToRecyclerView(
        recyclerView = recyclerView,
        lifecycleOwner = lifecycleOwner,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        footerCount = footerCount,
        scrollSampleMillis = options.scrollSampleMillis,
    )
    return PrefetchBinding(controller, binding)
}

/** [Int]-overload of [bindPrefetchToRecyclerView] for static header/footer layouts. */
public fun <T> Paginator<T>.bindPrefetchToRecyclerView(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    dataItemCount: () -> Int,
    headerCount: Int,
    footerCount: Int = 0,
    options: PrefetchOptions = PrefetchOptions(),
    enableCacheFlow: Boolean = core.enableCacheFlow,
    loadGuard: PageLoadGuard<T> = PageLoadGuard.allowAll(),
    onPrefetchError: ((Exception) -> Unit)? = null,
): PrefetchBinding<PaginatorPrefetchController<T>> = bindPrefetchToRecyclerView(
    recyclerView = recyclerView,
    lifecycleOwner = lifecycleOwner,
    dataItemCount = dataItemCount,
    headerCount = { headerCount },
    footerCount = { footerCount },
    options = options,
    enableCacheFlow = enableCacheFlow,
    loadGuard = loadGuard,
    onPrefetchError = onPrefetchError,
)

/** Cursor-paginator counterpart of [Paginator.bindPrefetchToRecyclerView]. */
public fun <T> CursorPaginator<T>.bindPrefetchToRecyclerView(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    dataItemCount: () -> Int,
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
    val binding = controller.bindToRecyclerView(
        recyclerView = recyclerView,
        lifecycleOwner = lifecycleOwner,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        footerCount = footerCount,
        scrollSampleMillis = options.scrollSampleMillis,
    )
    return PrefetchBinding(controller, binding)
}

/** Cursor-paginator [Int]-overload of [bindPrefetchToRecyclerView]. */
public fun <T> CursorPaginator<T>.bindPrefetchToRecyclerView(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    dataItemCount: () -> Int,
    headerCount: Int,
    footerCount: Int = 0,
    options: PrefetchOptions = PrefetchOptions(),
    enableCacheFlow: Boolean = core.enableCacheFlow,
    loadGuard: CursorLoadGuard<T> = CursorLoadGuard.allowAll(),
    onPrefetchError: ((Exception) -> Unit)? = null,
): PrefetchBinding<CursorPaginatorPrefetchController<T>> = bindPrefetchToRecyclerView(
    recyclerView = recyclerView,
    lifecycleOwner = lifecycleOwner,
    dataItemCount = dataItemCount,
    headerCount = { headerCount },
    footerCount = { footerCount },
    options = options,
    enableCacheFlow = enableCacheFlow,
    loadGuard = loadGuard,
    onPrefetchError = onPrefetchError,
)
