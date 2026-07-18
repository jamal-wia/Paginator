package com.jamal_aliev.paginator.compose.offset

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
 * **Scroll-on-jump.** When [autoScrollToJumpTarget] is `true` (default) the list scrolls to the
 * jumped-to page whenever a `jump` / `jumpForward` / `jumpBack` lands — no matter where it was
 * triggered, including `paginator.jump(...)` called from a `ViewModel` — by observing
 * [Paginator.jumpEvents]. You keep driving navigation with the plain paginator; no scroll plumbing.
 * `goNextPage` / `goPreviousPage` never scroll (appending keeps the anchor; a prepended page is
 * inserted past the edge with the position preserved).
 *
 * [key] is **required**: a stable per-item key lets the list keep its scroll position when a
 * previous page is prepended, and locates a jumped-to page. Full-screen [PaginatorUiState.Loading] /
 * [Empty][PaginatorUiState.Empty] / [Error][PaginatorUiState.Error] states render their optional
 * slot when provided. For fine-grained per-page control use `rememberPaginated` +
 * `LazyColumn { paginated(holder) { … } }` instead.
 *
 * ```
 * // ViewModel: fun openDeeplink(page: Int) = viewModelScope.launch { paginator.jump(BookmarkInt(page)) }
 * PaginatedLazyColumn(paginator, Modifier.fillMaxSize(), key = { it.id }) { item -> ItemRow(item) }
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
 * Observes [Paginator.jumpEvents] and scrolls [state] to the jumped-to page once it lands. The
 * target may sit anywhere in the reset window (cached neighbours are absorbed), so it is located by
 * the key of its first item rather than assuming index 0; the request is held until the page is
 * terminal so a later (post-absorption) snapshot can re-correct the position.
 */
@Composable
internal fun <T> JumpScrollEffect(
    paginator: Paginator<T>,
    state: LazyListState,
    items: List<T>,
    key: (item: T) -> Any,
    enabled: Boolean,
) {
    var pendingScrollPage by remember(paginator) { mutableStateOf<Int?>(null) }

    LaunchedEffect(paginator, enabled) {
        if (!enabled) return@LaunchedEffect
        paginator.navigationEvents.collect { event ->
            if (event is PaginatorNavigationEvent.Jumped) pendingScrollPage = event.target.page
        }
    }

    LaunchedEffect(pendingScrollPage, items) {
        if (!enabled) return@LaunchedEffect
        val page = pendingScrollPage ?: return@LaunchedEffect
        val pageState = paginator.core.getStateOf(page)
        val firstItem = pageState?.data?.firstOrNull()
        if (firstItem != null) {
            val targetKey = key(firstItem)
            val index = items.indexOfFirst { key(it) == targetKey }
            if (index >= 0) state.scrollToItem(index)
        }
        if (pageState != null && !pageState.isProgressState()) pendingScrollPage = null
    }
}
