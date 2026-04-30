package com.jamal_aliev.paginator.compose

import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import com.jamal_aliev.paginator.CursorPaginator
import com.jamal_aliev.paginator.Paginator
import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.compose.internal.rememberCursorPaginatorDataItemCount
import com.jamal_aliev.paginator.compose.internal.rememberPaginatorDataItemCount
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.prefetch.CursorPaginatorPrefetchController
import com.jamal_aliev.paginator.prefetch.PaginatorPrefetchController

/** `LazyGrid` analogue of [PaginatedLazyListHolder] — see that type for the contract. */
class PaginatedLazyGridHolder<C : Any> internal constructor(
    val controller: C,
) {
    internal val headerCountState: MutableIntState = mutableIntStateOf(0)
    internal val footerCountState: MutableIntState = mutableIntStateOf(0)

    val headerCount: Int get() = headerCountState.intValue
    val footerCount: Int get() = footerCountState.intValue
}

/**
 * Receiver scope used inside [paginated] for `LazyGrid`. [header] / [appendIndicator] default
 * to a full-line span (`GridItemSpan(maxLineSpan)`) — the typical layout for non-data items.
 *
 * `LazyGridScope` is `sealed` in Compose, so we can't implement it directly here; instead the
 * common entry points (`item`, `items`, `itemsIndexed`) are re-exposed as members / extensions
 * that delegate to the underlying scope. Anything that's not re-exposed is reachable via
 * [delegate] in the same module.
 */
class PaginatedLazyGridScope @PublishedApi internal constructor(
    @PublishedApi internal val delegate: LazyGridScope,
) {
    @PublishedApi
    internal var headerCount: Int = 0
    @PublishedApi
    internal var footerCount: Int = 0

    fun header(
        key: Any? = null,
        span: (LazyGridItemSpanScope.() -> GridItemSpan)? = FullLine,
        contentType: Any? = null,
        content: @Composable LazyGridItemScope.() -> Unit,
    ) {
        delegate.item(key = key, span = span, contentType = contentType, content = content)
        headerCount++
    }

    fun appendIndicator(
        key: Any? = null,
        span: (LazyGridItemSpanScope.() -> GridItemSpan)? = FullLine,
        contentType: Any? = null,
        content: @Composable LazyGridItemScope.() -> Unit,
    ) {
        delegate.item(key = key, span = span, contentType = contentType, content = content)
        footerCount++
    }

    fun item(
        key: Any? = null,
        span: (LazyGridItemSpanScope.() -> GridItemSpan)? = null,
        contentType: Any? = null,
        content: @Composable LazyGridItemScope.() -> Unit,
    ) = delegate.item(key = key, span = span, contentType = contentType, content = content)

    fun items(
        count: Int,
        key: ((index: Int) -> Any)? = null,
        span: (LazyGridItemSpanScope.(Int) -> GridItemSpan)? = null,
        contentType: (index: Int) -> Any? = { null },
        itemContent: @Composable LazyGridItemScope.(index: Int) -> Unit,
    ) = delegate.items(
        count = count,
        key = key,
        span = span,
        contentType = contentType,
        itemContent = itemContent,
    )

    private companion object {
        private val FullLine: LazyGridItemSpanScope.() -> GridItemSpan =
            { GridItemSpan(maxLineSpan) }
    }
}

/** `items(List<T>, …)` shortcut for [PaginatedLazyGridScope]. */
inline fun <T> PaginatedLazyGridScope.items(
    items: List<T>,
    noinline key: ((item: T) -> Any)? = null,
    noinline span: (LazyGridItemSpanScope.(item: T) -> GridItemSpan)? = null,
    noinline contentType: (item: T) -> Any? = { null },
    crossinline itemContent: @Composable LazyGridItemScope.(item: T) -> Unit,
) = delegate.items(
    items = items,
    key = key,
    span = span,
    contentType = contentType,
    itemContent = itemContent,
)

