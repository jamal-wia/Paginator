package com.jamal_aliev.paginator.view

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.jamal_aliev.paginator.CursorPaginator
import com.jamal_aliev.paginator.Paginator
import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.extension.prefetchController
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.prefetch.CursorLoadGuard
import com.jamal_aliev.paginator.prefetch.CursorPaginatorPrefetchController
import com.jamal_aliev.paginator.prefetch.PageLoadGuard
import com.jamal_aliev.paginator.prefetch.PaginatorPrefetchController
import com.jamal_aliev.paginator.prefetch.PrefetchOptions

/**
 * Creates a [PaginatorPrefetchController] whose lifecycle is bound to [lifecycleOwner].
 *
 * The controller is created against [LifecycleOwner.lifecycleScope]. If [PrefetchOptions.cancelOnDispose]
 * is `true` (the default), an observer is registered to call [PaginatorPrefetchController.cancel]
 * on `ON_DESTROY`. Set it to `false` to let an in-flight prefetch survive a brief lifecycle gap
 * (overlay, bottom-sheet, screen recreation) so the page lands when the screen returns.
 *
 * For a `ViewModel`-scoped controller, build it directly from `viewModelScope` with the existing
 * [com.jamal_aliev.paginator.extension.prefetchController] and skip this overload.
 *
 * Runtime-mutable settings ([PrefetchOptions.prefetchDistance], [PrefetchOptions.enableBackwardPrefetch],
 * [PrefetchOptions.silentlyLoading], [PrefetchOptions.silentlyResult], [PrefetchOptions.enabled])
 * are forwarded to the controller at creation time. To change them afterwards, mutate the
 * controller's properties directly — the controller has hot-update semantics.
 *
 * @param lifecycleOwner Lifecycle that scopes the prefetch coroutines and (optionally) the
 *   cancel signal. Typically a `Fragment.viewLifecycleOwner` or an `Activity`.
 * @param options Bag of runtime settings. Pass a hoisted instance to share configuration
 *   between screens, or build inline at the call site.
 * @param enableCacheFlow Forwarded to the underlying navigation calls (initial value only).
 * @param loadGuard Stable guard. Use [PageLoadGuard.allowAll] (default) to admit every page.
 * @param onPrefetchError Optional callback invoked when a prefetch fails. Pair with
 *   [PrefetchErrorChannel] to surface errors via a `StateFlow`.
 */
public fun <T> Paginator<T>.prefetchController(
    lifecycleOwner: LifecycleOwner,
    options: PrefetchOptions = PrefetchOptions(),
    enableCacheFlow: Boolean = core.enableCacheFlow,
    loadGuard: PageLoadGuard<T> = PageLoadGuard.allowAll(),
    onPrefetchError: ((Exception) -> Unit)? = null,
): PaginatorPrefetchController<T> {
    val controller = prefetchController(
        scope = lifecycleOwner.lifecycleScope,
        prefetchDistance = options.prefetchDistance,
        enableBackwardPrefetch = options.enableBackwardPrefetch,
        silentlyLoading = options.silentlyLoading,
        silentlyResult = options.silentlyResult,
        loadGuard = { page: Int, st: PageState<T>? -> loadGuard(page, st) },
        enableCacheFlow = enableCacheFlow,
        onPrefetchError = onPrefetchError,
    )
    controller.enabled = options.enabled
    if (options.cancelOnDispose) {
        lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                controller.cancel()
                owner.lifecycle.removeObserver(this)
            }
        })
    }
    return controller
}

/**
 * Cursor-paginator counterpart of [Paginator.prefetchController].
 *
 * Behaviour, lifecycle, and parameters are identical — the only difference is the [loadGuard]
 * type, which receives a [CursorBookmark] instead of a page number.
 */
public fun <T> CursorPaginator<T>.prefetchController(
    lifecycleOwner: LifecycleOwner,
    options: PrefetchOptions = PrefetchOptions(),
    enableCacheFlow: Boolean = core.enableCacheFlow,
    loadGuard: CursorLoadGuard<T> = CursorLoadGuard.allowAll(),
    onPrefetchError: ((Exception) -> Unit)? = null,
): CursorPaginatorPrefetchController<T> {
    val controller = prefetchController(
        scope = lifecycleOwner.lifecycleScope,
        prefetchDistance = options.prefetchDistance,
        enableBackwardPrefetch = options.enableBackwardPrefetch,
        silentlyLoading = options.silentlyLoading,
        silentlyResult = options.silentlyResult,
        loadGuard = { cursor: CursorBookmark, st: PageState<T>? -> loadGuard(cursor, st) },
        enableCacheFlow = enableCacheFlow,
        onPrefetchError = onPrefetchError,
    )
    controller.enabled = options.enabled
    if (options.cancelOnDispose) {
        lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                controller.cancel()
                owner.lifecycle.removeObserver(this)
            }
        })
    }
    return controller
}
