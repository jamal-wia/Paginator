package com.jamal_aliev.paginator.compose

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridItemScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
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

/** `LazyStaggeredGrid` analogue of [PaginatedLazyListHolder] — see that type for the contract. */
class PaginatedLazyStaggeredGridHolder<C : Any> internal constructor(
    val controller: C,
) {
    internal val headerCountState: MutableIntState = mutableIntStateOf(0)
    internal val footerCountState: MutableIntState = mutableIntStateOf(0)

    val headerCount: Int get() = headerCountState.intValue
    val footerCount: Int get() = footerCountState.intValue
}

/**
 * Receiver scope used inside [paginated] for `LazyStaggeredGrid`. [header] / [appendIndicator]
 * default to [StaggeredGridItemSpan.FullLine] — the typical layout for non-data items.
 *
 * `LazyStaggeredGridScope` is `sealed` in Compose, so we can't implement it directly here;
 * instead the common entry points (`item`, `items`, `itemsIndexed`) are re-exposed as members
 * / extensions that delegate to the underlying scope.
 */
class PaginatedLazyStaggeredGridScope @PublishedApi internal constructor(
    @PublishedApi internal val delegate: LazyStaggeredGridScope,
) {
    @PublishedApi
    internal var headerCount: Int = 0
    @PublishedApi
    internal var footerCount: Int = 0

    fun header(
        key: Any? = null,
        contentType: Any? = null,
        span: StaggeredGridItemSpan = StaggeredGridItemSpan.FullLine,
        content: @Composable LazyStaggeredGridItemScope.() -> Unit,
    ) {
        delegate.item(key = key, contentType = contentType, span = span, content = content)
        headerCount++
    }

    fun appendIndicator(
        key: Any? = null,
        contentType: Any? = null,
        span: StaggeredGridItemSpan = StaggeredGridItemSpan.FullLine,
        content: @Composable LazyStaggeredGridItemScope.() -> Unit,
    ) {
        delegate.item(key = key, contentType = contentType, span = span, content = content)
        footerCount++
    }

    fun item(
        key: Any? = null,
        contentType: Any? = null,
        span: StaggeredGridItemSpan? = null,
        content: @Composable LazyStaggeredGridItemScope.() -> Unit,
    ) = delegate.item(key = key, contentType = contentType, span = span, content = content)

    fun items(
        count: Int,
        key: ((index: Int) -> Any)? = null,
        contentType: (index: Int) -> Any? = { null },
        span: ((index: Int) -> StaggeredGridItemSpan)? = null,
        itemContent: @Composable LazyStaggeredGridItemScope.(index: Int) -> Unit,
    ) = delegate.items(
        count = count,
        key = key,
        contentType = contentType,
        span = span,
        itemContent = itemContent,
    )
}

/** `items(List<T>, …)` shortcut for [PaginatedLazyStaggeredGridScope]. */
inline fun <T> PaginatedLazyStaggeredGridScope.items(
    items: List<T>,
    noinline key: ((item: T) -> Any)? = null,
    noinline contentType: (item: T) -> Any? = { null },
    noinline span: ((item: T) -> StaggeredGridItemSpan)? = null,
    crossinline itemContent: @Composable LazyStaggeredGridItemScope.(item: T) -> Unit,
) = delegate.items(
    items = items,
    key = key,
    contentType = contentType,
    span = span,
    itemContent = itemContent,
)

/** `itemsIndexed(List<T>, …)` shortcut for [PaginatedLazyStaggeredGridScope]. */
inline fun <T> PaginatedLazyStaggeredGridScope.itemsIndexed(
    items: List<T>,
    noinline key: ((index: Int, item: T) -> Any)? = null,
    crossinline contentType: (index: Int, item: T) -> Any? = { _, _ -> null },
    noinline span: ((index: Int, item: T) -> StaggeredGridItemSpan)? = null,
    crossinline itemContent: @Composable LazyStaggeredGridItemScope.(index: Int, item: T) -> Unit,
) = delegate.itemsIndexed(
    items = items,
    key = key,
    contentType = contentType,
    span = span,
    itemContent = itemContent,
)

/** [LazyListScope.paginated] analogue for `LazyStaggeredGrid`. */
fun LazyStaggeredGridScope.paginated(
    holder: PaginatedLazyStaggeredGridHolder<*>,
    block: PaginatedLazyStaggeredGridScope.() -> Unit,
) {
    val scope = PaginatedLazyStaggeredGridScope(this).apply(block)
    if (holder.headerCountState.intValue != scope.headerCount) {
        holder.headerCountState.intValue = scope.headerCount
    }
    if (holder.footerCountState.intValue != scope.footerCount) {
        holder.footerCountState.intValue = scope.footerCount
    }
}

/** [Paginator.rememberPaginated] for staggered grids. */
@Composable
fun <T> Paginator<T>.rememberPaginated(
    state: LazyStaggeredGridState,
    options: PrefetchOptions = PrefetchOptions(),
    restartKey: Any? = null,
    onPrefetchError: ((Exception) -> Unit)? = null,
    loadGuard: PageLoadGuard<T> = PageLoadGuard.allowAll(),
): PaginatedLazyStaggeredGridHolder<PaginatorPrefetchController<T>> {
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
    val holder = remember(controller) { PaginatedLazyStaggeredGridHolder(controller) }
    val dataItemCount by rememberPaginatorDataItemCount(this)

    controller.BindToLazyStaggeredGrid(
        gridState = state,
        dataItemCount = dataItemCount,
        headerCount = holder.headerCount,
        footerCount = holder.footerCount,
        restartKey = restartKey,
        scrollSampleMillis = options.scrollSampleMillis,
    )
    return holder
}

/** Cursor-paginator counterpart of [Paginator.rememberPaginated] for staggered grids. */
@Composable
fun <T> CursorPaginator<T>.rememberPaginated(
    state: LazyStaggeredGridState,
    options: PrefetchOptions = PrefetchOptions(),
    restartKey: Any? = null,
    onPrefetchError: ((Exception) -> Unit)? = null,
    loadGuard: CursorLoadGuard<T> = CursorLoadGuard.allowAll(),
): PaginatedLazyStaggeredGridHolder<CursorPaginatorPrefetchController<T>> {
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
    val holder = remember(controller) { PaginatedLazyStaggeredGridHolder(controller) }
    val dataItemCount by rememberCursorPaginatorDataItemCount(this)

    controller.BindToLazyStaggeredGrid(
        gridState = state,
        dataItemCount = dataItemCount,
        headerCount = holder.headerCount,
        footerCount = holder.footerCount,
        restartKey = restartKey,
        scrollSampleMillis = options.scrollSampleMillis,
    )
    return holder
}
