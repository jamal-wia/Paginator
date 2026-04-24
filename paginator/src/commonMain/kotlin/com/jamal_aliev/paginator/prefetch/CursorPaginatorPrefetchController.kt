package com.jamal_aliev.paginator.prefetch

import com.jamal_aliev.paginator.CursorPaginator
import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.initializer.InitializerEmptyPage
import com.jamal_aliev.paginator.initializer.InitializerErrorPage
import com.jamal_aliev.paginator.initializer.InitializerProgressPage
import com.jamal_aliev.paginator.initializer.InitializerSuccessPage
import com.jamal_aliev.paginator.page.PageState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Automatic scroll-based prefetch controller for [CursorPaginator].
 *
 * Mirrors [PaginatorPrefetchController]: the controller watches the visible
 * item range reported from the UI via [onScroll] and calls
 * [CursorPaginator.goNextPage] / [CursorPaginator.goPreviousPage] before the
 * user reaches the edge of the loaded content.
 *
 * Instead of comparing integer page numbers, the cursor variant checks the
 * link of the current edge bookmark:
 * - a forward prefetch fires only when `endContextCursor?.next != null`;
 * - a backward prefetch fires only when `startContextCursor?.prev != null`.
 */
class CursorPaginatorPrefetchController<T>(
    private val paginator: CursorPaginator<T>,
    private val scope: CoroutineScope,
    prefetchDistance: Int,
    var enableBackwardPrefetch: Boolean = false,
    var silentlyLoading: Boolean = true,
    var silentlyResult: Boolean = false,
    var loadGuard: (cursor: CursorBookmark, state: PageState<T>?) -> Boolean = { _, _ -> true },
    var enableCacheFlow: Boolean = paginator.core.enableCacheFlow,
    var initProgressState: InitializerProgressPage<T> = paginator.core.initializerProgressPage,
    var initSuccessState: InitializerSuccessPage<T> = paginator.core.initializerSuccessPage,
    var initEmptyState: InitializerEmptyPage<T> = paginator.core.initializerEmptyPage,
    var initErrorState: InitializerErrorPage<T> = paginator.core.initializerErrorPage,
    var onPrefetchError: ((Exception) -> Unit)? = null,
) {

    var prefetchDistance: Int = prefetchDistance
        set(value) {
            require(value > 0) { "prefetchDistance must be positive, got $value" }
            field = value
        }

    init {
        require(prefetchDistance > 0) { "prefetchDistance must be positive, got $prefetchDistance" }
    }

    private var forwardJob: Job? = null
    private var backwardJob: Job? = null

    private var initialized: Boolean = false
    private var prevFirstVisible: Int = -1
    private var prevLastVisible: Int = -1

    var enabled: Boolean = true

    fun onScroll(
        firstVisibleIndex: Int,
        lastVisibleIndex: Int,
        totalItemCount: Int,
    ) {
        if (totalItemCount <= 0) return
        if (firstVisibleIndex < 0 || lastVisibleIndex < 0) return

        if (!enabled) {
            prevFirstVisible = firstVisibleIndex
            prevLastVisible = lastVisibleIndex
            return
        }

        if (!initialized) {
            prevFirstVisible = firstVisibleIndex
            prevLastVisible = lastVisibleIndex
            initialized = true
            return
        }

        val clampedLast = lastVisibleIndex.coerceAtMost(totalItemCount - 1)
        val scrollingForward = clampedLast > prevLastVisible
        val scrollingBackward = firstVisibleIndex < prevFirstVisible
        prevFirstVisible = firstVisibleIndex
        prevLastVisible = clampedLast

        if (scrollingForward) {
            val itemsFromEnd = totalItemCount - clampedLast - 1
            if (itemsFromEnd <= prefetchDistance
                && forwardJob?.isActive != true
                && !paginator.lockGoNextPage
                && paginator.core.endContextCursor?.next != null
            ) {
                forwardJob = scope.launch {
                    try {
                        paginator.goNextPage(
                            silentlyLoading = silentlyLoading,
                            silentlyResult = silentlyResult,
                            loadGuard = loadGuard,
                            enableCacheFlow = enableCacheFlow,
                            initProgressState = initProgressState,
                            initEmptyState = initEmptyState,
                            initSuccessState = initSuccessState,
                            initErrorState = initErrorState,
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        onPrefetchError?.invoke(e)
                    }
                }
            }
        }

        if (enableBackwardPrefetch && scrollingBackward) {
            if (firstVisibleIndex <= prefetchDistance
                && backwardJob?.isActive != true
                && !paginator.lockGoPreviousPage
                && paginator.core.startContextCursor?.prev != null
            ) {
                backwardJob = scope.launch {
                    try {
                        paginator.goPreviousPage(
                            silentlyLoading = silentlyLoading,
                            silentlyResult = silentlyResult,
                            loadGuard = loadGuard,
                            enableCacheFlow = enableCacheFlow,
                            initProgressState = initProgressState,
                            initEmptyState = initEmptyState,
                            initSuccessState = initSuccessState,
                            initErrorState = initErrorState,
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        onPrefetchError?.invoke(e)
                    }
                }
            }
        }
    }

    fun cancel() {
        forwardJob?.cancel()
        forwardJob = null
        backwardJob?.cancel()
        backwardJob = null
    }

    fun reset() {
        cancel()
        initialized = false
        prevFirstVisible = -1
        prevLastVisible = -1
    }
}
