package com.jamal_aliev.paginator.compose.cursor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jamal_aliev.paginator.compose.core.PaginatorLoadingIndicator
import com.jamal_aliev.paginator.compose.core.PaginatorScrollEdgeIndicators
import com.jamal_aliev.paginator.core.extension.isProgressState
import com.jamal_aliev.paginator.core.navigation.PaginatorNavigationEvent
import com.jamal_aliev.paginator.core.page.PageState
import com.jamal_aliev.paginator.core.page.PaginatorUiState
import com.jamal_aliev.paginator.core.prefetch.PrefetchOptions
import com.jamal_aliev.paginator.cursor.CursorPaginator
import com.jamal_aliev.paginator.cursor.extension.uiState
import com.jamal_aliev.paginator.cursor.prefetch.CursorLoadGuard

/**
 * Turnkey, scroll-anchor-safe paginated `LazyColumn` for a [CursorPaginator]. Cursor counterpart of
 * the offset `PaginatedLazyColumn`.
 *
 * It collects [CursorPaginator.uiState], renders `Content.items`, surfaces "loading previous / next
 * page" indicators as animated siblings **outside** the list, and wires scroll-driven prefetch
 * (unless [prefetch] is `null`). When [autoScrollToJumpTarget] is `true` (default) the list scrolls
 * to the jumped-to page whenever a `jump` / `jumpForward` / `jumpBack` lands — wherever it was
 * triggered, including from a `ViewModel` — by observing [CursorPaginator.navigationEvents].
 *
 * [key] is **required**: a stable per-item key preserves the scroll position on prepend and locates
 * a jumped-to page. Full-screen `Loading` / `Empty` / `Error` states render their optional slot when
 * provided; for fine-grained per-page control use `rememberPaginated` + the `paginated { }` DSL.
 */
@Composable
fun <K : Any, T> PaginatedLazyColumn(
    paginator: CursorPaginator<K, T>,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    prefetch: PrefetchOptions? = PrefetchOptions(),
    onPrefetchError: ((Exception) -> Unit)? = null,
    loadGuard: CursorLoadGuard<K, T> = CursorLoadGuard.allowAll(),
    edgeGatedIndicators: Boolean = true,
    autoScrollToJumpTarget: Boolean = true,
    contentType: (item: T) -> Any? = { null },
    prependIndicator: @Composable (PageState.ProgressState<T>) -> Unit = { PaginatorLoadingIndicator() },
    appendIndicator: @Composable (PageState.ProgressState<T>) -> Unit = { PaginatorLoadingIndicator() },
    loadingContent: (@Composable () -> Unit)? = null,
    emptyContent: (@Composable () -> Unit)? = null,
    errorContent: (@Composable (PageState.ErrorState<T>) -> Unit)? = null,
    key: (item: T) -> Any,
    itemContent: @Composable LazyItemScope.(item: T) -> Unit,
) {
    val uiState by paginator.uiState.collectAsStateWithLifecycle(initialValue = PaginatorUiState.Idle)

    when (val current = uiState) {
        is PaginatorUiState.Loading -> if (loadingContent != null) { loadingContent(); return }
        is PaginatorUiState.Empty -> if (emptyContent != null) { emptyContent(); return }
        is PaginatorUiState.Error -> if (errorContent != null) { errorContent(current.state); return }
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
        edgeGated = edgeGatedIndicators,
        prependIndicator = prependIndicator,
        appendIndicator = appendIndicator,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
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

/**
 * Observes [CursorPaginator.navigationEvents] and scrolls [state] to the jumped-to page once it
 * lands. The target may sit anywhere in the reset window (cached neighbours are absorbed), so it is
 * located by the key of its first item rather than assuming index 0; the request is held until the
 * page is terminal so a later (post-absorption) snapshot can re-correct the position.
 */
@Composable
internal fun <K : Any, T> CursorJumpScrollEffect(
    paginator: CursorPaginator<K, T>,
    state: LazyListState,
    items: List<T>,
    key: (item: T) -> Any,
    enabled: Boolean,
) {
    var pendingScrollKey by remember(paginator) { mutableStateOf<K?>(null) }

    LaunchedEffect(paginator, enabled) {
        if (!enabled) return@LaunchedEffect
        paginator.navigationEvents.collect { event ->
            if (event is PaginatorNavigationEvent.Jumped) pendingScrollKey = event.target.self
        }
    }

    LaunchedEffect(pendingScrollKey, items) {
        if (!enabled) return@LaunchedEffect
        val self = pendingScrollKey ?: return@LaunchedEffect
        val pageState = paginator.core.getStateOf(self)
        val firstItem = pageState?.data?.firstOrNull()
        if (firstItem != null) {
            val targetKey = key(firstItem)
            val index = items.indexOfFirst { key(it) == targetKey }
            if (index >= 0) state.scrollToItem(index)
        }
        if (pageState != null && !pageState.isProgressState()) pendingScrollKey = null
    }
}
