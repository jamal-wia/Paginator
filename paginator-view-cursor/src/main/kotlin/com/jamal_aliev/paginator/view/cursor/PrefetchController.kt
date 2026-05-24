package com.jamal_aliev.paginator.view.cursor

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.jamal_aliev.paginator.cursor.CursorPaginator
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.extension.prefetchController
import com.jamal_aliev.paginator.core.page.PageState
import com.jamal_aliev.paginator.cursor.prefetch.CursorLoadGuard
import com.jamal_aliev.paginator.cursor.prefetch.CursorPaginatorPrefetchController
import com.jamal_aliev.paginator.core.prefetch.PageLoadGuard
import com.jamal_aliev.paginator.core.prefetch.PrefetchOptions


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
