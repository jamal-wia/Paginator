package com.jamal_aliev.paginator.compose.offset

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
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
 * Turnkey, scroll-anchor-safe paginated `LazyColumn` for an offset [Paginator].
 *
 * This is the batteries-included counterpart of the low-level [paginated] DSL: it collects
 * [Paginator.uiState], renders `Content.items` in a `LazyColumn`, and surfaces "loading previous /
 * next page" indicators as animated siblings **outside** the list via [PaginatorScrollEdgeIndicators],
 * so a page load never causes the list to jump (see that composable for the anchoring rationale).
 * Scroll-driven prefetch is wired automatically unless [prefetch] is `null`.
 *
 * [key] is **required**: a stable per-item key is what lets the list keep its scroll position when a
 * previous page is prepended (the first visible row must survive the data change). Prefer a real
 * item id; do not derive it from the list position.
 *
 * Full-screen states ([PaginatorUiState.Loading] / [Empty][PaginatorUiState.Empty] /
 * [Error][PaginatorUiState.Error]) render their optional slot when provided; otherwise the list is
 * shown empty. For fine-grained per-page control (custom headers, sticky sections, inline error
 * rows) use `rememberPaginated` + `LazyColumn { paginated(holder) { … } }` instead.
 *
 * ```
 * PaginatedLazyColumn(paginator, Modifier.fillMaxSize(), key = { it.id }) { item ->
 *     ItemRow(item)
 * }
 * ```
 */
@Composable
fun <T> PaginatedLazyColumn(
    paginator: Paginator<T>,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
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
    itemContent: @Composable LazyItemScope.(item: T) -> Unit,
) {
    val uiState by paginator.uiState.collectAsState(initial = PaginatorUiState.Idle)

    // Full-screen states render their slot (if any) in place of the list.
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
        listState = state,
        modifier = modifier,
        edgeGated = edgeGatedIndicators,
        prependIndicator = prependIndicator,
        appendIndicator = appendIndicator,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            state = state,
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
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
