package com.jamal_aliev.paginator.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A serializable representation of a single page's state.
 *
 * @param T The element type. Must be `@Serializable`.
 * @param page The page number (>= 1).
 * @param data The items on this page; an empty list represents an "empty" page.
 * @param wasDirty `true` if this page was already dirty before saving,
 *   or if it was an ErrorPage/ProgressPage that was converted during save.
 * @param errorMessage The exception message from an [com.jamal_aliev.paginator.page.PageState.ErrorPage],
 *   preserved so that the UI can display the error reason after restoration. `null` for non-error pages.
 * @param metadata The serialized metadata attached to this page, or `null` if none was provided or
 *   no [metadataEncoder][com.jamal_aliev.paginator.PagingCore.saveState] was supplied during save.
 */
@Serializable
data class PageEntry<T>(
    val page: Int,
    val data: List<T>,
    val wasDirty: Boolean,
    val errorMessage: String? = null,
    val metadata: JsonElement? = null,
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
