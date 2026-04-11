package com.jamal_aliev.paginator.prefetch

import com.jamal_aliev.paginator.Paginator
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
 * Automatic scroll-based prefetch controller for [Paginator].
 *
 * Monitors the user's scroll position and triggers [Paginator.goNextPage] or
 * [Paginator.goPreviousPage] **before** the user reaches the boundary of
 * loaded content, so new pages are fetched in advance.
 *
 * The controller is **platform-agnostic**: it receives visibility information
 * directly from the UI layer via [onScroll] and does not depend on any
 * framework-specific scroll APIs.
 *
 * **Usage (Android RecyclerView):**
 * ```kotlin
 * val prefetch = paginator.prefetchController(
 *     scope = viewModelScope,
 *     prefetchDistance = 10,
 *     enableBackwardPrefetch = true,
 * )
 *
 * recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
 *     override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
 *         prefetch.onScroll(
 *             firstVisibleIndex = layoutManager.findFirstVisibleItemPosition(),
 *             lastVisibleIndex  = layoutManager.findLastVisibleItemPosition(),
 *             totalItemCount    = adapter.itemCount
 *         )
 *     }
 * })
 * ```
 *
 * **Usage (Compose LazyColumn):**
 * ```kotlin
 * val listState = rememberLazyListState()
 *
 * LaunchedEffect(listState.firstVisibleItemIndex, listState.layoutInfo) {
 *     val info = listState.layoutInfo
 *     prefetch.onScroll(
 *         firstVisibleIndex = listState.firstVisibleItemIndex,
 *         lastVisibleIndex  = info.visibleItemsInfo.lastOrNull()?.index ?: 0,
 *         totalItemCount    = info.totalItemsCount
 *     )
 * }
 * ```
 *
 * **Important:** [totalItemCount] must reflect only **data items**. Do not
 * include headers, footers, dividers, or loading indicators — otherwise
 * [prefetchDistance] will trigger inaccurately.
 *
 * @param T The type of elements contained in each page.
 * @param paginator The paginator instance to prefetch pages for.
 * @param scope [CoroutineScope] in which prefetch coroutines are launched.
 *   Typically `viewModelScope` or a lifecycle-bound scope.
 * @param prefetchDistance Number of items from the edge of loaded content at
 *   which prefetch is triggered. Must be positive. For example, `10` means
 *   "start loading the next page when there are 10 or fewer items left to
 *   scroll".
 * @param enableBackwardPrefetch When `true`, scrolling toward the beginning
 *   of the list also triggers [Paginator.goPreviousPage].
 * @param silentlyLoading If `true` (default), the [PageState.ProgressPage]
 *   emitted during prefetch loading is **not** pushed to the snapshot flow.
 *   This prevents a loading indicator from appearing for a page the user
 *   hasn't scrolled to yet.
 * @param silentlyResult If `true`, the snapshot is **not** emitted when the
 *   prefetched page finishes loading. Usually `false` so the UI picks up
 *   the new data immediately.
 * @param loadGuard Optional guard callback passed to navigation functions.
 * @param enableCacheFlow Forwarded to navigation functions.
 * @param initProgressState Factory for [PageState.ProgressPage] during loading.
 * @param initSuccessState Factory for [PageState.SuccessPage] on success.
 * @param initEmptyState Factory for [PageState.EmptyPage] when source returns no data.
 * @param initErrorState Factory for [PageState.ErrorPage] on failure.
 * @param onPrefetchError Optional callback invoked when a prefetch fails with
 *   an exception (excluding [CancellationException]). Useful for logging or
 *   analytics. If `null`, errors are silently ignored.
 *
 * @see Paginator.goNextPage
 * @see Paginator.goPreviousPage
 */
