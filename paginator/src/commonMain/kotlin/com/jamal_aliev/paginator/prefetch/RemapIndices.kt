package com.jamal_aliev.paginator.prefetch

/**
 * Result of remapping a UI scroll observation (full-list indices that include headers / footers /
 * dividers) into the **data-only** indices that [PaginatorPrefetchController.onScroll] /
 * [CursorPaginatorPrefetchController.onScroll] expect.
 */
public data class RemappedScroll(
    val firstVisibleIndex: Int,
    val lastVisibleIndex: Int,
    val totalItemCount: Int,
)

/**
 * Pure helper that translates a UI lazy-container scroll observation into the data-only indices
 * required by the prefetch controllers.
 *
 * Both controllers document that `totalItemCount` "must reflect only data items" and any index
 * arithmetic must not include headers, footers, or dividers. This function performs that
 * translation in one place so that the official UI-binding artifacts (`paginator-compose`,
 * `paginator-view`) — and any third-party binding — share identical behaviour.
 *
 * Returns `null` when the signal carries no actionable information and no `onScroll` call should
 * be made:
 * - the visible list is empty (`totalItemCount <= 0` or any index is negative),
 * - there are no data items (`dataItemCount <= 0`),
 * - the indices are inconsistent (`firstVisibleIndex > lastVisibleIndex`),
 * - the entire viewport sits above the data range (only headers visible),
 * - the entire viewport sits below the data range (only footers visible).
 *
 * In the last two cases the controller would otherwise either reject the call (negative-index
 * guard) or, worse, calibrate on a position that has nothing to do with the data — both are
 * incorrect, so returning `null` is the safe choice.
 *
 * @param firstVisibleIndex First visible index reported by the UI framework, in **full-list**
 *   coordinates (i.e. including headers and footers).
 * @param lastVisibleIndex Last visible index, same coordinate system as [firstVisibleIndex].
 * @param totalItemCount Total number of items rendered by the UI (data + headers + footers).
 * @param dataItemCount Number of **data** items currently rendered (excluding any headers,
 *   footers, dividers, sticky labels, or loading indicators).
 * @param headerCount Number of non-data items rendered **before** the data range. Used to shift
 *   the visible-index window down to the data origin.
 */
public fun remapIndices(
    firstVisibleIndex: Int,
    lastVisibleIndex: Int,
    totalItemCount: Int,
    dataItemCount: Int,
    headerCount: Int,
): RemappedScroll? {
    if (dataItemCount <= 0) return null
    if (totalItemCount <= 0) return null
    if (firstVisibleIndex < 0 || lastVisibleIndex < 0) return null
    if (firstVisibleIndex > lastVisibleIndex) return null

    val dataStart = headerCount
    val dataEndExclusive = headerCount + dataItemCount

    if (firstVisibleIndex >= dataEndExclusive) return null
    if (lastVisibleIndex < dataStart) return null

    val dataFirst = (firstVisibleIndex - headerCount).coerceAtLeast(0)
    val dataLast = (lastVisibleIndex - headerCount).coerceAtMost(dataItemCount - 1)

    return RemappedScroll(
        firstVisibleIndex = dataFirst,
        lastVisibleIndex = dataLast,
        totalItemCount = dataItemCount,
    )
}
