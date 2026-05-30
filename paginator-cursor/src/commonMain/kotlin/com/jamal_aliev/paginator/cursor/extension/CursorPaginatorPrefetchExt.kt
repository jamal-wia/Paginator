package com.jamal_aliev.paginator.cursor.extension

import com.jamal_aliev.paginator.cursor.CursorPaginator
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.initializer.InitializerCursorErrorPage
import com.jamal_aliev.paginator.cursor.initializer.InitializerCursorProgressPage
import com.jamal_aliev.paginator.cursor.initializer.InitializerCursorSuccessPage
import com.jamal_aliev.paginator.cursor.page.CursorPageState
import com.jamal_aliev.paginator.cursor.prefetch.CursorPaginatorPrefetchController
import kotlinx.coroutines.CoroutineScope

/**
 * Creates a [CursorPaginatorPrefetchController] bound to this paginator.
 *
 * Mirrors [prefetchController] for [com.jamal_aliev.paginator.offset.Paginator] but
 * uses cursor links instead of numeric page bounds to decide when to prefetch.
 */
fun <K : Any, T> CursorPaginator<K, T>.prefetchController(
    scope: CoroutineScope,
    prefetchDistance: Int,
    enableBackwardPrefetch: Boolean = false,
    silentlyLoading: Boolean = false,
    silentlyResult: Boolean = false,
    loadGuard: (cursor: CursorBookmark<K>, state: CursorPageState<K, T>?) -> Boolean = { _, _ -> true },
    enableCacheFlow: Boolean = core.enableCacheFlow,
    initProgressState: InitializerCursorProgressPage<K, T> = core.initializerProgressPage,
    initSuccessState: InitializerCursorSuccessPage<K, T> = core.initializerSuccessPage,
    initErrorState: InitializerCursorErrorPage<K, T> = core.initializerErrorPage,
    onPrefetchError: ((Exception) -> Unit)? = null,
): CursorPaginatorPrefetchController<K, T> {
    return CursorPaginatorPrefetchController(
        paginator = this,
        scope = scope,
        prefetchDistance = prefetchDistance,
        enableBackwardPrefetch = enableBackwardPrefetch,
        silentlyLoading = silentlyLoading,
        silentlyResult = silentlyResult,
        loadGuard = loadGuard,
        enableCacheFlow = enableCacheFlow,
        initProgressState = initProgressState,
        initSuccessState = initSuccessState,
        initErrorState = initErrorState,
        onPrefetchError = onPrefetchError,
    )
}