class PaginatorPrefetchController<T>(
    private val paginator: Paginator<T>,
    private val scope: CoroutineScope,
    prefetchDistance: Int,
    var enableBackwardPrefetch: Boolean = false,
    var silentlyLoading: Boolean = true,
    var silentlyResult: Boolean = false,
    var loadGuard: (page: Int, state: PageState<T>?) -> Boolean = { _, _ -> true },
    var enableCacheFlow: Boolean = paginator.core.enableCacheFlow,
    var initProgressState: InitializerProgressPage<T> = paginator.core.initializerProgressPage,
    var initSuccessState: InitializerSuccessPage<T> = paginator.core.initializerSuccessPage,
    var initEmptyState: InitializerEmptyPage<T> = paginator.core.initializerEmptyPage,
    var initErrorState: InitializerErrorPage<T> = paginator.core.initializerErrorPage,
    var onPrefetchError: ((Exception) -> Unit)? = null,
) {

    /**
     * Number of items from the edge at which prefetch fires. Must be positive.
     * @throws IllegalArgumentException if set to a non-positive value.
     */
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

    /** Whether the first [onScroll] call has been received (calibration). */
    private var initialized: Boolean = false

    /** Previous scroll positions for direction tracking. */
    private var prevFirstVisible: Int = -1
    private var prevLastVisible: Int = -1

    /**
     * Master switch. When `false`, [onScroll] is a no-op.
     * Use this to pause prefetching without cancelling in-flight jobs
     * (e.g. when a modal is shown over the list).
     * To also cancel running jobs, call [cancel].
     */
    var enabled: Boolean = true

    /**
     * Notifies the controller about the current scroll state.
     *
     * Call this method from a scroll listener or a reactive effect every time
     * the visible item range changes.
     *
     * The very first call is used for **calibration only** — it records the
     * initial scroll position without triggering any prefetch, preventing a
     * false positive when the list first appears.
     *
     * @param firstVisibleIndex Index of the first visible **data** item in
     *   the flat list (0-based).
     * @param lastVisibleIndex Index of the last visible **data** item in
     *   the flat list (0-based).
     * @param totalItemCount Total number of **data** items currently in the list.
     *   Must not include headers, footers, dividers, or loading indicators.
     */
    fun onScroll(
        firstVisibleIndex: Int,
        lastVisibleIndex: Int,
        totalItemCount: Int
    ) {
        if (totalItemCount <= 0) return
        if (firstVisibleIndex < 0 || lastVisibleIndex < 0) return

        // ── Always track positions, even when disabled ─────────────────
        if (!enabled) {
            prevFirstVisible = firstVisibleIndex
            prevLastVisible = lastVisibleIndex
            return
        }

        // ── Calibration: first call only records positions ──────────────
        if (!initialized) {
            prevFirstVisible = firstVisibleIndex
            prevLastVisible = lastVisibleIndex
            initialized = true
            return
        }

        // ── Clamp lastVisibleIndex to valid range ──────────────────────
        val clampedLastVisible = lastVisibleIndex.coerceAtMost(totalItemCount - 1)

        // ── Determine scroll direction ──────────────────────────────────
        val scrollingForward = clampedLastVisible > prevLastVisible
        val scrollingBackward = firstVisibleIndex < prevFirstVisible
        prevFirstVisible = firstVisibleIndex
        prevLastVisible = clampedLastVisible

        // ── Forward prefetch ────────────────────────────────────────────
        if (scrollingForward) {
            val itemsFromEnd = totalItemCount - clampedLastVisible - 1
            if (itemsFromEnd <= prefetchDistance
                && forwardJob?.isActive != true
                && !paginator.lockGoNextPage
                && paginator.cache.endContextPage < paginator.finalPage
            ) {
                forwardJob = scope.launch {
                    try {
                        paginator.goNextPage(
                            silentlyLoading = silentlyLoading,
                            silentlyResult = silentlyResult,
                            finalPage = paginator.finalPage,
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

        // ── Backward prefetch ───────────────────────────────────────────
        if (enableBackwardPrefetch && scrollingBackward) {
            if (firstVisibleIndex <= prefetchDistance
                && backwardJob?.isActive != true
                && !paginator.lockGoPreviousPage
                && paginator.cache.startContextPage > 1
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

    /**
     * Cancels any in-flight prefetch jobs.
     *
     * Does **not** disable the controller — new prefetch jobs can still be
     * launched on subsequent [onScroll] calls. To fully stop prefetching,
     * set [enabled] to `false` before or after calling this method.
     */
    fun cancel() {
        forwardJob?.cancel()
        forwardJob = null
        backwardJob?.cancel()
        backwardJob = null
    }

    /**
     * Resets the controller to its initial state: cancels in-flight jobs
     * and clears the calibration, so the next [onScroll] call will be
     * treated as the first one again (calibration, no prefetch).
     */
    fun reset() {
        cancel()
        initialized = false
        prevFirstVisible = -1
        prevLastVisible = -1
    }
}
