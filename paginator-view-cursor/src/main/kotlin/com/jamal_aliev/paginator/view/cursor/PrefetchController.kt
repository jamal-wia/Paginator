package com.jamal_aliev.paginator.view.cursor

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.jamal_aliev.paginator.core.prefetch.PrefetchOptions
import com.jamal_aliev.paginator.cursor.CursorPaginator
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.extension.prefetchController
import com.jamal_aliev.paginator.cursor.page.CursorPageState
import com.jamal_aliev.paginator.cursor.prefetch.CursorLoadGuard
import com.jamal_aliev.paginator.cursor.prefetch.CursorPaginatorPrefetchController


/**
 * Cursor-paginator counterpart of [Paginator.prefetchController].
 *
 * Behaviour, lifecycle, and parameters are identical — the only difference is the [loadGuard]
 * type, which receives a [CursorBookmark<K>] instead of a page number.
 */
public fun <K : Any, T> CursorPaginator<K, T>.prefetchController(
    lifecycleOwner: LifecycleOwner,
    options: PrefetchOptions = PrefetchOptions(),
    enableCacheFlow: Boolean = core.enableCacheFlow,
    loadGuard: CursorLoadGuard<K, T> = CursorLoadGuard.allowAll(),
    onPrefetchError: ((Exception) -> Unit)? = null,
): CursorPaginatorPrefetchController<K, T> {
    val controller = prefetchController(
        scope = lifecycleOwner.lifecycleScope,
        prefetchDistance = options.prefetchDistance,
        enableBackwardPrefetch = options.enableBackwardPrefetch,
        silentlyLoading = options.silentlyLoading,
        silentlyResult = options.silentlyResult,
        loadGuard = { cursor: CursorBookmark<K>, st: CursorPageState<K, T>? ->
            loadGuard(
                cursor,
                st
            )
        },
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
