package com.jamal_aliev.paginator.extension

import com.jamal_aliev.paginator.Paginator
import com.jamal_aliev.paginator.initializer.InitializerErrorPage
import com.jamal_aliev.paginator.initializer.InitializerProgressPage
import com.jamal_aliev.paginator.initializer.InitializerSuccessPage
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.prefetch.PaginatorPrefetchController
import kotlinx.coroutines.CoroutineScope

/**
 * Creates a [PaginatorPrefetchController] bound to this paginator.
 *
 * The returned controller monitors scroll position via its
 * [PaginatorPrefetchController.onScroll] method and automatically triggers
 * [Paginator.goNextPage] / [Paginator.goPreviousPage] when the user approaches
 * the edge of loaded content.
 *
 * @param scope [CoroutineScope] for launching prefetch coroutines.
 * @param prefetchDistance Number of items from the edge at which prefetch fires.
 * @param enableBackwardPrefetch If `true`, scrolling up also prefetches via [Paginator.goPreviousPage].
 * @param silentlyLoading If `true` (default), no [PageState.ProgressPage] snapshot is emitted
 *   during prefetch loading.
 * @param silentlyResult If `true`, no snapshot is emitted when the prefetched page arrives.
 * @param loadGuard Guard callback forwarded to navigation functions.
 * @param enableCacheFlow Forwarded to navigation functions.
 * @param initProgressState Factory for [PageState.ProgressPage].
 * @param initSuccessState Factory for [PageState.SuccessPage].
 * @param initErrorState Factory for [PageState.ErrorPage].
 * @param onPrefetchError Optional callback invoked when a prefetch fails.
 * @return A new [PaginatorPrefetchController] ready to receive [PaginatorPrefetchController.onScroll] calls.
 */
fun <T> Paginator<T>.prefetchController(
    scope: CoroutineScope,
    prefetchDistance: Int,
    enableBackwardPrefetch: Boolean = false,
    silentlyLoading: Boolean = true,
    silentlyResult: Boolean = false,
    loadGuard: (page: Int, state: PageState<T>?) -> Boolean = { _, _ -> true },
    enableCacheFlow: Boolean = core.enableCacheFlow,
    initProgressState: InitializerProgressPage<T> = core.initializerProgressPage,
    initSuccessState: InitializerSuccessPage<T> = core.initializerSuccessPage,
    initErrorState: InitializerErrorPage<T> = core.initializerErrorPage,
    onPrefetchError: ((Exception) -> Unit)? = null,
): PaginatorPrefetchController<T> {
    return PaginatorPrefetchController(
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
