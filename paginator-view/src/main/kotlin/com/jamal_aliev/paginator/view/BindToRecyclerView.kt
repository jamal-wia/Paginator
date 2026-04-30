package com.jamal_aliev.paginator.view

import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.jamal_aliev.paginator.prefetch.CursorPaginatorPrefetchController
import com.jamal_aliev.paginator.prefetch.PaginatorPrefetchController
import com.jamal_aliev.paginator.view.internal.ScrollDispatcher

/**
 * Binds this [PaginatorPrefetchController] to a [RecyclerView] so that scroll-driven prefetch
 * happens automatically — without manually wiring an `OnScrollListener` and reading
 * `findFirst/LastVisibleItemPosition` the way the platform-agnostic guide in
 * `docs/7. prefetch.md` demonstrates.
 *
 * The binding installs:
 *
 * 1. An [RecyclerView.OnScrollListener] — drives prefetch while the user is dragging or flinging.
 * 2. A [android.view.View.OnLayoutChangeListener] — drives prefetch when the **first page is
 *    shorter than the viewport** and the user can't scroll. Without this, the controller would
 *    never be triggered and append-pagination would stall on partial first pages. Also covers
 *    viewport resizes (rotation, keyboard, multi-window).
 *
 * Both paths read the current visible window, remap indices by subtracting [headerCount] and
 * clamping the data range, and forward to [PaginatorPrefetchController.onScroll]. Results are
 * **deduplicated** so a layout pass that carries the same indices as the previous scroll event
 * is a no-op.
 *
 * The [dataItemCount] / [headerCount] / [footerCount] lambdas are read **on every dispatch** —
 * the latest values are always used. This matches the Compose binding's `rememberUpdatedState`
 * behaviour without requiring the caller to hold any state.
 *
 * On the [lifecycleOwner]'s `ON_DESTROY` event the binding removes both listeners. The
 * [PaginatorPrefetchController] itself is **not** cancelled here — controllers are typically
 * scoped to a `ViewModel` and outlive the view. Use the lifecycle-aware overload
 * [com.jamal_aliev.paginator.view.prefetchController] to cancel the controller alongside the
 * view, or call [PaginatorPrefetchController.cancel] yourself.
 *
 * @param recyclerView The [RecyclerView] to observe. Must already have a `layoutManager` set
 *   when the first dispatch happens; supported managers are `LinearLayoutManager`,
 *   `GridLayoutManager`, and `StaggeredGridLayoutManager`.
 * @param lifecycleOwner Lifecycle that scopes the listener registration. Listeners are removed
 *   on `ON_DESTROY`; if the lifecycle is already destroyed, the binding is a no-op.
 * @param dataItemCount Lambda returning the number of **data** items currently in the adapter
 *   (excluding headers, footers, dividers, sticky labels, and loading indicators).
 * @param headerCount Lambda returning the number of non-data items rendered **before** the data
 *   range. Defaults to `0`.
 * @param footerCount Lambda returning the number of non-data items rendered **after** the data
 *   range. Currently accepted for self-documentation; the math relies on [dataItemCount] and
 *   [headerCount].
 * @param scrollSampleMillis When > 0, throttles the scroll-and-layout signal to one emission
 *   per window via [kotlinx.coroutines.flow.sample]. Useful when [PaginatorPrefetchController]'s
 *   `loadGuard` or downstream work is non-trivial and the user can scroll fast. Defaults to `0`
 *   (no throttling).
 * @return A [ScrollBinding] handle. Call [ScrollBinding.recalibrate] after a manual
 *   `paginator.refresh()` / `paginator.jump()`, or [ScrollBinding.unbind] to detach early.
 */
public fun PaginatorPrefetchController<*>.bindToRecyclerView(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    dataItemCount: () -> Int,
    headerCount: () -> Int = ZERO,
    footerCount: () -> Int = ZERO,
    scrollSampleMillis: Long = 0L,
): ScrollBinding = bindInternal(
    recyclerView = recyclerView,
    lifecycleOwner = lifecycleOwner,
    dataItemCount = dataItemCount,
    headerCount = headerCount,
    footerCount = footerCount,
    scrollSampleMillis = scrollSampleMillis,
    onScroll = ::onScroll,
)

/**
 * Convenience overload of [bindToRecyclerView] taking [Int] constants instead of lambdas. Use
 * this when the header/footer layout is static (e.g., one sticky title and one append loader).
 * For dynamic layouts, prefer the lambda-based overload — its values are read on every dispatch.
 *
 * Note that [dataItemCount] is still a lambda here — it almost always changes as pages load.
 */
public fun PaginatorPrefetchController<*>.bindToRecyclerView(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    dataItemCount: () -> Int,
    headerCount: Int,
    footerCount: Int = 0,
    scrollSampleMillis: Long = 0L,
): ScrollBinding = bindToRecyclerView(
    recyclerView = recyclerView,
    lifecycleOwner = lifecycleOwner,
    dataItemCount = dataItemCount,
    headerCount = { headerCount },
    footerCount = { footerCount },
    scrollSampleMillis = scrollSampleMillis,
)

/**
 * Cursor-paginator counterpart of [PaginatorPrefetchController.bindToRecyclerView].
 *
 * Behaviour, parameters, and constraints are identical — see the page-based overload's KDoc.
 */
public fun CursorPaginatorPrefetchController<*>.bindToRecyclerView(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    dataItemCount: () -> Int,
    headerCount: () -> Int = ZERO,
    footerCount: () -> Int = ZERO,
    scrollSampleMillis: Long = 0L,
): ScrollBinding = bindInternal(
    recyclerView = recyclerView,
    lifecycleOwner = lifecycleOwner,
    dataItemCount = dataItemCount,
    headerCount = headerCount,
    footerCount = footerCount,
    scrollSampleMillis = scrollSampleMillis,
    onScroll = ::onScroll,
)

/**
 * Cursor-paginator [Int]-overload of [bindToRecyclerView]. See the lambda overload's KDoc and
 * the page-based [PaginatorPrefetchController.bindToRecyclerView] for the full contract.
 */
public fun CursorPaginatorPrefetchController<*>.bindToRecyclerView(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    dataItemCount: () -> Int,
    headerCount: Int,
    footerCount: Int = 0,
    scrollSampleMillis: Long = 0L,
): ScrollBinding = bindToRecyclerView(
    recyclerView = recyclerView,
    lifecycleOwner = lifecycleOwner,
    dataItemCount = dataItemCount,
    headerCount = { headerCount },
    footerCount = { footerCount },
    scrollSampleMillis = scrollSampleMillis,
)

private val ZERO: () -> Int = { 0 }

private fun bindInternal(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    dataItemCount: () -> Int,
    headerCount: () -> Int,
    @Suppress("UNUSED_PARAMETER") footerCount: () -> Int,
    scrollSampleMillis: Long,
    onScroll: (firstVisibleIndex: Int, lastVisibleIndex: Int, totalItemCount: Int) -> Unit,
): ScrollBinding {
    val dispatcher = ScrollDispatcher(
        recyclerView = recyclerView,
        lifecycleOwner = lifecycleOwner,
        scrollSampleMillis = scrollSampleMillis,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        onScroll = onScroll,
    )
    dispatcher.start()
    return dispatcher
}
