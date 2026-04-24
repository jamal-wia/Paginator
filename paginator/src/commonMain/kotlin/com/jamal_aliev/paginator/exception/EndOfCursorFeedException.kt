package com.jamal_aliev.paginator.exception

/**
 * Thrown by [com.jamal_aliev.paginator.CursorPaginator] when navigation is attempted
 * past the boundary of a cursor-based feed.
 *
 * The canonical signal of "nothing more to load" in a cursor-based paginator is
 * `next == null` (tail) or `prev == null` (head) on the current edge bookmark.
 * When [com.jamal_aliev.paginator.CursorPaginator.goNextPage] or
 * [com.jamal_aliev.paginator.CursorPaginator.goPreviousPage] encounters that
 * signal they throw this exception instead of attempting another load.
 *
 * @param attemptedCursorKey The `self` key of the edge cursor that was used as the
 *   pivot for the failed navigation. May be `null` when the feed has not been
 *   started yet and navigation was attempted in a direction that cannot bootstrap.
 * @param direction Whether the exceeded boundary was the head or the tail.
 */
class EndOfCursorFeedException(
    val attemptedCursorKey: Any?,
    val direction: Direction,
) : Exception(
    "Attempted to go ${direction.name.lowercase()} past the end of the cursor feed " +
            "(pivot=$attemptedCursorKey). The feed signalled the end by leaving " +
            "${if (direction == Direction.FORWARD) "next" else "prev"} == null."
) {

    /**
     * Direction of the navigation that failed.
     */
    enum class Direction { FORWARD, BACKWARD }
}
