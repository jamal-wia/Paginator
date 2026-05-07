package com.jamal_aliev.paginator.prefetch

import com.jamal_aliev.paginator.prefetch.ScrollWindow.Companion.NONE
import com.jamal_aliev.paginator.prefetch.ScrollWindow.Companion.from
import kotlin.jvm.JvmInline

/**
 * The currently visible window expressed in **data-only** coordinates — the form
 * [PaginatorPrefetchController.onScroll] / [CursorPaginatorPrefetchController.onScroll] expect.
 *
 * Packs [firstVisibleIndex] and [lastVisibleIndex] into a single [Long] — zero heap allocation
 * on the hot scroll path. Use [isNone] to check for the sentinel returned by [from] when the
 * signal is not actionable.
 */
@JvmInline
value class ScrollWindow(val packed: Long) {

    val firstVisibleIndex: Int get() = (packed ushr 32).toInt()
    val lastVisibleIndex: Int get() = (packed and 0xFFFFFFFFL).toInt()

    /** True when this instance is the [NONE] sentinel — no actionable scroll signal. */
    val isNone: Boolean get() = packed == NONE.packed

    companion object {
        /**
         * Sentinel returned by [from] when the scroll signal carries no actionable information.
         * Packed as [Long.MIN_VALUE] — unambiguously outside the valid index range (valid packed
         * values always have a non-negative high 32 bits).
         */
        val NONE = ScrollWindow(Long.MIN_VALUE)

        /**
         * Translates a UI lazy-container scroll observation into the data-only indices required
         * by the prefetch controllers.
         *
         * Both controllers require that `totalItemCount` reflects only data items, with no
         * prefix, suffix, or decorator items. This factory performs that translation in one
         * place so that the official UI-binding artifacts (`paginator-compose`, `paginator-view`)
         * — and any third-party binding — share identical behavior.
         *
         * Returns [NONE] when the signal carries no actionable information:
         * - any visible index is negative (empty or not-yet-laid-out list),
         * - there are no data items (`dataItemCount <= 0`),
         * - the indices are inconsistent (`firstVisibleIndex > lastVisibleIndex`),
         * - the entire viewport sits before the data range,
         * - the entire viewport sits after the data range.
         *
         * In the last two cases the controller would otherwise either reject the call
         * (negative-index guard) or, worse, calibrate on a position that has nothing to do with
         * the data — both are incorrect, so returning [NONE] is the safe choice.
         *
         * @param firstVisibleIndex First visible index in **full-list** coordinates
         *   (i.e. including any prefix and suffix items).
         * @param lastVisibleIndex Last visible index, same coordinate system as [firstVisibleIndex].
         * @param dataItemCount Number of **data** items currently rendered (excluding any prefix
         *   or suffix items such as decorators, loading indicators, or sticky labels).
         * @param dataOffset Number of non-data items rendered **before** the data range. Used to
         *   shift the visible-index window down to the data origin.
         */
        fun from(
            firstVisibleIndex: Int,
            lastVisibleIndex: Int,
            dataItemCount: Int,
            dataOffset: Int,
        ): ScrollWindow {
            if (dataItemCount <= 0) return NONE
            if (firstVisibleIndex < 0 || lastVisibleIndex < 0) return NONE
            if (firstVisibleIndex > lastVisibleIndex) return NONE
            if (firstVisibleIndex >= dataOffset + dataItemCount) return NONE
            if (lastVisibleIndex < dataOffset) return NONE

            return ScrollWindow(
                firstVisibleIndex = (firstVisibleIndex - dataOffset).coerceAtLeast(0),
                lastVisibleIndex = (lastVisibleIndex - dataOffset).coerceAtMost(dataItemCount - 1),
            )
        }
    }
}

/** Packs a (first, last) index pair into a [ScrollWindow]. */
fun ScrollWindow(firstVisibleIndex: Int, lastVisibleIndex: Int): ScrollWindow =
    ScrollWindow((firstVisibleIndex.toLong() shl 32) or (lastVisibleIndex.toLong() and 0xFFFFFFFFL))
