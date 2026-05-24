package com.jamal_aliev.paginator.cursor.extension

import com.jamal_aliev.paginator.cursor.CursorPaginator
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.exception.CursorLoadGuardedException
import com.jamal_aliev.paginator.core.exception.LockedException.RefreshWasLockedException
import com.jamal_aliev.paginator.core.initializer.InitializerErrorPage
import com.jamal_aliev.paginator.core.initializer.InitializerProgressPage
import com.jamal_aliev.paginator.core.initializer.InitializerSuccessPage
import com.jamal_aliev.paginator.core.page.PageState

/**
 * Refreshes **all** currently cached cursors by reloading them from the source
 * in parallel.
 *
 * @throws RefreshWasLockedException If refresh is locked.
 * @throws CursorLoadGuardedException If [loadGuard] returns `false` for any cursor.
 */
suspend fun <T> CursorPaginator<T>.refreshAll(
    loadingSilently: Boolean = false,
    finalSilently: Boolean = false,
    loadGuard: (cursor: CursorBookmark, state: PageState<T>?) -> Boolean = { _, _ -> true },
    enableCacheFlow: Boolean = this.core.enableCacheFlow,
    initProgressState: InitializerProgressPage<T> = this.core.initializerProgressPage,
    initSuccessState: InitializerSuccessPage<T> = this.core.initializerSuccessPage,
    initErrorState: InitializerErrorPage<T> = this.core.initializerErrorPage,
) {
    if (lockRefresh) throw RefreshWasLockedException()
    return refresh(
        cursors = this.core.cursors,
        loadingSilently = loadingSilently,
        finalSilently = finalSilently,
        loadGuard = loadGuard,
        enableCacheFlow = enableCacheFlow,
        initProgressState = initProgressState,
        initSuccessState = initSuccessState,
        initErrorState = initErrorState,
    )
}
