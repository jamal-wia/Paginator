package com.jamal_aliev.paginator.exception

import com.jamal_aliev.paginator.bookmark.CursorBookmark

/**
 * Cursor-flavoured counterpart of [LoadGuardedException] thrown by
 * [com.jamal_aliev.paginator.CursorPaginator] when its `loadGuard` rejects a load.
 *
 * The full [CursorBookmark] that was about to be loaded is preserved in
 * [attemptedCursor] — callers often need `prev`/`next` context to decide how
 * to handle the rejection, not just the `self` key.
 *
 * @param attemptedCursor The bookmark whose load was rejected by the guard.
 */
class CursorLoadGuardedException(
    val attemptedCursor: CursorBookmark,
) : LoadGuardedBaseException(
    attemptedKey = attemptedCursor.self,
    message = "Load guard rejected loading of cursor ${attemptedCursor.self}",
)
