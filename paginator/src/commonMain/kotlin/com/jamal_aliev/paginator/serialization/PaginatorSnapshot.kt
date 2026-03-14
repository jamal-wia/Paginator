package com.jamal_aliev.paginator.serialization

import kotlinx.serialization.Serializable

/**
 * A serializable snapshot of [com.jamal_aliev.paginator.Paginator] state.
 *
 * Extends [PagingCoreSnapshot] with Paginator-level concerns: final page,
 * bookmarks, bookmark position, recycling mode, and lock flags.
 *
 * @param T The element type. Must be `@Serializable`.
 * @param coreSnapshot The underlying [PagingCoreSnapshot] with cache data.
 * @param finalPage The maximum page number allowed for pagination.
 * @param bookmarkPages The list of bookmark page numbers.
 * @param bookmarkIndex The current position within the bookmarks list.
 * @param recyclingBookmark Whether bookmark navigation wraps around.
 * @param lockJump Whether [com.jamal_aliev.paginator.Paginator.jump] is blocked.
 * @param lockGoNextPage Whether [com.jamal_aliev.paginator.Paginator.goNextPage] is blocked.
 * @param lockGoPreviousPage Whether [com.jamal_aliev.paginator.Paginator.goPreviousPage] is blocked.
 * @param lockRestart Whether [com.jamal_aliev.paginator.Paginator.restart] is blocked.
 * @param lockRefresh Whether [com.jamal_aliev.paginator.Paginator.refresh] is blocked.
 */
@Serializable
data class PaginatorSnapshot<T>(
    val coreSnapshot: PagingCoreSnapshot<T>,
    val finalPage: Int,
    val bookmarkPages: List<Int>,
    val bookmarkIndex: Int,
    val recyclingBookmark: Boolean,
    val lockJump: Boolean,
    val lockGoNextPage: Boolean,
    val lockGoPreviousPage: Boolean,
    val lockRestart: Boolean,
    val lockRefresh: Boolean,
)
