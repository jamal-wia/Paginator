package com.jamal_aliev.paginator.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Serializable snapshot of [com.jamal_aliev.paginator.CursorPaginator] state.
 *
 * Extends [CursorPagingCoreSnapshot] with paginator-level fields: bookmarks,
 * bookmark position, recycling flag, initial anchor cursor, and lock flags.
 *
 * @param T The element type. Must be `@Serializable`.
 */
@Serializable
data class CursorPaginatorSnapshot<T>(
    val coreSnapshot: CursorPagingCoreSnapshot<T>,
    val bookmarkSelves: List<JsonElement>,
    val bookmarkPrevSelves: List<JsonElement?>,
    val bookmarkNextSelves: List<JsonElement?>,
    val bookmarkIndex: Int,
    val recyclingBookmark: Boolean,
    val initialCursorSelf: JsonElement? = null,
    val initialCursorPrevSelf: JsonElement? = null,
    val initialCursorNextSelf: JsonElement? = null,
    val lockJump: Boolean,
    val lockGoNextPage: Boolean,
    val lockGoPreviousPage: Boolean,
    val lockRestart: Boolean,
    val lockRefresh: Boolean,
)
