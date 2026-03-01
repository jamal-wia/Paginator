package com.jamal_aliev.paginator.serialization

import kotlinx.serialization.Serializable

/**
 * The type of a page state entry for serialization purposes.
 *
 * During save, [com.jamal_aliev.paginator.page.PageState.ErrorPage] and
 * [com.jamal_aliev.paginator.page.PageState.ProgressPage] are converted to either
 * [SUCCESS] or [EMPTY] (preserving their cached data). On restore,
 * those pages are marked dirty so they get re-fetched.
 */
@Serializable
enum class PageEntryType {
    SUCCESS,
    EMPTY
}

/**
 * A serializable representation of a single page's state.
 *
 * @param T The element type. Must be `@Serializable`.
 * @param page The page number (>= 1).
 * @param type Whether this is a success page with data or an empty page.
 * @param data The items on this page. For [PageEntryType.EMPTY] pages this is an empty list.
 * @param wasDirty `true` if this page was already dirty before saving,
 *   or if it was an ErrorPage/ProgressPage that was converted during save.
 */
@Serializable
data class PageEntry<T>(
    val page: Int,
    val type: PageEntryType,
    val data: List<T>,
    val wasDirty: Boolean,
)

/**
 * A serializable snapshot of [com.jamal_aliev.paginator.PagingCore] state.
 *
 * Captures everything needed to restore the paginator's cache after process death.
 * Does **not** include Paginator-level concerns (finalPage, bookmarks, locks, source).
 *
 * @param T The element type. Must be `@Serializable`.
 * @param entries The list of page entries, in ascending page order.
 * @param startContextPage The left boundary of the context window (0 if not started).
 * @param endContextPage The right boundary of the context window (0 if not started).
 * @param capacity The expected items per page.
 */
@Serializable
data class PagingCoreSnapshot<T>(
    val entries: List<PageEntry<T>>,
    val startContextPage: Int,
    val endContextPage: Int,
    val capacity: Int,
)
