package com.jamal_aliev.paginator.view

import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.jamal_aliev.paginator.prefetch.CursorPaginatorPrefetchController
import com.jamal_aliev.paginator.prefetch.PaginatorPrefetchController
import com.jamal_aliev.paginator.prefetch.remapIndices
import com.jamal_aliev.paginator.view.internal.ScrollSignal
import com.jamal_aliev.paginator.view.internal.readScrollSignal

/**
 * Binds this [PaginatorPrefetchController] to a [RecyclerView] so that scroll-driven prefetch
 * happens automatically — without manually wiring an `OnScrollListener` and reading
 * `findFirst/LastVisibleItemPosition` the way the platform-agnostic guide in
 * `docs/7. prefetch.md` demonstrates.
 *
 * The binding installs:
 *
 * 1. An [RecyclerView.OnScrollListener] that fires on every scroll delta — drives prefetch while
 *    the user is dragging or flinging.
 * 2. A [View.OnLayoutChangeListener] that fires on every layout pass — drives prefetch when the
 *    **first page is shorter than the viewport** and the user can't scroll. Without this, the
 *    controller would never be triggered and append-pagination would stall on partial first pages.
 *    Also covers viewport resizes (rotation, keyboard, multi-window).
 *
 * Both paths read the current visible window via [readScrollSignal], remap indices via
 * [remapIndices] (subtracting [headerCount] and clamping the data range), and forward to
 * [PaginatorPrefetchController.onScroll]. The result is **deduplicated** so a layout-pass that
 * carries the same indices as the previous scroll event is a no-op.
 *
 * On the [LifecycleOwner]'s `ON_DESTROY` event the binding removes both listeners. The
 * [PaginatorPrefetchController] itself is **not** cancelled here — controllers are typically
 * scoped to a `ViewModel` and outlive the view. Use the lifecycle-aware overload
 * [com.jamal_aliev.paginator.view.prefetchController] to cancel the controller alongside the
 * view, or call [PaginatorPrefetchController.cancel] yourself.
 *
 * The [dataItemCount] / [headerCount] / [footerCount] lambdas are read **on every dispatch** —
 * the latest values are always used. This matches the Compose binding's `rememberUpdatedState`
 * behaviour without requiring the user to hold any state.
 *
 * @param recyclerView The [RecyclerView] to observe. Must already have a [RecyclerView.adapter]
 *   and [RecyclerView.layoutManager] set; the binding does not require them to be non-null at
 *   call time, but [readScrollSignal] will throw if [RecyclerView.layoutManager] is missing when
 *   the first dispatch happens.
 * @param lifecycleOwner The lifecycle that scopes the listener registration. Listeners are
 *   removed on `ON_DESTROY`; if the lifecycle is already destroyed, the binding is a no-op.
 * @param dataItemCount Lambda returning the number of **data** items currently in the adapter
 *   (excluding headers, footers, dividers, sticky labels, and loading indicators).
 * @param headerCount Lambda returning the number of non-data items rendered **before** the data
 *   range. Defaults to `0`.
 * @param footerCount Lambda returning the number of non-data items rendered **after** the data
 *   range. Currently accepted for self-documentation; the math relies on [dataItemCount] and
 *   [headerCount].
 */
fun PaginatorPrefetchController<*>.bindToRecyclerView(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    dataItemCount: () -> Int,
    headerCount: () -> Int = { 0 },
    footerCount: () -> Int = { 0 },
) {
    bindInternal(
        recyclerView = recyclerView,
        lifecycleOwner = lifecycleOwner,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        footerCount = footerCount,
        onScroll = ::onScroll,
    )
}

/**
 * Convenience overload of [bindToRecyclerView] taking [Int] constants instead of lambdas. Use
 * this when the header/footer/data layout is static and the values don't depend on per-frame
 * state. For dynamic layouts (e.g., a sticky header that appears only after the first page
 * loads), prefer the lambda-based overload.
 *
 * Note that [dataItemCount] is still a lambda here — it almost always changes as pages load.
 */
fun PaginatorPrefetchController<*>.bindToRecyclerView(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    dataItemCount: () -> Int,
    headerCount: Int,
    footerCount: Int = 0,
) {
    bindToRecyclerView(
        recyclerView = recyclerView,
        lifecycleOwner = lifecycleOwner,
        dataItemCount = dataItemCount,
        headerCount = { headerCount },
        footerCount = { footerCount },
    )
}

/**
 * Cursor-paginator counterpart of [PaginatorPrefetchController.bindToRecyclerView].
 *
 * Behaviour, parameters, and constraints are identical — see the page-based overload's KDoc.
 */
fun CursorPaginatorPrefetchController<*>.bindToRecyclerView(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    dataItemCount: () -> Int,
    headerCount: () -> Int = { 0 },
    footerCount: () -> Int = { 0 },
) {
    bindInternal(
        recyclerView = recyclerView,
        lifecycleOwner = lifecycleOwner,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        footerCount = footerCount,
        onScroll = ::onScroll,
    )
}

/**
 * Cursor-paginator [Int]-overload of [bindToRecyclerView]. See the lambda overload's KDoc and the
 * page-based [PaginatorPrefetchController.bindToRecyclerView] for the full contract.
 */
fun CursorPaginatorPrefetchController<*>.bindToRecyclerView(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    dataItemCount: () -> Int,
    headerCount: Int,
    footerCount: Int = 0,
) {
    bindToRecyclerView(
        recyclerView = recyclerView,
        lifecycleOwner = lifecycleOwner,
        dataItemCount = dataItemCount,
        headerCount = { headerCount },
        footerCount = { footerCount },
    )
}

private fun bindInternal(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    dataItemCount: () -> Int,
    headerCount: () -> Int,
    @Suppress("UNUSED_PARAMETER") footerCount: () -> Int,
    onScroll: (firstVisibleIndex: Int, lastVisibleIndex: Int, totalItemCount: Int) -> Unit,
) {
    if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) return

    var lastEmitted: ScrollSignal? = null

    fun dispatch() {
        if (recyclerView.layoutManager == null) return
        val signal = recyclerView.readScrollSignal()
        if (signal == lastEmitted) return
        val remapped = remapIndices(
            firstVisibleIndex = signal.firstVisibleIndex,
            lastVisibleIndex = signal.lastVisibleIndex,
            totalItemCount = signal.totalItemCount,
            dataItemCount = dataItemCount(),
            headerCount = headerCount(),
        ) ?: run {
            lastEmitted = signal
            return
        }
        lastEmitted = signal
        onScroll(
            remapped.firstVisibleIndex,
            remapped.lastVisibleIndex,
            remapped.totalItemCount,
        )
    }

    val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
            dispatch()
        }
    }

    val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        dispatch()
    }

    recyclerView.addOnScrollListener(scrollListener)
    recyclerView.addOnLayoutChangeListener(layoutListener)

    lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            recyclerView.removeOnScrollListener(scrollListener)
            recyclerView.removeOnLayoutChangeListener(layoutListener)
            owner.lifecycle.removeObserver(this)
        }
    })
}
