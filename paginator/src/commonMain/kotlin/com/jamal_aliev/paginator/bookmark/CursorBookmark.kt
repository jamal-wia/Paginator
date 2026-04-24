package com.jamal_aliev.paginator.bookmark

/**
 * LinkedList-style bookmark for cursor-based pagination.
 *
 * Unlike [BookmarkInt] — which identifies a page by its absolute index —
 * a [CursorBookmark] knows only its immediate neighbors. This makes it suitable for
 * APIs that expose continuation tokens / opaque cursors instead of numeric offsets
 * (e.g. chat feeds, activity streams, infinite social timelines).
 *
 * ```
 *   prev ◀─── self ───▶ next
 * ```
 *
 * ## Identity
 *
 * Two bookmarks are considered equal **if and only if** their [self] keys are equal.
 * The [prev]/[next] fields do not participate in equality or hashing, so a newly
 * loaded bookmark with freshly discovered neighbors still hashes to the same slot
 * as its cached predecessor — the cache can therefore update links in place.
 *
 * ## End of feed
 *
 * - `prev == null` → this page is the **head** of the feed (no page before it).
 * - `next == null` → this page is the **tail** of the feed (no page after it).
 *
 * These are the canonical signals used by
 * [com.jamal_aliev.paginator.CursorPaginator.goNextPage] and
 * [com.jamal_aliev.paginator.CursorPaginator.goPreviousPage] to throw
 * [com.jamal_aliev.paginator.exception.EndOfCursorFeedException].
 *
 * ## Cache key
 *
 * [self] is the cache key. It is deliberately typed as `Any` so consumers can pick
 * whatever key shape their backend returns — opaque strings, numeric ids,
 * timestamps, composite `data class` tokens, etc. The only requirements are stable
 * [Any.equals]/[Any.hashCode].
 *
 * @property prev Cache key of the page immediately before this one, or `null` at the head.
 * @property self Cache key of this page. Used for identity and hashing.
 * @property next Cache key of the page immediately after this one, or `null` at the tail.
 */
data class CursorBookmark(
    val prev: Any?,
    val self: Any,
    val next: Any?,
) : Bookmark {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CursorBookmark) return false
        return self == other.self
    }

    override fun hashCode(): Int = self.hashCode()

    override fun toString(): String = "CursorBookmark(self=$self, prev=$prev, next=$next)"
}
