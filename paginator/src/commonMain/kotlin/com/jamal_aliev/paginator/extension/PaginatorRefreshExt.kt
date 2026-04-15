package com.jamal_aliev.paginator.extension

import com.jamal_aliev.paginator.Paginator
import com.jamal_aliev.paginator.exception.LoadGuardedException
import com.jamal_aliev.paginator.exception.LockedException.RefreshWasLockedException
import com.jamal_aliev.paginator.initializer.InitializerEmptyPage
import com.jamal_aliev.paginator.initializer.InitializerErrorPage
import com.jamal_aliev.paginator.initializer.InitializerProgressPage
import com.jamal_aliev.paginator.initializer.InitializerSuccessPage
import com.jamal_aliev.paginator.page.PageState

/**
 * Refreshes **all** currently cached pages by reloading them from the source in parallel.
 *
 * @param loadingSilently If `true`, the snapshot will **not** be emitted after setting
 *   pages to progress state.
 * @param finalSilently If `true`, the snapshot will **not** be emitted after all pages
 *   finish loading.
 * @param loadGuard A guard callback invoked for each page before loading.
 * @param enableCacheFlow If `true`, the full cache flow is also updated.
 * @param initProgressState Factory for creating progress page instances during loading.
 * @param initEmptyState Factory for creating empty page instances.
 * @param initSuccessState Factory for creating success page instances.
 * @param initErrorState Factory for creating error page instances.
 * @throws RefreshWasLockedException If refresh is locked.
 * @throws LoadGuardedException If [loadGuard] returns `false` for any page.
 */
suspend fun <T> Paginator<T>.refreshAll(
    loadingSilently: Boolean = false,
    finalSilently: Boolean = false,
    loadGuard: (page: Int, state: PageState<T>?) -> Boolean = { _, _ -> true },
    enableCacheFlow: Boolean = this.core.enableCacheFlow,
    initProgressState: InitializerProgressPage<T> = this.core.initializerProgressPage,
    initEmptyState: InitializerEmptyPage<T> = this.core.initializerEmptyPage,
    initSuccessState: InitializerSuccessPage<T> = this.core.initializerSuccessPage,
    initErrorState: InitializerErrorPage<T> = this.core.initializerErrorPage,
) {
    if (lockRefresh) throw RefreshWasLockedException()
    return refresh(
        pages = this.cache.pages,
        loadingSilently = loadingSilently,
        finalSilently = finalSilently,
        loadGuard = loadGuard,
        enableCacheFlow = enableCacheFlow,
        initProgressState = initProgressState,
        initEmptyState = initEmptyState,
        initSuccessState = initSuccessState,
        initErrorState = initErrorState,
    )
}
