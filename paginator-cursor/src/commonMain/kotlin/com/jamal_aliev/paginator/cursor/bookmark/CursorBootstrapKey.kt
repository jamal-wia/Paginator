package com.jamal_aliev.paginator.cursor.bookmark

/**
 * Stand-in `self` key reported for the **anchorless first load**, which has no real cursor key yet
 * — currently only as
 * [CursorLoadGuardedException.attemptedCursor][com.jamal_aliev.paginator.cursor.exception.CursorLoadGuardedException.attemptedCursor]
 * when a load guard rejects a `restart()` without `initialCursor`
 * (see [isBootstrap][com.jamal_aliev.paginator.cursor.exception.CursorLoadGuardedException.isBootstrap]).
 *
 * It never enters the cache and is never passed to `load` or to an initializer.
 */
data object CursorBootstrapKey
