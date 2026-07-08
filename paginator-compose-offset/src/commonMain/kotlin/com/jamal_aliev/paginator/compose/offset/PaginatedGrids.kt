package com.jamal_aliev.paginator.compose.offset

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jamal_aliev.paginator.compose.core.PaginatorLoadingIndicator
import com.jamal_aliev.paginator.compose.core.PaginatorScrollEdgeIndicators
import com.jamal_aliev.paginator.core.page.PageState
import com.jamal_aliev.paginator.core.page.PaginatorUiState
import com.jamal_aliev.paginator.core.prefetch.PageLoadGuard
import com.jamal_aliev.paginator.core.prefetch.PrefetchOptions
import com.jamal_aliev.paginator.offset.Paginator
import com.jamal_aliev.paginator.offset.extension.uiState

/**
 * Turnkey, scroll-anchor-safe paginated `LazyVerticalGrid` for an offset [Paginator]. The grid
 * counterpart of [PaginatedLazyColumn]; the boundary indicators animate on the top / bottom edge.
 * See [PaginatedLazyColumn] for the full parameter contract ([key] is required).
 */
@Composable
fun <T> PaginatedLazyVerticalGrid(
    paginator: Paginator<T>,
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    prefetch: PrefetchOptions? = PrefetchOptions(),
    onPrefetchError: ((Exception) -> Unit)? = null,
    loadGuard: PageLoadGuard<T> = PageLoadGuard.allowAll(),
    edgeGatedIndicators: Boolean = true,
    contentType: (item: T) -> Any? = { null },
    prependIndicator: @Composable (PageState.ProgressState<T>) -> Unit = { PaginatorLoadingIndicator() },
    appendIndicator: @Composable (PageState.ProgressState<T>) -> Unit = { PaginatorLoadingIndicator() },
    loadingContent: (@Composable () -> Unit)? = null,
    emptyContent: (@Composable () -> Unit)? = null,
    errorContent: (@Composable (PageState.ErrorState<T>) -> Unit)? = null,
    key: (item: T) -> Any,
    itemContent: @Composable LazyGridItemScope.(item: T) -> Unit,
) {
    val uiState by paginator.uiState.collectAsState(initial = PaginatorUiState.Idle)

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
            loadGuard = { page, st -> loadGuard(page, st) },
            onPrefetchError = onPrefetchError,
        )
        controller.BindToLazyGrid(
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
        LazyVerticalGrid(
            columns = columns,
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
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
 * Horizontal turnkey, scroll-anchor-safe paginated `LazyHorizontalGrid` for an offset [Paginator].
 * The boundary indicators animate on the start / end edge. See [PaginatedLazyColumn] for the full
 * parameter contract ([key] is required).
 */
@Composable
fun <T> PaginatedLazyHorizontalGrid(
    paginator: Paginator<T>,
    rows: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    prefetch: PrefetchOptions? = PrefetchOptions(),
    onPrefetchError: ((Exception) -> Unit)? = null,
    loadGuard: PageLoadGuard<T> = PageLoadGuard.allowAll(),
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
    itemContent: @Composable LazyGridItemScope.(item: T) -> Unit,
) {
    val uiState by paginator.uiState.collectAsState(initial = PaginatorUiState.Idle)

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
            loadGuard = { page, st -> loadGuard(page, st) },
            onPrefetchError = onPrefetchError,
        )
        controller.BindToLazyGrid(
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
        LazyHorizontalGrid(
            rows = rows,
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
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
