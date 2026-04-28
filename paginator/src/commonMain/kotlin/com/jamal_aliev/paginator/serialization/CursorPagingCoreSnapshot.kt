package com.jamal_aliev.paginator.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A serializable representation of a single cursor-indexed page.
 *
 * Mirrors [PageEntry] but keyed by the [CursorBookmark][com.jamal_aliev.paginator.bookmark.CursorBookmark]
 * triple instead of a numeric page. The cursor keys are stored as [JsonElement]s so
 * the caller can choose how to serialise the user-defined key type.
 *
 * @param T The element type. Must be `@Serializable`.
 * @param selfKey The serialised `self` key of the bookmark.
 * @param prevKey The serialised `prev` key, or `null` at the head of the feed.
 * @param nextKey The serialised `next` key, or `null` at the tail of the feed.
 */
@Serializable
data class CursorPageEntry<T>(
    val selfKey: JsonElement,
    val prevKey: JsonElement? = null,
    val nextKey: JsonElement? = null,
    val data: List<T>,
    val wasDirty: Boolean,
    val errorMessage: String? = null,
    val metadata: JsonElement? = null,
)

/**
 * A serializable snapshot of [com.jamal_aliev.paginator.CursorPagingCore] state.
 *
 * Captures everything needed to restore the cursor-based paginator's cache after
 * process death. Does **not** include CursorPaginator-level concerns (bookmarks,
 * locks, initial cursor).
 */
@Serializable
data class CursorPagingCoreSnapshot<T>(
    val entries: List<CursorPageEntry<T>>,
    val startContextSelf: JsonElement? = null,
    val endContextSelf: JsonElement? = null,
    val capacity: Int,
)
