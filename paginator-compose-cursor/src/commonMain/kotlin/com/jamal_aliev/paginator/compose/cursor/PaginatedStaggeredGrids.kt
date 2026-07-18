package com.jamal_aliev.paginator.compose.cursor

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyHorizontalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridItemScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jamal_aliev.paginator.compose.core.PaginatorLoadingIndicator
import com.jamal_aliev.paginator.compose.core.PaginatorScrollEdgeIndicators
import com.jamal_aliev.paginator.core.page.PageState
import com.jamal_aliev.paginator.core.page.PaginatorUiState
import com.jamal_aliev.paginator.core.prefetch.PrefetchOptions
import com.jamal_aliev.paginator.cursor.CursorPaginator
import com.jamal_aliev.paginator.cursor.extension.uiState
import com.jamal_aliev.paginator.cursor.prefetch.CursorLoadGuard

/**
 * Turnkey, scroll-anchor-safe paginated `LazyVerticalStaggeredGrid` for a [CursorPaginator]. The
 * boundary indicators animate on the top / bottom edge. [key] is required for scroll-anchor
 * preservation.
 */
@Composable
fun <K : Any, T> PaginatedLazyVerticalStaggeredGrid(
    paginator: CursorPaginator<K, T>,
    columns: StaggeredGridCells,
    modifier: Modifier = Modifier,
    state: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalItemSpacing: Dp = 0.dp,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(0.dp),
    prefetch: PrefetchOptions? = PrefetchOptions(),
    onPrefetchError: ((Exception) -> Unit)? = null,
    loadGuard: CursorLoadGuard<K, T> = CursorLoadGuard.allowAll(),
    edgeGatedIndicators: Boolean = true,
    contentType: (item: T) -> Any? = { null },
    prependIndicator: @Composable (PageState.ProgressState<T>) -> Unit = { PaginatorLoadingIndicator() },
    appendIndicator: @Composable (PageState.ProgressState<T>) -> Unit = { PaginatorLoadingIndicator() },
    loadingContent: (@Composable () -> Unit)? = null,
    emptyContent: (@Composable () -> Unit)? = null,
    errorContent: (@Composable (PageState.ErrorState<T>) -> Unit)? = null,
    key: (item: T) -> Any,
    itemContent: @Composable LazyStaggeredGridItemScope.(item: T) -> Unit,
) {
    val uiState by paginator.uiState.collectAsStateWithLifecycle(initialValue = PaginatorUiState.Idle)

    when (val current = uiState) {
        is PaginatorUiState.Loading -> if (loadingContent != null) { loadingContent(); return }
        is PaginatorUiState.Empty -> if (emptyContent != null) { emptyContent(); return }
        is PaginatorUiState.Error -> if (errorContent != null) { errorContent(current.state); return }
        else -> Unit
    }

    val items = (uiState as? PaginatorUiState.Content<T>)?.items.orEmpty()

    if (prefetch != null) {
        val controller = paginator.rememberPrefetchController(
            prefetchDistance = prefetch.prefetchDistance,
            enableBackwardPrefetch = prefetch.enableBackwardPrefetch,
            silentlyLoading = prefetch.silentlyLoading,
            silentlyResult = prefetch.silentlyResult,
            enabled = prefetch.enabled,
            cancelOnDispose = prefetch.cancelOnDispose,
            loadGuard = { cursor, st -> loadGuard(cursor, st) },
            onPrefetchError = onPrefetchError,
        )
        controller.BindToLazyStaggeredGrid(
            gridState = state,
            dataItemCount = items.size,
            headerCount = 0,
            footerCount = 0,
            scrollSampleMillis = prefetch.scrollSampleMillis,
        )
    }

    PaginatorScrollEdgeIndicators(
        uiState = uiState,
        scrollState = state,
        modifier = modifier,
        orientation = Orientation.Vertical,
        edgeGated = edgeGatedIndicators,
        prependIndicator = prependIndicator,
        appendIndicator = appendIndicator,
    ) {
        LazyVerticalStaggeredGrid(
            columns = columns,
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = contentPadding,
            verticalItemSpacing = verticalItemSpacing,
            horizontalArrangement = horizontalArrangement,
        ) {
            items(
                count = items.size,
                key = { index -> key(items[index]) },
                contentType = { index -> contentType(items[index]) },
            ) { index ->
                itemContent(items[index])
            }
        }
    }
}

