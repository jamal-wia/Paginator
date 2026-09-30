package com.jamal_aliev.paginator.compose.cursor

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jamal_aliev.paginator.compose.core.PaginatorLoadingIndicator
import com.jamal_aliev.paginator.compose.core.PaginatorScrollEdgeIndicators
import com.jamal_aliev.paginator.core.extension.toUiState
import com.jamal_aliev.paginator.core.page.PageState
import com.jamal_aliev.paginator.core.page.PaginatorUiState
import com.jamal_aliev.paginator.core.prefetch.PrefetchOptions
import com.jamal_aliev.paginator.cursor.CursorPaginator
import com.jamal_aliev.paginator.cursor.prefetch.CursorLoadGuard

/**
 * Horizontal turnkey, scroll-anchor-safe paginated `LazyRow` for a [CursorPaginator]. The indicators
 * animate in on the start / end edge; scroll-on-jump works the same way (via
 * [CursorPaginator.navigationEvents]). See the cursor [PaginatedLazyColumn] for the full parameter
 * contract ([key] is required).
 */
@Composable
fun <K : Any, T> PaginatedLazyRow(
    paginator: CursorPaginator<K, T>,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    prefetch: PrefetchOptions? = PrefetchOptions(),
    onPrefetchError: ((Exception) -> Unit)? = null,
    loadGuard: CursorLoadGuard<K, T> = CursorLoadGuard.allowAll(),
    edgeGatedIndicators: Boolean = true,
    autoScrollToJumpTarget: Boolean = true,
    contentType: (item: T) -> Any? = { null },
    prependIndicator: @Composable (PageState.ProgressState<T>) -> Unit = {
        PaginatorLoadingIndicator(orientation = Orientation.Horizontal)
    },
    appendIndicator: @Composable (PageState.ProgressState<T>) -> Unit = {
        PaginatorLoadingIndicator(orientation = Orientation.Horizontal)
    },
    prependErrorIndicator: (@Composable (PageState.ErrorState<T>) -> Unit)? = null,
    appendErrorIndicator: (@Composable (PageState.ErrorState<T>) -> Unit)? = null,
    loadingContent: (@Composable () -> Unit)? = null,
    emptyContent: (@Composable () -> Unit)? = null,
    errorContent: (@Composable (PageState.ErrorState<T>) -> Unit)? = null,
    key: (item: T) -> Any,
    itemContent: @Composable LazyItemScope.(item: T, globalIndex: Int, indexInPage: Int, items: List<T>, page: PageState<T>) -> Unit,
) {
    val pages by paginator.core.snapshot.collectAsStateWithLifecycle(initialValue = emptyList())
    val uiState = remember(pages) { pages.toUiState(isStarted = paginator.core.isStarted) }

    when (uiState) {
        is PaginatorUiState.Loading -> if (loadingContent != null) { loadingContent(); return }
        is PaginatorUiState.Empty -> if (emptyContent != null) { emptyContent(); return }
        is PaginatorUiState.Error -> if (errorContent != null) { errorContent(uiState.state); return }
        else -> Unit
    }

    val items = (uiState as? PaginatorUiState.Content<T>)?.items.orEmpty()

    CursorJumpScrollEffect(paginator, state, items, key, autoScrollToJumpTarget)

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
        prependErrorIndicator = prependErrorIndicator,
        appendErrorIndicator = appendErrorIndicator,
    ) {
        LazyRow(
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = contentPadding,
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment,
        ) {
            var start = 0
            for (pageState in pages) {
                val offset = start
                val data = pageState.data
                items(
                    count = data.size,
                    key = { i -> key(data[i]) },
                    contentType = { i -> contentType(data[i]) },
                ) { i ->
                    itemContent(data[i], offset + i, i, items, pageState)
                }
                start += data.size
            }
        }
    }
}
