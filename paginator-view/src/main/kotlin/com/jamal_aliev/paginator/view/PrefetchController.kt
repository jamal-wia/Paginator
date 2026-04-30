package com.jamal_aliev.paginator.view

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.jamal_aliev.paginator.CursorPaginator
import com.jamal_aliev.paginator.Paginator
import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.extension.prefetchController
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.prefetch.CursorPaginatorPrefetchController
import com.jamal_aliev.paginator.prefetch.PaginatorPrefetchController

/**
 * Creates a [PaginatorPrefetchController] whose lifecycle is bound to [lifecycleOwner].
 *
 * The controller is created against [LifecycleOwner.lifecycleScope] and an observer is registered
 * to call [PaginatorPrefetchController.cancel] on `ON_DESTROY`. This is the right helper when
 * prefetch should live as long as the screen (an `Activity` or `Fragment` view-lifecycle).
 *
 * For a `ViewModel`-scoped controller, build it directly from `viewModelScope` with the existing
 * [com.jamal_aliev.paginator.extension.prefetchController] and skip this overload — passing it to
 * [bindToRecyclerView] only requires a `LifecycleOwner` for the scroll-listener cleanup, not for
 * the controller's coroutine scope.
 *
 * @param lifecycleOwner Lifecycle that scopes both the prefetch coroutines and the cancel signal.
 *   Typically a `Fragment.viewLifecycleOwner` or an `Activity`.
 * @param prefetchDistance Distance from the edge (in items) at which prefetch fires.
 * @param enableBackwardPrefetch If `true`, scrolling up also triggers `goPreviousPage`.
 * @param silentlyLoading Suppress `ProgressPage` snapshot emission during prefetch loading.
 * @param silentlyResult Suppress snapshot emission when the prefetched page arrives.
 * @param enableCacheFlow Forwarded to the underlying navigation calls (initial value only).
 * @param loadGuard Guard callback forwarded to navigation functions (initial value only).
 * @param onPrefetchError Optional callback invoked when a prefetch fails.
 */
fun <T> Paginator<T>.prefetchController(
    lifecycleOwner: LifecycleOwner,
    prefetchDistance: Int,
    enableBackwardPrefetch: Boolean = false,
    silentlyLoading: Boolean = true,
    silentlyResult: Boolean = false,
    enableCacheFlow: Boolean = core.enableCacheFlow,
    loadGuard: (page: Int, state: PageState<T>?) -> Boolean = { _, _ -> true },
    onPrefetchError: ((Exception) -> Unit)? = null,
): PaginatorPrefetchController<T> {
    val controller = prefetchController(
        scope = lifecycleOwner.lifecycleScope,
        prefetchDistance = prefetchDistance,
        enableBackwardPrefetch = enableBackwardPrefetch,
        silentlyLoading = silentlyLoading,
        silentlyResult = silentlyResult,
        loadGuard = loadGuard,
        enableCacheFlow = enableCacheFlow,
        onPrefetchError = onPrefetchError,
    )
    lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            controller.cancel()
            owner.lifecycle.removeObserver(this)
        }
    })
    return controller
}

/**
 * Cursor-paginator counterpart of [Paginator.prefetchController].
 *
 * Behaviour, lifecycle, and parameters are identical — the only difference is the [loadGuard]
 * signature, which receives a [CursorBookmark] instead of a page number.
 */
fun <T> CursorPaginator<T>.prefetchController(
    lifecycleOwner: LifecycleOwner,
    prefetchDistance: Int,
    enableBackwardPrefetch: Boolean = false,
    silentlyLoading: Boolean = true,
    silentlyResult: Boolean = false,
    enableCacheFlow: Boolean = core.enableCacheFlow,
    loadGuard: (cursor: CursorBookmark, state: PageState<T>?) -> Boolean = { _, _ -> true },
    onPrefetchError: ((Exception) -> Unit)? = null,
): CursorPaginatorPrefetchController<T> {
    val controller = prefetchController(
        scope = lifecycleOwner.lifecycleScope,
        prefetchDistance = prefetchDistance,
        enableBackwardPrefetch = enableBackwardPrefetch,
        silentlyLoading = silentlyLoading,
        silentlyResult = silentlyResult,
        loadGuard = loadGuard,
        enableCacheFlow = enableCacheFlow,
        onPrefetchError = onPrefetchError,
    )
    lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            controller.cancel()
            owner.lifecycle.removeObserver(this)
        }
    })
    return controller
}
