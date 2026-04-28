package com.jamal_aliev.paginator.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.jamal_aliev.paginator.CursorPaginator
import com.jamal_aliev.paginator.Paginator
import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.extension.prefetchController
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.prefetch.CursorPaginatorPrefetchController
import com.jamal_aliev.paginator.prefetch.PaginatorPrefetchController

/**
 * Remembers a [PaginatorPrefetchController] whose lifecycle is bound to the calling composable.
 *
 * The controller is created once per [Paginator] instance via [rememberCoroutineScope]; in-flight
 * prefetch jobs are cancelled when the composable leaves composition (`controller.cancel()` is
 * called in [DisposableEffect]'s `onDispose`). This is the right helper when prefetch should
 * live as long as the screen — for view-model-scoped controllers, build the controller inside
 * the view-model and call `BindToLazyList(...)` directly without this wrapper.
 *
 * Runtime-mutable properties ([prefetchDistance], [enableBackwardPrefetch], [silentlyLoading],
 * [silentlyResult], [enabled], [onPrefetchError]) are kept in sync with the latest composition
 * via [SideEffect] — changing the argument re-applies the value without recreating the
 * controller, matching the controller's documented hot-update semantics.
 *
 * Note: changing [enableCacheFlow] / [loadGuard] / `init*State` factories at runtime is also
 * supported by the controller, but those are intentionally **not** wired through `SideEffect`
 * here to keep the composable signature focused. If you need to change them mid-flight, mutate
 * the controller directly.
 *
 * @param prefetchDistance Distance from the edge (in items) at which prefetch fires.
 * @param enableBackwardPrefetch If `true`, scrolling up also triggers `goPreviousPage`.
 * @param silentlyLoading Suppress `ProgressPage` snapshot emission during prefetch loading.
 * @param silentlyResult Suppress snapshot emission when the prefetched page arrives.
 * @param enabled Master switch — `false` makes [PaginatorPrefetchController.onScroll] a no-op.
 * @param enableCacheFlow Forwarded to the underlying navigation calls (initial value only).
 * @param loadGuard Guard callback forwarded to navigation functions (initial value only).
 * @param onPrefetchError Optional callback invoked when a prefetch fails.
 */
@Composable
fun <T> Paginator<T>.rememberPrefetchController(
    prefetchDistance: Int,
    enableBackwardPrefetch: Boolean = false,
    silentlyLoading: Boolean = true,
    silentlyResult: Boolean = false,
    enabled: Boolean = true,
    enableCacheFlow: Boolean = core.enableCacheFlow,
    loadGuard: (page: Int, state: PageState<T>?) -> Boolean = { _, _ -> true },
    onPrefetchError: ((Exception) -> Unit)? = null,
): PaginatorPrefetchController<T> {
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
        controller.prefetchDistance = prefetchDistance
        controller.enableBackwardPrefetch = enableBackwardPrefetch
        controller.silentlyLoading = silentlyLoading
        controller.silentlyResult = silentlyResult
        controller.enabled = enabled
        controller.onPrefetchError = onPrefetchError
    }
    DisposableEffect(controller) {
        onDispose { controller.cancel() }
    }
    return controller
}

/**
 * Cursor-paginator counterpart of [Paginator.rememberPrefetchController].
 *
 * Behaviour, lifecycle, and hot-update semantics are identical — the only difference is the
 * [loadGuard] signature, which receives a [CursorBookmark] instead of a page number.
 */
@Composable
fun <T> CursorPaginator<T>.rememberPrefetchController(
    prefetchDistance: Int,
    enableBackwardPrefetch: Boolean = false,
    silentlyLoading: Boolean = true,
    silentlyResult: Boolean = false,
    enabled: Boolean = true,
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
        controller.prefetchDistance = prefetchDistance
        controller.enableBackwardPrefetch = enableBackwardPrefetch
        controller.silentlyLoading = silentlyLoading
        controller.silentlyResult = silentlyResult
        controller.enabled = enabled
        controller.onPrefetchError = onPrefetchError
    }
    DisposableEffect(controller) {
        onDispose { controller.cancel() }
    }
    return controller
}
