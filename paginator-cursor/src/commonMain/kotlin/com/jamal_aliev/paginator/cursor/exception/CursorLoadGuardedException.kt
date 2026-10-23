package com.jamal_aliev.paginator.cursor.exception

import com.jamal_aliev.paginator.core.exception.LoadGuardedBaseException
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.bookmark.CursorBootstrapKey

/**
 * Cursor-flavoured counterpart of [LoadGuardedException] thrown by
 * [com.jamal_aliev.paginator.cursor.CursorPaginator] when its `loadGuard` rejects a load.
 *
 * The full [CursorBookmark] that was about to be loaded is preserved in
 * [attemptedCursor] — callers often need `prev`/`next` context to decide how
 * to handle the rejection, not just the `self` key.
 *
 * @param attemptedCursor The bookmark whose load was rejected by the guard.
 */
class CursorLoadGuardedException(
    val attemptedCursor: CursorBookmark<*>,
) : LoadGuardedBaseException(
    attemptedKey = attemptedCursor.self,
    message = "Load guard rejected loading of cursor ${attemptedCursor.self}",
) {

    /**
     * `true` when the rejected load was the **anchorless first load** of `restart()` — there is no
     * cursor key yet, so [attemptedCursor] carries [CursorBootstrapKey] as its `self`.
     */
    val isBootstrap: Boolean get() = attemptedCursor.self == CursorBootstrapKey
}
