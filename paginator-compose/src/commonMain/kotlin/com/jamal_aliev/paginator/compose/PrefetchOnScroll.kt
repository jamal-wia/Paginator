package com.jamal_aliev.paginator.compose

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import com.jamal_aliev.paginator.CursorPaginator
import com.jamal_aliev.paginator.Paginator
import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.prefetch.CursorPaginatorPrefetchController
import com.jamal_aliev.paginator.prefetch.DefaultPrefetchDistance
import com.jamal_aliev.paginator.prefetch.PaginatorPrefetchController

/**
 * One-call helper that wires scroll-driven prefetch for a [Paginator] into a Compose lazy
 * container. Combines [Paginator.rememberPrefetchController] with the corresponding
 * `BindTo*` extension under a single name resolved by the type of [state].
 *
 * Use this when prefetch should live for the lifetime of the screen and the controller does
 * not need to be referenced elsewhere. For view-model-scoped controllers, build the controller
 * in the view-model and call `BindToLazyList(...)` directly.
 *
 * Example:
 * ```
 * val listState = rememberLazyListState()
 * paginator.PrefetchOnScroll(
 *     state = listState,
 *     dataItemCount = uiState.items.size,
 *     headerCount = 1,
 * )
 * LazyColumn(state = listState) { … }
 * ```
 *
 * @param restartKey Optional extra key for the underlying scroll-observation coroutine. Bump it
 *   (e.g. via a refresh counter) to force the controller to recalibrate from the current
 *   viewport — useful right after `paginator.refresh()`.
 * @param scrollSampleMillis When > 0, throttle the scroll signal so only the latest emission
 *   per window is forwarded to the controller. `0` (default) disables throttling.
 *
 * @return The created [PaginatorPrefetchController], in case the call site needs direct access.
 */
@Composable
fun <T> Paginator<T>.PrefetchOnScroll(
    state: LazyListState,
    dataItemCount: Int,
    headerCount: Int = 0,
    footerCount: Int = 0,
    prefetchDistance: Int = DefaultPrefetchDistance,
    enableBackwardPrefetch: Boolean = false,
    silentlyLoading: Boolean = true,
    silentlyResult: Boolean = false,
    enabled: Boolean = true,
    cancelOnDispose: Boolean = true,
    restartKey: Any? = null,
    scrollSampleMillis: Long = 0L,
    onPrefetchError: ((Exception) -> Unit)? = null,
    loadGuard: (page: Int, state: PageState<T>?) -> Boolean = { _, _ -> true },
): PaginatorPrefetchController<T> {
    val controller = rememberPrefetchController(
        prefetchDistance = prefetchDistance,
        enableBackwardPrefetch = enableBackwardPrefetch,
        silentlyLoading = silentlyLoading,
        silentlyResult = silentlyResult,
        enabled = enabled,
        cancelOnDispose = cancelOnDispose,
        loadGuard = loadGuard,
        onPrefetchError = onPrefetchError,
    )
    controller.BindToLazyList(
        listState = state,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        footerCount = footerCount,
        restartKey = restartKey,
        scrollSampleMillis = scrollSampleMillis,
    )
    return controller
}

/** [LazyGridState] overload — see the [LazyListState] variant for the full contract. */
@Composable
fun <T> Paginator<T>.PrefetchOnScroll(
    state: LazyGridState,
    dataItemCount: Int,
    headerCount: Int = 0,
    footerCount: Int = 0,
    prefetchDistance: Int = DefaultPrefetchDistance,
    enableBackwardPrefetch: Boolean = false,
    silentlyLoading: Boolean = true,
    silentlyResult: Boolean = false,
    enabled: Boolean = true,
    cancelOnDispose: Boolean = true,
    restartKey: Any? = null,
    scrollSampleMillis: Long = 0L,
    onPrefetchError: ((Exception) -> Unit)? = null,
    loadGuard: (page: Int, state: PageState<T>?) -> Boolean = { _, _ -> true },
): PaginatorPrefetchController<T> {
    val controller = rememberPrefetchController(
        prefetchDistance = prefetchDistance,
        enableBackwardPrefetch = enableBackwardPrefetch,
        silentlyLoading = silentlyLoading,
        silentlyResult = silentlyResult,
        enabled = enabled,
        cancelOnDispose = cancelOnDispose,
        loadGuard = loadGuard,
        onPrefetchError = onPrefetchError,
    )
    controller.BindToLazyGrid(
        gridState = state,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        footerCount = footerCount,
        restartKey = restartKey,
        scrollSampleMillis = scrollSampleMillis,
    )
    return controller
}

