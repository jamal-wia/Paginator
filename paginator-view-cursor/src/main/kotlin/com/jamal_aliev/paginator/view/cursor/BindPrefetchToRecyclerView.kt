package com.jamal_aliev.paginator.view.cursor

import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.jamal_aliev.paginator.core.prefetch.PrefetchOptions
import com.jamal_aliev.paginator.cursor.CursorPaginator
import com.jamal_aliev.paginator.cursor.prefetch.CursorLoadGuard
import com.jamal_aliev.paginator.cursor.prefetch.CursorPaginatorPrefetchController
import com.jamal_aliev.paginator.view.core.ScrollBinding

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


/** Cursor-paginator counterpart of [Paginator.bindPrefetchToRecyclerView]. */
@Deprecated(
    message = "bindPrefetchToRecyclerView is deprecated since 10.3.0: use bindPaginated, which takes the same " +
        "arguments and tracks dataItemCount from the paginator itself, or prefetchController + " +
        "bindToRecyclerView when the controller lives elsewhere (e.g. in a ViewModel).",
    replaceWith = ReplaceWith(
        "bindPaginated(recyclerView = recyclerView, lifecycleOwner = lifecycleOwner, headerCount = headerCount, footerCount = footerCount, options = options, enableCacheFlow = enableCacheFlow, loadGuard = loadGuard, onPrefetchError = onPrefetchError, preserveScroll = preserveScroll, scrollKey = scrollKey)",
    ),
)
public fun <K : Any, T> CursorPaginator<K, T>.bindPrefetchToRecyclerView(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    dataItemCount: () -> Int,
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
    val binding = controller.bindToRecyclerView(
        recyclerView = recyclerView,
        lifecycleOwner = lifecycleOwner,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        footerCount = footerCount,
        scrollSampleMillis = options.scrollSampleMillis,
        preserveScroll = preserveScroll,
        scrollKey = scrollKey,
    )
    return PrefetchBinding(controller, binding)
}

/** Cursor-paginator [Int]-overload of [bindPrefetchToRecyclerView]. */
@Deprecated(
    message = "bindPrefetchToRecyclerView is deprecated since 10.3.0: use bindPaginated, which takes the same " +
        "arguments and tracks dataItemCount from the paginator itself, or prefetchController + " +
        "bindToRecyclerView when the controller lives elsewhere (e.g. in a ViewModel).",
    replaceWith = ReplaceWith(
        "bindPaginated(recyclerView = recyclerView, lifecycleOwner = lifecycleOwner, headerCount = headerCount, footerCount = footerCount, options = options, enableCacheFlow = enableCacheFlow, loadGuard = loadGuard, onPrefetchError = onPrefetchError, preserveScroll = preserveScroll, scrollKey = scrollKey)",
    ),
)
@Suppress("DEPRECATION") // delegates to the deprecated lambda overload above
public fun <K : Any, T> CursorPaginator<K, T>.bindPrefetchToRecyclerView(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    dataItemCount: () -> Int,
    headerCount: Int,
    footerCount: Int = 0,
    options: PrefetchOptions = PrefetchOptions(),
    enableCacheFlow: Boolean = core.enableCacheFlow,
    loadGuard: CursorLoadGuard<K, T> = CursorLoadGuard.allowAll(),
    onPrefetchError: ((Exception) -> Unit)? = null,
    preserveScroll: Boolean = false,
    scrollKey: String? = null,
): PrefetchBinding<CursorPaginatorPrefetchController<K, T>> = bindPrefetchToRecyclerView(
    recyclerView = recyclerView,
    lifecycleOwner = lifecycleOwner,
    dataItemCount = dataItemCount,
    headerCount = { headerCount },
    footerCount = { footerCount },
    options = options,
    enableCacheFlow = enableCacheFlow,
    loadGuard = loadGuard,
    onPrefetchError = onPrefetchError,
    preserveScroll = preserveScroll,
    scrollKey = scrollKey,
)
