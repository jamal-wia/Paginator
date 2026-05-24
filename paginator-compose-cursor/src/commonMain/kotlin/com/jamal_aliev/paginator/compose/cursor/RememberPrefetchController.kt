package com.jamal_aliev.paginator.compose.cursor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.jamal_aliev.paginator.cursor.CursorPaginator
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.extension.prefetchController
import com.jamal_aliev.paginator.core.page.PageState
import com.jamal_aliev.paginator.cursor.prefetch.CursorPaginatorPrefetchController
import com.jamal_aliev.paginator.core.prefetch.DefaultPrefetchDistance


/**
 * Cursor-paginator counterpart of [Paginator.rememberPrefetchController].
 *
 * Behaviour, lifecycle, and hot-update semantics are identical — the only difference is the
 * [loadGuard] signature, which receives a [CursorBookmark] instead of a page number.
 */
@Composable
fun <T> CursorPaginator<T>.rememberPrefetchController(
    prefetchDistance: Int = DefaultPrefetchDistance,
    enableBackwardPrefetch: Boolean = false,
    silentlyLoading: Boolean = true,
    silentlyResult: Boolean = false,
    enabled: Boolean = true,
    cancelOnDispose: Boolean = true,
    enableCacheFlow: Boolean = core.enableCacheFlow,
    loadGuard: (cursor: CursorBookmark, state: PageState<T>?) -> Boolean = { _, _ -> true },
    onPrefetchError: ((Exception) -> Unit)? = null,
): CursorPaginatorPrefetchController<T> {
    val paginator = this
    val scope = rememberCoroutineScope()
    val controller = remember(paginator, scope) {
        paginator.prefetchController(
            scope = scope,
            prefetchDistance = prefetchDistance,
            enableBackwardPrefetch = enableBackwardPrefetch,
            silentlyLoading = silentlyLoading,
            silentlyResult = silentlyResult,
            loadGuard = loadGuard,
            enableCacheFlow = enableCacheFlow,
            onPrefetchError = onPrefetchError,
        )
    }
    SideEffect {
        // Equality-guard each write — `prefetchDistance`'s setter runs `require(value > 0)`
        // on assignment regardless of whether the value changed, and downstream observers of
        // these vars would otherwise be invalidated on every recomposition.
        if (controller.prefetchDistance != prefetchDistance) {
            controller.prefetchDistance = prefetchDistance
        }
        if (controller.enableBackwardPrefetch != enableBackwardPrefetch) {
            controller.enableBackwardPrefetch = enableBackwardPrefetch
        }
        if (controller.silentlyLoading != silentlyLoading) {
            controller.silentlyLoading = silentlyLoading
        }
        if (controller.silentlyResult != silentlyResult) {
            controller.silentlyResult = silentlyResult
        }
        if (controller.enabled != enabled) {
            controller.enabled = enabled
        }
        if (controller.onPrefetchError !== onPrefetchError) {
            controller.onPrefetchError = onPrefetchError
        }
    }
    DisposableEffect(controller, cancelOnDispose) {
        onDispose { if (cancelOnDispose) controller.cancel() }
    }
    return controller
}
