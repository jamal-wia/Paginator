package com.jamal_aliev.paginator.load

import com.jamal_aliev.paginator.bookmark.CursorBookmark

/**
 * Result of a single cursor-based load for
 * [com.jamal_aliev.paginator.CursorPaginator].
 *
 * Alongside [data] and [metadata] this result carries the [bookmark] returned by
 * the source. The bookmark's `prev`/`next` fields tell the paginator how the
 * freshly loaded page is linked to its neighbours and, crucially, whether the
 * end of the feed has been reached (`next == null` / `prev == null`).
 *
 * ```kotlin
 * load = { cursor ->
 *     val page = api.getMessages(cursor?.self as? String)
 *     CursorLoadResult(
 *         data = page.items,
 *         bookmark = CursorBookmark(
 *             prev = page.prevCursor,
 *             self = page.selfCursor,
 *             next = page.nextCursor,
 *         ),
 *     )
 * }
 * ```
 *
 * @param data Items for the requested page, in order. If
 *   [com.jamal_aliev.paginator.CursorPagingCore.capacity] is set, excess items are
 *   trimmed automatically.
 * @param metadata Arbitrary metadata attached to this load result, or `null` if none.
 * @param bookmark The [CursorBookmark] describing the loaded page's links. Must be
 *   non-null for a successful load — the paginator needs `self` to key the cache.
 */
open class CursorLoadResult<T>(
    open val data: List<T>,
    open val metadata: Metadata? = null,
    open val bookmark: CursorBookmark,
)
