package com.jamal_aliev.paginator.compose

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import com.jamal_aliev.paginator.CursorPaginator
import com.jamal_aliev.paginator.Paginator
import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.compose.internal.rememberCursorPaginatorDataItemCount
import com.jamal_aliev.paginator.compose.internal.rememberPaginatorDataItemCount
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.prefetch.CursorLoadGuard
import com.jamal_aliev.paginator.prefetch.CursorPaginatorPrefetchController
import com.jamal_aliev.paginator.prefetch.PageLoadGuard
import com.jamal_aliev.paginator.prefetch.PaginatorPrefetchController
import com.jamal_aliev.paginator.prefetch.PrefetchOptions

/**
 * Holder that owns the prefetch controller and live header/footer counts for a `LazyColumn`
 * pagination DSL session. Created by [Paginator.rememberPaginated] / [CursorPaginator.rememberPaginated].
 *
 * The counts are populated by the [paginated] DSL block on every composition — call sites do
 * not have to maintain `headerCount` / `footerCount` manually, so off-by-one bugs around the
 * append-loader item are eliminated.
 */
class PaginatedLazyListHolder<C : Any> internal constructor(
    val controller: C,
) {
    internal val headerCountState: MutableIntState = mutableIntStateOf(0)
    internal val footerCountState: MutableIntState = mutableIntStateOf(0)

    val headerCount: Int get() = headerCountState.intValue
    val footerCount: Int get() = footerCountState.intValue
}

/**
 * Receiver scope used inside [paginated]. Delegates everything to the underlying
 * [LazyListScope]; adds [header] / [appendIndicator] which behave like `item { … }` but also
 * tally header/footer counts so prefetch math always matches what the user actually rendered.
 */
class PaginatedLazyListScope @PublishedApi internal constructor(
    @PublishedApi internal val delegate: LazyListScope,
) : LazyListScope by delegate {

    @PublishedApi
    internal var headerCount: Int = 0

    @PublishedApi
    internal var footerCount: Int = 0

    /** Adds an item before the data range and counts it as a header. */
    fun header(
        key: Any? = null,
        contentType: Any? = null,
        content: @Composable LazyItemScope.() -> Unit,
    ) {
        delegate.item(key = key, contentType = contentType) { content() }
        headerCount++
    }

    /** Adds an item after the data range and counts it as a footer (e.g. append loader / retry). */
    fun appendIndicator(
        key: Any? = null,
        contentType: Any? = null,
        content: @Composable LazyItemScope.() -> Unit,
    ) {
        delegate.item(key = key, contentType = contentType) { content() }
        footerCount++
    }
}

/**
 * Builds the body of a paginated `LazyColumn`. Use inside a `LazyColumn(state = state) { … }`
 * block:
 *
 * ```
 * val paged = paginator.rememberPaginated(state = listState)
 * LazyColumn(state = listState) {
 *     paginated(paged) {
 *         header { StickyTitle() }
 *         items(uiState.items, key = { it.id }) { Row(it) }
 *         appendIndicator { AppendIndicator(uiState.appendState) }
 *     }
 * }
 * ```
 *
 * On each composition the block runs, the new header/footer counts are written back to
 * [holder]; if they change, the binding picks them up via Compose snapshot observation and
 * `dataItemCount` math stays in sync without any manual bookkeeping.
 */
fun LazyListScope.paginated(
    holder: PaginatedLazyListHolder<*>,
    block: PaginatedLazyListScope.() -> Unit,
) {
    val scope = PaginatedLazyListScope(this).apply(block)
    if (holder.headerCountState.intValue != scope.headerCount) {
        holder.headerCountState.intValue = scope.headerCount
    }
    if (holder.footerCountState.intValue != scope.footerCount) {
        holder.footerCountState.intValue = scope.footerCount
    }
}

/**
 * Auto-everything entry point for `LazyColumn` pagination.
 *
 * - `dataItemCount` is derived from [Paginator.uiState] (only the items size from
 *   [PaginatorUiState.Content] is observed via [derivedStateOf] — the rest of the state object
 *   is **not** in the recomposition path).
 * - `headerCount` / `footerCount` are filled in by the [paginated] DSL block.
 * - Runtime-mutable controller settings come from [options]; pass a hoisted [PrefetchOptions]
 *   to share configuration between screens.
 *
 * Pair with `LazyColumn(state) { paginated(holder) { … } }` — see [paginated] for the body
 * shape.
 */
@Composable
fun <T> Paginator<T>.rememberPaginated(
    state: LazyListState,
    options: PrefetchOptions = PrefetchOptions(),
    restartKey: Any? = null,
    onPrefetchError: ((Exception) -> Unit)? = null,
    loadGuard: PageLoadGuard<T> = PageLoadGuard.allowAll(),
): PaginatedLazyListHolder<PaginatorPrefetchController<T>> {
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
    val holder = remember(controller) { PaginatedLazyListHolder(controller) }
    val dataItemCount by rememberPaginatorDataItemCount(this)

    controller.BindToLazyList(
        listState = state,
        dataItemCount = dataItemCount,
        headerCount = holder.headerCount,
        footerCount = holder.footerCount,
        restartKey = restartKey,
        scrollSampleMillis = options.scrollSampleMillis,
    )
    return holder
}

/** Cursor-paginator counterpart of [Paginator.rememberPaginated]. */
@Composable
fun <T> CursorPaginator<T>.rememberPaginated(
    state: LazyListState,
    options: PrefetchOptions = PrefetchOptions(),
    restartKey: Any? = null,
    onPrefetchError: ((Exception) -> Unit)? = null,
    loadGuard: CursorLoadGuard<T> = CursorLoadGuard.allowAll(),
): PaginatedLazyListHolder<CursorPaginatorPrefetchController<T>> {
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
    val holder = remember(controller) { PaginatedLazyListHolder(controller) }
    val dataItemCount by rememberCursorPaginatorDataItemCount(this)

    controller.BindToLazyList(
        listState = state,
        dataItemCount = dataItemCount,
        headerCount = holder.headerCount,
        footerCount = holder.footerCount,
        restartKey = restartKey,
        scrollSampleMillis = options.scrollSampleMillis,
    )
    return holder
}