/**
 * Horizontal turnkey, scroll-anchor-safe paginated `LazyHorizontalStaggeredGrid` for a
 * [CursorPaginator]. The boundary indicators animate on the start / end edge. [key] is required for
 * scroll-anchor preservation.
 */
@Composable
fun <K : Any, T> PaginatedLazyHorizontalStaggeredGrid(
    paginator: CursorPaginator<K, T>,
    rows: StaggeredGridCells,
    modifier: Modifier = Modifier,
    state: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalItemSpacing: Dp = 0.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(0.dp),
    prefetch: PrefetchOptions? = PrefetchOptions(),
    onPrefetchError: ((Exception) -> Unit)? = null,
    loadGuard: CursorLoadGuard<K, T> = CursorLoadGuard.allowAll(),
    edgeGatedIndicators: Boolean = true,
    contentType: (item: T) -> Any? = { null },
    prependIndicator: @Composable (PageState.ProgressState<T>) -> Unit = {
        PaginatorLoadingIndicator(orientation = Orientation.Horizontal)
    },
    appendIndicator: @Composable (PageState.ProgressState<T>) -> Unit = {
        PaginatorLoadingIndicator(orientation = Orientation.Horizontal)
    },
    loadingContent: (@Composable () -> Unit)? = null,
    emptyContent: (@Composable () -> Unit)? = null,
    errorContent: (@Composable (PageState.ErrorState<T>) -> Unit)? = null,
    key: (item: T) -> Any,
    itemContent: @Composable LazyStaggeredGridItemScope.(item: T) -> Unit,
) {
    val uiState by paginator.uiState.collectAsStateWithLifecycle(initialValue = PaginatorUiState.Idle)

    when (val current = uiState) {
        is PaginatorUiState.Loading -> if (loadingContent != null) { loadingContent(); return }
        is PaginatorUiState.Empty -> if (emptyContent != null) { emptyContent(); return }
        is PaginatorUiState.Error -> if (errorContent != null) { errorContent(current.state); return }
        else -> Unit
    }

    val items = (uiState as? PaginatorUiState.Content<T>)?.items.orEmpty()

    if (prefetch != null) {
        val controller = paginator.rememberPrefetchController(
            prefetchDistance = prefetch.prefetchDistance,
            enableBackwardPrefetch = prefetch.enableBackwardPrefetch,
            silentlyLoading = prefetch.silentlyLoading,
            silentlyResult = prefetch.silentlyResult,
            enabled = prefetch.enabled,
            cancelOnDispose = prefetch.cancelOnDispose,
            loadGuard = { cursor, st -> loadGuard(cursor, st) },
            onPrefetchError = onPrefetchError,
        )
        controller.BindToLazyStaggeredGrid(
            gridState = state,
            dataItemCount = items.size,
            headerCount = 0,
            footerCount = 0,
            scrollSampleMillis = prefetch.scrollSampleMillis,
        )
    }

    PaginatorScrollEdgeIndicators(
        uiState = uiState,
        scrollState = state,
        modifier = modifier,
        orientation = Orientation.Horizontal,
        edgeGated = edgeGatedIndicators,
        prependIndicator = prependIndicator,
        appendIndicator = appendIndicator,
    ) {
        LazyHorizontalStaggeredGrid(
            rows = rows,
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = contentPadding,
            horizontalItemSpacing = horizontalItemSpacing,
            verticalArrangement = verticalArrangement,
        ) {
            items(
                count = items.size,
                key = { index -> key(items[index]) },
                contentType = { index -> contentType(items[index]) },
            ) { index ->
                itemContent(items[index])
            }
        }
    }
}