/** `itemsIndexed(List<T>, …)` shortcut for [PaginatedLazyGridScope]. */
inline fun <T> PaginatedLazyGridScope.itemsIndexed(
    items: List<T>,
    noinline key: ((index: Int, item: T) -> Any)? = null,
    noinline span: (LazyGridItemSpanScope.(index: Int, item: T) -> GridItemSpan)? = null,
    crossinline contentType: (index: Int, item: T) -> Any? = { _, _ -> null },
    crossinline itemContent: @Composable LazyGridItemScope.(index: Int, item: T) -> Unit,
) = delegate.itemsIndexed(
    items = items,
    key = key,
    span = span,
    contentType = contentType,
    itemContent = itemContent,
)

/** [LazyListScope.paginated] analogue for `LazyGrid`. */
fun LazyGridScope.paginated(
    holder: PaginatedLazyGridHolder<*>,
    block: PaginatedLazyGridScope.() -> Unit,
) {
    val scope = PaginatedLazyGridScope(this).apply(block)
    if (holder.headerCountState.intValue != scope.headerCount) {
        holder.headerCountState.intValue = scope.headerCount
    }
    if (holder.footerCountState.intValue != scope.footerCount) {
        holder.footerCountState.intValue = scope.footerCount
    }
}

/** [Paginator.rememberPaginated] for `LazyVerticalGrid` / `LazyHorizontalGrid`. */
@Composable
fun <T> Paginator<T>.rememberPaginated(
    state: LazyGridState,
    options: PrefetchOptions = PrefetchOptions(),
    restartKey: Any? = null,
    onPrefetchError: ((Exception) -> Unit)? = null,
    loadGuard: PageLoadGuard<T> = PageLoadGuard.allowAll(),
): PaginatedLazyGridHolder<PaginatorPrefetchController<T>> {
    val controller = rememberPrefetchController(
        prefetchDistance = options.prefetchDistance,
        enableBackwardPrefetch = options.enableBackwardPrefetch,
        silentlyLoading = options.silentlyLoading,
        silentlyResult = options.silentlyResult,
        enabled = options.enabled,
        cancelOnDispose = options.cancelOnDispose,
        loadGuard = { page: Int, st: PageState<T>? -> loadGuard(page, st) },
        onPrefetchError = onPrefetchError,
    )
    val holder = remember(controller) { PaginatedLazyGridHolder(controller) }
    val dataItemCount by rememberPaginatorDataItemCount(this)

    controller.BindToLazyGrid(
        gridState = state,
        dataItemCount = dataItemCount,
        headerCount = holder.headerCount,
        footerCount = holder.footerCount,
        restartKey = restartKey,
        scrollSampleMillis = options.scrollSampleMillis,
    )
    return holder
}

/** Cursor-paginator counterpart of [Paginator.rememberPaginated] for grids. */
@Composable
fun <T> CursorPaginator<T>.rememberPaginated(
    state: LazyGridState,
    options: PrefetchOptions = PrefetchOptions(),
    restartKey: Any? = null,
    onPrefetchError: ((Exception) -> Unit)? = null,
    loadGuard: CursorLoadGuard<T> = CursorLoadGuard.allowAll(),
): PaginatedLazyGridHolder<CursorPaginatorPrefetchController<T>> {
    val controller = rememberPrefetchController(
        prefetchDistance = options.prefetchDistance,
        enableBackwardPrefetch = options.enableBackwardPrefetch,
        silentlyLoading = options.silentlyLoading,
        silentlyResult = options.silentlyResult,
        enabled = options.enabled,
        cancelOnDispose = options.cancelOnDispose,
        loadGuard = { cursor: CursorBookmark, st: PageState<T>? -> loadGuard(cursor, st) },
        onPrefetchError = onPrefetchError,
    )
    val holder = remember(controller) { PaginatedLazyGridHolder(controller) }
    val dataItemCount by rememberCursorPaginatorDataItemCount(this)

    controller.BindToLazyGrid(
        gridState = state,
        dataItemCount = dataItemCount,
        headerCount = holder.headerCount,
        footerCount = holder.footerCount,
        restartKey = restartKey,
        scrollSampleMillis = options.scrollSampleMillis,
    )
    return holder
}
