package com.jamal_aliev.paginator.extension

import com.jamal_aliev.paginator.CursorPaginator
import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.initializer.InitializerEmptyPage
import com.jamal_aliev.paginator.initializer.InitializerErrorPage
import com.jamal_aliev.paginator.initializer.InitializerProgressPage
import com.jamal_aliev.paginator.initializer.InitializerSuccessPage
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.prefetch.CursorPaginatorPrefetchController
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
    initEmptyState: InitializerEmptyPage<T> = core.initializerEmptyPage,
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
        initEmptyState = initEmptyState,
        initErrorState = initErrorState,
        onPrefetchError = onPrefetchError,
    )
}