/** [LazyStaggeredGridState] overload — see the [LazyListState] variant for the full contract. */
@Composable
fun <T> Paginator<T>.PrefetchOnScroll(
    state: LazyStaggeredGridState,
    dataItemCount: Int,
    headerCount: Int = 0,
    footerCount: Int = 0,
    prefetchDistance: Int = DefaultPrefetchDistance,
    enableBackwardPrefetch: Boolean = false,
    silentlyLoading: Boolean = true,
    silentlyResult: Boolean = false,
    enabled: Boolean = true,
    cancelOnDispose: Boolean = true,
    restartKey: Any? = null,
    scrollSampleMillis: Long = 0L,
    onPrefetchError: ((Exception) -> Unit)? = null,
    loadGuard: (page: Int, state: PageState<T>?) -> Boolean = { _, _ -> true },
): PaginatorPrefetchController<T> {
    val controller = rememberPrefetchController(
        prefetchDistance = prefetchDistance,
        enableBackwardPrefetch = enableBackwardPrefetch,
        silentlyLoading = silentlyLoading,
        silentlyResult = silentlyResult,
        enabled = enabled,
        cancelOnDispose = cancelOnDispose,
        loadGuard = loadGuard,
        onPrefetchError = onPrefetchError,
    )
    controller.BindToLazyStaggeredGrid(
        gridState = state,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        footerCount = footerCount,
        restartKey = restartKey,
        scrollSampleMillis = scrollSampleMillis,
    )
    return controller
}

/** Cursor-paginator counterpart for [LazyListState]. */
@Composable
fun <T> CursorPaginator<T>.PrefetchOnScroll(
    state: LazyListState,
    dataItemCount: Int,
    headerCount: Int = 0,
    footerCount: Int = 0,
    prefetchDistance: Int = DefaultPrefetchDistance,
    enableBackwardPrefetch: Boolean = false,
    silentlyLoading: Boolean = true,
    silentlyResult: Boolean = false,
    enabled: Boolean = true,
    cancelOnDispose: Boolean = true,
    restartKey: Any? = null,
    scrollSampleMillis: Long = 0L,
    onPrefetchError: ((Exception) -> Unit)? = null,
    loadGuard: (cursor: CursorBookmark, state: PageState<T>?) -> Boolean = { _, _ -> true },
): CursorPaginatorPrefetchController<T> {
    val controller = rememberPrefetchController(
        prefetchDistance = prefetchDistance,
        enableBackwardPrefetch = enableBackwardPrefetch,
        silentlyLoading = silentlyLoading,
        silentlyResult = silentlyResult,
        enabled = enabled,
        cancelOnDispose = cancelOnDispose,
        loadGuard = loadGuard,
        onPrefetchError = onPrefetchError,
    )
    controller.BindToLazyList(
        listState = state,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        footerCount = footerCount,
        restartKey = restartKey,
        scrollSampleMillis = scrollSampleMillis,
    )
    return controller
}

/** Cursor-paginator counterpart for [LazyGridState]. */
@Composable
fun <T> CursorPaginator<T>.PrefetchOnScroll(
    state: LazyGridState,
    dataItemCount: Int,
    headerCount: Int = 0,
    footerCount: Int = 0,
    prefetchDistance: Int = DefaultPrefetchDistance,
    enableBackwardPrefetch: Boolean = false,
    silentlyLoading: Boolean = true,
    silentlyResult: Boolean = false,
    enabled: Boolean = true,
    cancelOnDispose: Boolean = true,
    restartKey: Any? = null,
    scrollSampleMillis: Long = 0L,
    onPrefetchError: ((Exception) -> Unit)? = null,
    loadGuard: (cursor: CursorBookmark, state: PageState<T>?) -> Boolean = { _, _ -> true },
): CursorPaginatorPrefetchController<T> {
    val controller = rememberPrefetchController(
        prefetchDistance = prefetchDistance,
        enableBackwardPrefetch = enableBackwardPrefetch,
        silentlyLoading = silentlyLoading,
        silentlyResult = silentlyResult,
        enabled = enabled,
        cancelOnDispose = cancelOnDispose,
        loadGuard = loadGuard,
        onPrefetchError = onPrefetchError,
    )
    controller.BindToLazyGrid(
        gridState = state,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        footerCount = footerCount,
        restartKey = restartKey,
        scrollSampleMillis = scrollSampleMillis,
    )
    return controller
}

/** Cursor-paginator counterpart for [LazyStaggeredGridState]. */
@Composable
fun <T> CursorPaginator<T>.PrefetchOnScroll(
    state: LazyStaggeredGridState,
    dataItemCount: Int,
    headerCount: Int = 0,
    footerCount: Int = 0,
    prefetchDistance: Int = DefaultPrefetchDistance,
    enableBackwardPrefetch: Boolean = false,
    silentlyLoading: Boolean = true,
    silentlyResult: Boolean = false,
    enabled: Boolean = true,
    cancelOnDispose: Boolean = true,
    restartKey: Any? = null,
    scrollSampleMillis: Long = 0L,
    onPrefetchError: ((Exception) -> Unit)? = null,
    loadGuard: (cursor: CursorBookmark, state: PageState<T>?) -> Boolean = { _, _ -> true },
): CursorPaginatorPrefetchController<T> {
    val controller = rememberPrefetchController(
        prefetchDistance = prefetchDistance,
        enableBackwardPrefetch = enableBackwardPrefetch,
        silentlyLoading = silentlyLoading,
        silentlyResult = silentlyResult,
        enabled = enabled,
        cancelOnDispose = cancelOnDispose,
        loadGuard = loadGuard,
        onPrefetchError = onPrefetchError,
    )
    controller.BindToLazyStaggeredGrid(
        gridState = state,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        footerCount = footerCount,
        restartKey = restartKey,
        scrollSampleMillis = scrollSampleMillis,
    )
    return controller
}
