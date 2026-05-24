package com.jamal_aliev.paginator.cursor.extension

import com.jamal_aliev.paginator.cursor.CursorPaginator
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.core.initializer.InitializerErrorPage
import com.jamal_aliev.paginator.core.initializer.InitializerProgressPage
import com.jamal_aliev.paginator.core.initializer.InitializerSuccessPage
import com.jamal_aliev.paginator.core.page.PageState
import com.jamal_aliev.paginator.cursor.prefetch.CursorPaginatorPrefetchController
import kotlinx.coroutines.CoroutineScope

/**
 * Creates a [CursorPaginatorPrefetchController] bound to this paginator.
 *
 * Mirrors [prefetchController] for [com.jamal_aliev.paginator.Paginator] but
 * uses cursor links instead of numeric page bounds to decide when to prefetch.
 */
fun <T> CursorPaginator<T>.prefetchController(
    scope: CoroutineScope,
    prefetchDistance: Int,
    enableBackwardPrefetch: Boolean = false,
    silentlyLoading: Boolean = true,
    silentlyResult: Boolean = false,
    loadGuard: (cursor: CursorBookmark, state: PageState<T>?) -> Boolean = { _, _ -> true },
    enableCacheFlow: Boolean = core.enableCacheFlow,
    initProgressState: InitializerProgressPage<T> = core.initializerProgressPage,
    initSuccessState: InitializerSuccessPage<T> = core.initializerSuccessPage,
    initErrorState: InitializerErrorPage<T> = core.initializerErrorPage,
    onPrefetchError: ((Exception) -> Unit)? = null,
): CursorPaginatorPrefetchController<T> {
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
