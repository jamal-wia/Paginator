package com.jamal_aliev.paginator.compose.cursor

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import com.jamal_aliev.paginator.cursor.CursorPaginator
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.core.page.PageState
import com.jamal_aliev.paginator.cursor.prefetch.CursorPaginatorPrefetchController
import com.jamal_aliev.paginator.core.prefetch.DefaultPrefetchDistance


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
