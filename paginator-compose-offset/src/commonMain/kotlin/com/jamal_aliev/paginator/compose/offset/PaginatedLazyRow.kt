package com.jamal_aliev.paginator.compose.offset

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
 * Horizontal turnkey, scroll-anchor-safe paginated `LazyRow` for an offset [Paginator]. The
 * horizontal counterpart of [PaginatedLazyColumn]; the "loading previous / next page" indicators
 * animate in on the start / end edge. Scroll-on-jump works the same way (see [PaginatedLazyColumn]),
 * observing [Paginator.jumpEvents]. See [PaginatedLazyColumn] for the full parameter contract
 * ([key] is required).
 */
@Composable
fun <T> PaginatedLazyRow(
    paginator: Paginator<T>,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    prefetch: PrefetchOptions? = PrefetchOptions(),
    onPrefetchError: ((Exception) -> Unit)? = null,
    loadGuard: PageLoadGuard<T> = PageLoadGuard.allowAll(),
    edgeGatedIndicators: Boolean = true,
    autoScrollToJumpTarget: Boolean = true,
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
    itemContent: @Composable LazyItemScope.(item: T) -> Unit,
) {
    val uiState by paginator.uiState.collectAsState(initial = PaginatorUiState.Idle)

    when (val current = uiState) {
        is PaginatorUiState.Loading -> if (loadingContent != null) { loadingContent(); return }
        is PaginatorUiState.Empty -> if (emptyContent != null) { emptyContent(); return }
        is PaginatorUiState.Error -> if (errorContent != null) { errorContent(current.state); return }
        else -> Unit
    }

    val items = (uiState as? PaginatorUiState.Content<T>)?.items.orEmpty()

    JumpScrollEffect(paginator, state, items, key, autoScrollToJumpTarget)

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
        controller.BindToLazyList(
            listState = state,
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
        LazyRow(
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = contentPadding,
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment,
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
