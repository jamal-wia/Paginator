package com.jamal_aliev.paginator.view.internal

import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager

/**
 * Snapshot of the data we read from a [RecyclerView] on every scroll / data-change pass.
 *
 * Mirrors the shape of the indices the [com.jamal_aliev.paginator.prefetch.PaginatorPrefetchController]
 * accepts, but in **full-list** coordinates (headers + footers included). The remapping into
 * data-only indices is done by [com.jamal_aliev.paginator.prefetch.ScrollWindow.Companion.from] at the call site.
 */
internal data class ScrollSignal(
    val firstVisibleIndex: Int,
    val lastVisibleIndex: Int,
)

/**
 * Reads the current visible-index window and total item count from a [RecyclerView] by dispatching
 * on its [RecyclerView.LayoutManager]:
 *
 * - [LinearLayoutManager] (and its [GridLayoutManager] subclass): uses `findFirstVisibleItemPosition`
 *   / `findLastVisibleItemPosition`. These return indices into the adapter's full item list, which
 *   is exactly what we need before remapping.
 * - [StaggeredGridLayoutManager]: lanes can render items out of linear order, so we collapse
 *   `findFirstVisibleItemPositions(null)` / `findLastVisibleItemPositions(null)` to the global
 *   `min` / `max`. The **range** between them still bounds the visible items, which is what the
 *   controller's edge-distance check needs (the comment mirrors the Compose binding's behaviour).
 *
 * Returns a signal with `firstVisibleIndex = -1` / `lastVisibleIndex = -1` if the layout has not
 * positioned anything yet (e.g., before first measure pass) — the controller's negative-index
 * guard rejects this naturally, and `ScrollWindow.from` returns `ScrollWindow.NONE`.
 *
 * @throws IllegalStateException if the [RecyclerView] has no [RecyclerView.LayoutManager], or one
 *   that this binding does not support.
 */
internal fun RecyclerView.readScrollSignal(): ScrollSignal {
    return when (val lm = layoutManager) {
        null -> error(
            "PaginatorPrefetchController.bindToRecyclerView requires a LayoutManager to be set " +
                    "on the RecyclerView before binding."
        )

        is LinearLayoutManager -> ScrollSignal(
            firstVisibleIndex = lm.findFirstVisibleItemPosition(),
            lastVisibleIndex = lm.findLastVisibleItemPosition(),
        )

        is StaggeredGridLayoutManager -> {
            val firsts = lm.findFirstVisibleItemPositions(null)
            val lasts = lm.findLastVisibleItemPositions(null)
            var min = Int.MAX_VALUE
            var max = -1
            for (i in firsts.indices) {
                val v = firsts[i]
                if (v != RecyclerView.NO_POSITION && v < min) min = v
            }
            for (i in lasts.indices) {
                val v = lasts[i]
                if (v != RecyclerView.NO_POSITION && v > max) max = v
            }
            ScrollSignal(
                firstVisibleIndex = if (max < 0) -1 else min,
                lastVisibleIndex = max,
            )
        }

        else -> error(
            "Unsupported RecyclerView.LayoutManager for paginator-view bindings: " +
                    "${lm::class.java.name}. Supported: LinearLayoutManager, GridLayoutManager, " +
                    "StaggeredGridLayoutManager. For a custom LayoutManager, wire " +
                    "PaginatorPrefetchController.onScroll(...) manually."
        )
    }
}
