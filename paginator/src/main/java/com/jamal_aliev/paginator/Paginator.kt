package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.PagingCore.Companion.DEFAULT_CAPACITY
import com.jamal_aliev.paginator.bookmark.Bookmark
import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import com.jamal_aliev.paginator.exception.FinalPageExceededException
import com.jamal_aliev.paginator.exception.LoadGuardedException
import com.jamal_aliev.paginator.exception.LockedException.GoNextPageWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.GoPreviousPageWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.JumpWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.RefreshWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.RestartWasLockedException
import com.jamal_aliev.paginator.extension.isProgressState
import com.jamal_aliev.paginator.initializer.InitializerEmptyPage
import com.jamal_aliev.paginator.initializer.InitializerErrorPage
import com.jamal_aliev.paginator.initializer.InitializerProgressPage
import com.jamal_aliev.paginator.initializer.InitializerSuccessPage
import com.jamal_aliev.paginator.logger.PaginatorLogger
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PageState.ProgressPage
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A read-only, reactive pagination manager for Kotlin/Android.
 *
 * `Paginator` handles bidirectional navigation, bookmark-based jumping,
 * and emits state updates via Kotlin Flows. All cache-related state and
 * operations are delegated to [core] ([PagingCore]).
 *
 * For element-level CRUD, capacity management, and state manipulation,
 * use [MutablePaginator].
 *
 * **Key concepts:**
 * - **Context window** ([core].startContextPage..[core].endContextPage): the contiguous
 *   range of filled success pages visible to the UI via the [core].snapshot flow.
 * - **Bookmarks**: predefined page targets for quick navigation via [jumpForward]/[jumpBack].
 * - **Load guard**: an optional callback on navigation functions that can veto a page load
 *   before the network request is made, throwing [LoadGuardedException] when rejected.
 *
 * **Page number contract:** page numbers are always >= 1. A value of `0` for
 * context pages means "not started yet".
 *
 * **Coroutine safety:** all mutations to cache are serialized through [navigationMutex],
 * so concurrent coroutine calls (e.g. [goNextPage] + [refresh]) are safe.
 *
 * **Java-thread safety is NOT guaranteed** — do not access a [Paginator] from
 * raw Java threads without external synchronization.
 *
 * @param T The type of elements contained in each page.
 * @param source A suspending lambda that loads data for a given page number.
 *   The receiver is the paginator itself, giving access to its properties during loading.
 *
 * @see PageState
 * @see Bookmark
 * @see MutablePaginator
 * @see PagingCore
 */
open class Paginator<T>(
    val core: PagingCore<T> = PagingCore<T>(DEFAULT_CAPACITY),
    var source: suspend Paginator<T>.(page: Int) -> List<T>
) : Comparable<Paginator<*>> {

    /** Mutex serialising all mutations to cache across concurrent coroutines. */
    protected val navigationMutex = Mutex()


    /**
     * Logger for observing paginator operations.
     *
     * Setting this also updates [core]'s logger so cache operations are logged too.
     *
     * @see PaginatorLogger
     */
    var logger: PaginatorLogger? = null

    // ──────────────────────────────────────────────────────────────────────────
    //  Final page
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * The Maximum page number allowed for pagination.
     *
     * Used as an upper border when pagination. If you try to paginate
     * (e.g. via [goNextPage] or [jump]) the requested page number
     * exceeds [finalPage], [FinalPageExceededException] will be thrown.
     *
     * The default value is [Int.MAX_VALUE], which means there is no limit.
     */
    var finalPage: Int = Int.MAX_VALUE

    @Suppress("NOTHING_TO_INLINE")
    private inline fun exceedsFinal(
        page: Int,
        finalPage: Int = this.finalPage
    ): Boolean {
        return page > finalPage
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Bookmarks
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Predefined page targets for quick navigation via [jumpForward] and [jumpBack].
     *
     * By default, contains a single bookmark pointing to page 1. You can add, remove, or
     * replace bookmarks at any time. The internal iterator tracks the current position
     * within this list.
     */
    val bookmarks: MutableList<Bookmark> = mutableListOf(BookmarkInt(page = 1))

    /**
     * If `true`, bookmark navigation ([jumpForward]/[jumpBack]) wraps around when
     * reaching the end/beginning of the [bookmarks] list. Default: `false`.
     */
    var recyclingBookmark = false
    protected var bookmarkIterator = bookmarks.listIterator()

    // ──────────────────────────────────────────────────────────────────────────
    //  Jump forward / back
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Moves forward to the next bookmark in the [bookmarks] list whose page is
     * **outside** the current visible snapshot range, and jumps to it.
     *
     * Bookmarks whose pages fall within the snapshot range (i.e., already visible
     * on the UI) are skipped, since jumping to them would be a no-op for the user.
     * If all remaining bookmarks are visible, the function falls back to the last
     * one encountered (preserving the old behavior).
     *
     * If the bookmark iterator has reached the end and [recycling] is `true`,
     * the iterator resets to the beginning and continues from the first bookmark.
     *
     * This function delegates to [jump] for the actual page loading.
     *
     * @param recycling If `true`, wraps around to the first bookmark when the end is reached.
     *   Defaults to [recyclingBookmark].
     * @param silentlyLoading If `true`, the snapshot will **not** be emitted during loading.
     * @param silentlyResult If `true`, the snapshot will **not** be emitted after loading.
     * @param finalPage Upper page boundary. Defaults to [Paginator.finalPage].
     * @param loadGuard A guard callback invoked with `(page, currentState)` **before** the page
     *   is loaded from the source. Return `true` to proceed, or `false` to abort.
     *   When `false` is returned, [LoadGuardedException] is thrown.
     *   Defaults to always allowing the load.
     * @param lockJump If `true`, throws [JumpWasLockedException]. Defaults to [Paginator.lockJump].
     * @param enableCacheFlow If `true`, the full cache flow is also updated.
     * @param initProgressState Factory for creating [ProgressPage] instances during loading.
     * @param initSuccessState Factory for creating [SuccessPage] instances on successful load.
     * @param initEmptyState Factory for creating empty page instances when the source returns no data.
     * @param initErrorState Factory for creating error page instances when the source throws.
     * @return A [Pair] of the [Bookmark] and resulting [PageState], or `null` if no bookmark is available.
     * @throws JumpWasLockedException If [lockJump] is `true`.
     * @throws FinalPageExceededException If the bookmark page exceeds [finalPage].
     * @throws LoadGuardedException If [loadGuard] returns `false`.
     */
    suspend fun jumpForward(
        recycling: Boolean = recyclingBookmark,
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        finalPage: Int = this.finalPage,
        loadGuard: (page: Int, state: PageState<T>?) -> Boolean = { _, _ -> true },
        lockJump: Boolean = this.lockJump,
        enableCacheFlow: Boolean = core.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = core.initializerProgressPage,
        initSuccessState: InitializerSuccessPage<T> = core.initializerSuccessPage,
        initEmptyState: InitializerEmptyPage<T> = core.initializerEmptyPage,
        initErrorState: InitializerErrorPage<T> = core.initializerErrorPage
    ): Pair<Bookmark, PageState<T>>? {
        if (lockJump) throw JumpWasLockedException()
        logger?.log(TAG, "jumpForward: recycling=$recycling")

        val visibleRange: IntRange? = core.snapshotPageRange()
        var lastSkippedBookmark: Bookmark? = null
        var bookmark: Bookmark? = null
        var iteratorInvalidated = false

        // Phase 1: iterate forward, skipping bookmarks inside the visible range.
        try {
            while (bookmarkIterator.hasNext()) {
                val candidate: Bookmark = bookmarkIterator.next()
                if (visibleRange != null && candidate.page in visibleRange) {
                    lastSkippedBookmark = candidate
                    continue
                }
                bookmark = candidate
                break
            }
        } catch (_: ConcurrentModificationException) {
            iteratorInvalidated = true
        }

        // Phase 2: reset iterator and try again
        if (bookmark == null && (recycling || iteratorInvalidated)) {
            bookmarkIterator = bookmarks.listIterator()
            while (bookmarkIterator.hasNext()) {
                val candidate = bookmarkIterator.next()
                if (visibleRange != null && candidate.page in visibleRange) {
                    lastSkippedBookmark = candidate
                    continue
                }
                bookmark = candidate
                break
            }
        }

        // Phase 3: fallback
        if (bookmark == null) {
            bookmark = lastSkippedBookmark
        }

        if (bookmark != null) {
            return jump(
                bookmark = bookmark,
                silentlyLoading = silentlyLoading,
                silentlyResult = silentlyResult,
                finalPage = finalPage,
                loadGuard = loadGuard,
                enableCacheFlow = enableCacheFlow,
                initProgressState = initProgressState,
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState,
            )
        }

        logger?.log(TAG, "jumpForward: no bookmark available")
        return null
    }

    /**
     * Moves backward to the previous bookmark in the [bookmarks] list whose page is
     * **outside** the current visible snapshot range, and jumps to it.
     *
     * @see jumpForward for full parameter documentation.
     */
    suspend fun jumpBack(
        recycling: Boolean = recyclingBookmark,
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        finalPage: Int = this.finalPage,
        loadGuard: (page: Int, state: PageState<T>?) -> Boolean = { _, _ -> true },
        lockJump: Boolean = this.lockJump,
        enableCacheFlow: Boolean = core.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = core.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = core.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = core.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = core.initializerErrorPage
    ): Pair<Bookmark, PageState<T>>? {
        if (lockJump) throw JumpWasLockedException()
        logger?.log(TAG, "jumpBack: recycling=$recycling")

        val visibleRange: IntRange? = core.snapshotPageRange()
        var lastSkippedBookmark: Bookmark? = null
        var bookmark: Bookmark? = null
        var iteratorInvalidated = false

        // Phase 1: iterate backward, skipping bookmarks inside the visible range.
        try {
            while (bookmarkIterator.hasPrevious()) {
                val candidate: Bookmark = bookmarkIterator.previous()
                if (visibleRange != null && candidate.page in visibleRange) {
                    lastSkippedBookmark = candidate
                    continue
                }
                bookmark = candidate
                break
            }
        } catch (_: ConcurrentModificationException) {
            iteratorInvalidated = true
        }

        // Phase 2: reset iterator to end and try again
        if (bookmark == null && (recycling || iteratorInvalidated)) {
            bookmarkIterator = bookmarks.listIterator(bookmarks.size)
            while (bookmarkIterator.hasPrevious()) {
                val candidate = bookmarkIterator.previous()
                if (visibleRange != null && candidate.page in visibleRange) {
                    lastSkippedBookmark = candidate
                    continue
                }
                bookmark = candidate
                break
            }
        }

        // Phase 3: fallback
        if (bookmark == null) {
            bookmark = lastSkippedBookmark
        }

        if (bookmark != null) {
            return jump(
                bookmark = bookmark,
                silentlyLoading = silentlyLoading,
                silentlyResult = silentlyResult,
                finalPage = finalPage,
                loadGuard = loadGuard,
                enableCacheFlow = enableCacheFlow,
                initProgressState = initProgressState,
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState,
            )
        }

        logger?.log(TAG, "jumpBack: no bookmark available")
        return null
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Lock flags
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * If `true`, all jump operations ([jump], [jumpForward], [jumpBack]) are blocked
     * and will throw [JumpWasLockedException]. Reset to `false` on [release].
     */
    var lockJump = false

    /**
     * If `true`, [goNextPage] is blocked and will throw [GoNextPageWasLockedException].
     * Reset to `false` on [release].
     */
    var lockGoNextPage: Boolean = false

    /**
     * If `true`, [goPreviousPage] is blocked and will throw [GoPreviousPageWasLockedException].
     * Reset to `false` on [release].
     */
    var lockGoPreviousPage: Boolean = false

    /**
     * If `true`, [restart] is blocked and will throw [RestartWasLockedException].
     * Reset to `false` on [release].
     */
    var lockRestart: Boolean = false

    /**
     * If `true`, [refresh] is blocked and will throw [RefreshWasLockedException].
     * Reset to `false` on [release].
     */
    var lockRefresh: Boolean = false

    // ──────────────────────────────────────────────────────────────────────────
    //  Jump
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Jumps directly to a specific page identified by [bookmark].
     *
     * If the target page is already cached as a filled success page, it is returned
     * immediately without reloading. Otherwise, the context window is reset to the
     * target page and the page is loaded from the [source].
     *
     * @param bookmark The target page bookmark. Must have `page >= 1`.
     * @param silentlyLoading If `true`, the snapshot will **not** be emitted when the
     *   page transitions to [ProgressPage].
     * @param silentlyResult If `true`, the snapshot will **not** be emitted after the
     *   page finishes loading.
     * @param finalPage Upper page boundary for this call. Defaults to [Paginator.finalPage].
     * @param loadGuard A guard callback invoked with `(page, currentState)` **before** the page
     *   is loaded from the source. Return `true` to proceed, or `false` to abort.
     * @param lockJump If `true`, the operation is blocked and [JumpWasLockedException]
     *   is thrown immediately. Defaults to [Paginator.lockJump].
     * @param enableCacheFlow If `true`, the full cache flow is also updated.
     * @param initProgressState Factory for creating [ProgressPage] instances during loading.
     * @param initEmptyState Factory for creating empty page instances.
     * @param initSuccessState Factory for creating [SuccessPage] instances on successful load.
     * @param initErrorState Factory for creating error page instances.
     * @return A [Pair] of the [Bookmark] and the resulting [PageState].
     * @throws JumpWasLockedException If [lockJump] is `true`.
     * @throws FinalPageExceededException If [bookmark] page exceeds [finalPage].
     * @throws LoadGuardedException If [loadGuard] returns `false`.
     * @throws IllegalArgumentException If [bookmark] page is < 1.
     */
    suspend fun jump(
        bookmark: Bookmark,
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        finalPage: Int = this.finalPage,
        loadGuard: (page: Int, state: PageState<T>?) -> Boolean = { _, _ -> true },
        lockJump: Boolean = this.lockJump,
        enableCacheFlow: Boolean = core.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = core.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = core.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = core.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = core.initializerErrorPage
    ): Pair<Bookmark, PageState<T>> = coroutineScope {
        if (lockJump) throw JumpWasLockedException()

        require(bookmark.page >= 1) { "bookmark.page should be >= 1, but was ${bookmark.page}" }
        logger?.log(TAG, "jump: page=${bookmark.page}")

        if (exceedsFinal(bookmark.page, finalPage)) {
            throw FinalPageExceededException(
                attemptedPage = bookmark.page,
                finalPage = finalPage
            )
        }

        return@coroutineScope navigationMutex.withLock {
            val probablySuccessBookmarkPage: PageState<T>? = core.getStateOf(bookmark.page)
            if (core.isFilledSuccessState(probablySuccessBookmarkPage)) {
                core.expandStartContextPage(probablySuccessBookmarkPage)
                core.expandEndContextPage(probablySuccessBookmarkPage)
                if (!silentlyResult) {
                    core.snapshot()
                }
                logger?.log(TAG, "jump: page=${bookmark.page} cache hit")
                refreshDirtyPagesInContext()
                return@withLock bookmark to probablySuccessBookmarkPage
            }

            if (!loadGuard.invoke(bookmark.page, probablySuccessBookmarkPage)) {
                throw LoadGuardedException(attemptedPage = bookmark.page)
            }

            core.startContextPage = bookmark.page
            core.endContextPage = bookmark.page

            val resultState: PageState<T> = loadOrGetPageState(
                page = bookmark.page,
                forceLoading = true,
                loading = { page: Int, pageState: PageState<T>? ->
                    val data: List<T> = pageState?.data ?: mutableListOf()
                    val progressState: ProgressPage<T> = initProgressState.invoke(page, data)
                    core.setState(
                        state = progressState,
                        silently = true,
                    )
                    core.expandStartContextPage(progressState)
                    core.expandEndContextPage(progressState)

                    if (enableCacheFlow) {
                        core.repeatCacheFlow()
                    }
                    if (!silentlyLoading) {
                        core.snapshot()
                    }
                },
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState
            )
            core.setState(
                state = resultState,
                silently = true
            )
            core.expandStartContextPage(resultState)
            core.expandEndContextPage(resultState)

            if (enableCacheFlow) {
                core.repeatCacheFlow()
            }
            if (!silentlyResult) {
                core.snapshot()
            }

            logger?.log(TAG, "jump: page=${bookmark.page} result=${resultState::class.simpleName}")
            refreshDirtyPagesInContext()
            return@withLock bookmark to resultState
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Go next / previous
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Loads the next page after the current [core].endContextPage.
     *
     * If the paginator has not been started yet (i.e., no pages have been loaded),
     * this function automatically performs a [jump] to page 1.
     *
     * @param silentlyLoading If `true`, the snapshot will **not** be emitted during loading.
     * @param silentlyResult If `true`, the snapshot will **not** be emitted after loading.
     * @param finalPage Upper page boundary. Defaults to [Paginator.finalPage].
     * @param loadGuard A guard callback invoked before the page is loaded.
     * @param lockGoNextPage If `true`, throws [GoNextPageWasLockedException].
     * @param enableCacheFlow If `true`, the full cache flow is also updated.
     * @param initProgressState Factory for creating [ProgressPage] instances during loading.
     * @param initEmptyState Factory for creating empty page instances.
     * @param initSuccessState Factory for creating [SuccessPage] instances on successful load.
     * @param initErrorState Factory for creating error page instances.
     * @return The resulting [PageState] of the loaded page.
     * @throws GoNextPageWasLockedException If [lockGoNextPage] is `true`.
     * @throws FinalPageExceededException If the next page exceeds [finalPage].
     * @throws LoadGuardedException If [loadGuard] returns `false`.
     */
    suspend fun goNextPage(
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        finalPage: Int = this.finalPage,
        loadGuard: (page: Int, state: PageState<T>?) -> Boolean = { _, _ -> true },
        lockGoNextPage: Boolean = this.lockGoNextPage,
        enableCacheFlow: Boolean = core.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = core.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = core.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = core.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = core.initializerErrorPage
    ): PageState<T> = coroutineScope {
        if (lockGoNextPage) throw GoNextPageWasLockedException()
        logger?.log(TAG, "goNextPage: endContextPage=${core.endContextPage}")
        if (!core.isStarted) {
            logger?.log(TAG, "goNextPage: not started, jumping to page 1")
            val pageState: PageState<T> = jump(
                bookmark = BookmarkInt(page = 1),
                silentlyLoading = silentlyLoading,
                silentlyResult = silentlyResult,
                finalPage = finalPage,
                enableCacheFlow = enableCacheFlow,
                initProgressState = initProgressState,
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState,
            ).second
            return@coroutineScope pageState
        }

        return@coroutineScope navigationMutex.withLock {
            var pivotContextPage: Int = core.endContextPage
            var pivotContextPageState: PageState<T>? = core.getStateOf(pivotContextPage)
            val isPivotContextPageValid: Boolean = core.isFilledSuccessState(pivotContextPageState)
            if (isPivotContextPageValid) {
                core.expandEndContextPage(core.getStateOf(pivotContextPage + 1))
                    ?.also { expanded: PageState<T> ->
                        pivotContextPage = expanded.page
                        pivotContextPageState = expanded
                    }
            }

            val nextPage: Int =
                if (isPivotContextPageValid) pivotContextPage + 1
                else pivotContextPage

            if (exceedsFinal(nextPage, finalPage)) {
                throw FinalPageExceededException(
                    attemptedPage = nextPage,
                    finalPage = finalPage
                )
            }

            val nextPageState: PageState<T>? =
                if (nextPage == pivotContextPage) pivotContextPageState
                else core.getStateOf(nextPage)

            if (nextPageState.isProgressState())
                return@withLock nextPageState

            if (!loadGuard.invoke(nextPage, nextPageState)) {
                throw LoadGuardedException(attemptedPage = nextPage)
            }

            loadOrGetPageState(
                page = nextPage,
                forceLoading = true,
                loading = { page: Int, pageState: PageState<T>? ->
                    val data: List<T> = pageState?.data ?: mutableListOf()
                    val progressState: ProgressPage<T> = initProgressState.invoke(page, data)
                    core.setState(
                        state = progressState,
                        silently = true,
                    )
                    if (enableCacheFlow) {
                        core.repeatCacheFlow()
                    }
                    if (!silentlyLoading) {
                        core.snapshot()
                    }
                },
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState
            ).also { resultState ->
                core.setState(
                    state = resultState,
                    silently = true
                )
                if (core.endContextPage == pivotContextPage
                    && core.isFilledSuccessState(resultState)
                ) {
                    core.endContextPage = nextPage
                    core.expandEndContextPage(core.getStateOf(nextPage + 1))
                }
                if (enableCacheFlow) {
                    core.repeatCacheFlow()
                }
                if (!silentlyResult) {
                    core.snapshot()
                }

                logger?.log(
                    TAG,
                    "goNextPage: page=$nextPage result=${resultState::class.simpleName}"
                )
                refreshDirtyPagesInContext()
                return@withLock resultState
            }
        }
    }

    /**
     * Loads the page before the current [core].startContextPage.
     *
     * Requires the paginator to be started (i.e., at least one [jump] must have been performed).
     *
     * @param silentlyLoading If `true`, the snapshot will **not** be emitted during loading.
     * @param silentlyResult If `true`, the snapshot will **not** be emitted after loading.
     * @param loadGuard A guard callback invoked before the page is loaded.
     * @param enableCacheFlow If `true`, the full cache flow is also updated.
     * @param initProgressState Factory for creating [ProgressPage] instances during loading.
     * @param initEmptyState Factory for creating empty page instances.
     * @param initSuccessState Factory for creating [SuccessPage] instances on successful load.
     * @param initErrorState Factory for creating error page instances.
     * @return The resulting [PageState] of the loaded page.
     * @throws GoPreviousPageWasLockedException If [lockGoPreviousPage] is `true`.
     * @throws LoadGuardedException If [loadGuard] returns `false`.
     * @throws IllegalStateException If the paginator has not been started or the previous page is < 1.
     */
    suspend fun goPreviousPage(
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        loadGuard: (page: Int, state: PageState<T>?) -> Boolean = { _, _ -> true },
        enableCacheFlow: Boolean = core.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = core.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = core.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = core.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = core.initializerErrorPage
    ): PageState<T> = coroutineScope {
        if (lockGoPreviousPage) throw GoPreviousPageWasLockedException()
        logger?.log(TAG, "goPreviousPage: startContextPage=${core.startContextPage}")
        check(core.isStarted) {
            "startContextPage=0 or endContextPage=0 so paginator was not jumped (started). " +
                    "First of all paginator must be jumped (started). " +
                    "Please use jump function to start paginator before use goPreviousPage"
        }

        return@coroutineScope navigationMutex.withLock {
            var pivotContextPage: Int = core.startContextPage
            var pivotContextPageState = core.getStateOf(pivotContextPage)
            val pivotContextPageValid = core.isFilledSuccessState(pivotContextPageState)
            if (pivotContextPageValid) {
                core.expandStartContextPage(core.getStateOf(pivotContextPage - 1))
                    ?.also { expanded: PageState<T> ->
                        pivotContextPage = expanded.page
                        pivotContextPageState = expanded
                    }
            }

            val previousPage: Int =
                if (pivotContextPageValid) pivotContextPage - 1
                else pivotContextPage
            check(previousPage >= 1) { "previousPage is $previousPage. you can't go below page 1" }
            val previousPageState: PageState<T>? =
                if (previousPage == pivotContextPage) pivotContextPageState
                else core.getStateOf(previousPage)

            if (previousPageState.isProgressState())
                return@withLock previousPageState

            if (!loadGuard.invoke(previousPage, previousPageState)) {
                throw LoadGuardedException(attemptedPage = previousPage)
            }

            loadOrGetPageState(
                page = previousPage,
                forceLoading = true,
                loading = { page: Int, pageState: PageState<T>? ->
                    val data: List<T> = pageState?.data ?: mutableListOf()
                    val progressState: ProgressPage<T> = initProgressState(page, data)
                    core.setState(
                        state = progressState,
                        silently = true
                    )
                    if (enableCacheFlow) {
                        core.repeatCacheFlow()
                    }
                    if (!silentlyLoading) {
                        core.snapshot()
                    }
                },
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState
            ).also { resultState: PageState<T> ->
                core.setState(
                    state = resultState,
                    silently = true
                )
                if (core.startContextPage == pivotContextPage
                    && core.isFilledSuccessState(resultState)
                ) {
                    core.startContextPage = previousPage
                    core.expandStartContextPage(core.getStateOf(previousPage - 1))
                }
                if (enableCacheFlow) {
                    core.repeatCacheFlow()
                }
                if (!silentlyResult) {
                    core.snapshot()
                }

                logger?.log(
                    TAG,
                    "goPreviousPage: page=$previousPage result=${resultState::class.simpleName}"
                )
                refreshDirtyPagesInContext()
                return@withLock resultState
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Restart
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Resets the paginator to its initial state and reloads the first page.
     *
     * Clears all cached pages except page 1's structure, resets the context window
     * to page 1, and reloads it from the [source]. Ideal for swipe-to-refresh scenarios.
     *
     * @param silentlyLoading If `true`, the snapshot will **not** be emitted during loading.
     * @param silentlyResult If `true`, the snapshot will **not** be emitted after loading.
     * @param loadGuard A guard callback invoked before page 1 is reloaded.
     * @param enableCacheFlow If `true`, the full cache flow is also updated.
     * @param initProgressState Factory for creating [ProgressPage] instances during loading.
     * @param initEmptyState Factory for creating empty page instances.
     * @param initSuccessState Factory for creating [SuccessPage] instances on successful load.
     * @param initErrorState Factory for creating error page instances.
     * @throws RestartWasLockedException If [lockRestart] is `true`.
     * @throws LoadGuardedException If [loadGuard] returns `false`.
     */
    suspend fun restart(
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        loadGuard: (page: Int, state: PageState<T>?) -> Boolean = { _, _ -> true },
        enableCacheFlow: Boolean = core.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = core.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = core.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = core.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = core.initializerErrorPage
    ): Unit = coroutineScope {
        if (lockRestart) throw RestartWasLockedException()
        logger?.log(TAG, "restart")

        return@coroutineScope navigationMutex.withLock {
            val firstPage: PageState<T>? = core.getStateOf(1)
            core.clear()
            if (firstPage != null) {
                core.setState(
                    state = firstPage,
                    silently = true
                )
            }

            core.clearAllDirty()

            if (!loadGuard.invoke(1, firstPage)) {
                throw LoadGuardedException(attemptedPage = 1)
            }

            core.startContextPage = 1
            core.endContextPage = 1
            loadOrGetPageState(
                page = 1,
                forceLoading = true,
                loading = { page, pageState ->
                    val dataOfState: List<T> = pageState?.data ?: mutableListOf()
                    val progressState: ProgressPage<T> = initProgressState.invoke(page, dataOfState)
                    core.setState(
                        state = progressState,
                        silently = true
                    )
                    if (enableCacheFlow) {
                        core.repeatCacheFlow()
                    }
                    if (!silentlyLoading) {
                        core.snapshot(1..1)
                    }
                },
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState
            ).also { resultPageState ->
                core.setState(
                    state = resultPageState,
                    silently = true
                )
                if (enableCacheFlow) {
                    core.repeatCacheFlow()
                }
                if (!silentlyResult) {
                    core.snapshot(1..1)
                }
                logger?.log(TAG, "restart: result=${resultPageState::class.simpleName}")
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Refresh
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Refreshes the specified pages by reloading them from the [source] in parallel.
     *
     * Each page is first set to [ProgressPage] (preserving any previously cached data),
     * then all pages are reloaded concurrently. After all loads complete, the cache is
     * updated with the results.
     *
     * @param pages The list of page numbers to refresh.
     * @param loadingSilently If `true`, the snapshot will **not** be emitted after setting
     *   pages to [ProgressPage].
     * @param finalSilently If `true`, the snapshot will **not** be emitted after all pages
     *   finish loading.
     * @param loadGuard A guard callback invoked for each page before loading.
     * @param enableCacheFlow If `true`, the full cache flow is also updated.
     * @param initProgressState Factory for creating [ProgressPage] instances during loading.
     * @param initEmptyState Factory for creating empty page instances.
     * @param initSuccessState Factory for creating [SuccessPage] instances on successful load.
     * @param initErrorState Factory for creating error page instances.
     * @throws RefreshWasLockedException If [lockRefresh] is `true`.
     * @throws LoadGuardedException If [loadGuard] returns `false` for any page.
     */
    suspend fun refresh(
        pages: List<Int>,
        loadingSilently: Boolean = false,
        finalSilently: Boolean = false,
        loadGuard: (page: Int, state: PageState<T>?) -> Boolean = { _, _ -> true },
        enableCacheFlow: Boolean = core.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = core.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = core.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = core.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = core.initializerErrorPage
    ): Unit = coroutineScope {
        if (lockRefresh) throw RefreshWasLockedException()
        logger?.log(TAG, "refresh: pages=$pages")

        // Phase 1: validate guards and set progress states under lock
        navigationMutex.withLock {
            pages.forEach { page: Int ->
                if (!loadGuard.invoke(page, core.getStateOf(page))) {
                    throw LoadGuardedException(attemptedPage = page)
                }
            }
            pages.forEach { page: Int ->
                val dataOfPage = core.getStateOf(page)?.data ?: mutableListOf()
                val progressState = initProgressState.invoke(page, dataOfPage)
                core.setState(
                    state = progressState,
                    silently = true
                )
            }
        }
        if (enableCacheFlow) {
            core.repeatCacheFlow()
        }
        if (!loadingSilently) {
            core.snapshot()
        }

        // Phase 2: load pages in parallel (network I/O — outside lock)
        pages.map { page: Int ->
            async {
                loadOrGetPageState(
                    page = page,
                    forceLoading = true,
                    initEmptyState = initEmptyState,
                    initSuccessState = initSuccessState,
                    initErrorState = initErrorState
                )
            }
        }.awaitAll().let { results: List<PageState<T>> ->
            // Phase 3: write results back under lock
            navigationMutex.withLock {
                results.forEach { finalPageState: PageState<T> ->
                    core.setState(
                        state = finalPageState,
                        silently = true
                    )
                }
            }
        }

        core.clearDirty(pages)

        if (enableCacheFlow) {
            core.repeatCacheFlow()
        }
        if (!finalSilently) {
            core.snapshot()
        }
        logger?.log(TAG, "refresh: pages=$pages completed")
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Load or get page state
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Loads a page from the [source] or returns the cached state if it is already a filled success page.
     *
     * This is the low-level loading primitive used internally by [goNextPage], [goPreviousPage],
     * [jump], [restart], and [refresh]. It does **not** update context pages or emit snapshots —
     * callers are responsible for that.
     *
     * @param page The page number to load.
     * @param forceLoading If `true`, always reloads from [source] even if a valid cached state exists.
     * @param loading A callback invoked just before loading starts.
     * @param source The data source suspend function. Defaults to [Paginator.source].
     * @param initEmptyState Factory for empty page instances.
     * @param initSuccessState Factory for success page instances.
     * @param initErrorState Factory for error page instances.
     * @return The resulting [PageState] after loading or from cache.
     */
    suspend inline fun loadOrGetPageState(
        page: Int,
        forceLoading: Boolean = false,
        loading: ((page: Int, pageState: PageState<T>?) -> Unit) = { _, _ -> },
        noinline source: suspend Paginator<T>.(page: Int) -> List<T> = this.source,
        noinline initEmptyState: InitializerEmptyPage<T> = core.initializerEmptyPage,
        noinline initSuccessState: InitializerSuccessPage<T> = core.initializerSuccessPage,
        noinline initErrorState: InitializerErrorPage<T> = core.initializerErrorPage
    ): PageState<T> {
        logger?.log(TAG, "loadOrGetPageState: page=$page forceLoading=$forceLoading")
        val cachedState: PageState<T>? = core.getStateOf(page)
        if (!forceLoading && core.isFilledSuccessState(cachedState)) {
            logger?.log(
                TAG,
                "loadOrGetPageState: page=$page cachedState(isFilledSuccessState)=$cachedState"
            )
            return cachedState
        }
        loading.invoke(page, cachedState)
        return try {
            val data: MutableList<T> =
                source.invoke(this, page)
                    .toMutableList()
            if (data.isEmpty()) {
                logger?.log(TAG, "loadOrGetPageState: page=$page data.isEmpty()")
                initEmptyState.invoke(page, data)
            } else {
                logger?.log(TAG, "loadOrGetPageState: page=$page data.isNotEmpty()")
                initSuccessState.invoke(page, data)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger?.log(TAG, "loadOrGetPageState: page=$page exception=$exception")
            initErrorState.invoke(exception, page, cachedState?.data ?: mutableListOf())
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Dirty pages refresh helper
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Launches a fire-and-forget refresh for all dirty pages within the current context window.
     *
     * Finds dirty pages that fall within startContextPage..endContextPage,
     * removes them from the dirty set, and starts a parallel [refresh] for those pages.
     */
    protected fun CoroutineScope.refreshDirtyPagesInContext() {
        if (!core.isStarted) return
        val dirtyInContext: List<Int> =
            core.drainDirtyPagesInRange(core.startContextPage..core.endContextPage)
                ?: return
        logger?.log(TAG, "refreshDirtyPagesInContext: pages=$dirtyInContext")
        launch {
            refresh(pages = dirtyInContext, loadingSilently = true)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Release
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Releases all resources and resets the paginator to its initial (unconfigured) state.
     *
     * This clears the cache, resets bookmarks to `[page 1]`, resets the context window
     * to `0`, sets [finalPage] back to [Int.MAX_VALUE], unlocks all lock flags,
     * and sets capacity to the given value.
     *
     * Call this method when the paginator is no longer needed (e.g., in `ViewModel.onCleared()`).
     *
     * @param capacity The capacity to set after release. Defaults to [DEFAULT_CAPACITY].
     * @param silently If `true`, the empty snapshot is **not** emitted.
     */
    fun release(
        capacity: Int = DEFAULT_CAPACITY,
        silently: Boolean = false
    ) {
        logger?.log(TAG, "release")
        core.release(capacity, silently)
        bookmarks.clear()
        bookmarks.add(BookmarkInt(page = 1))
        bookmarkIterator = bookmarks.listIterator()
        finalPage = Int.MAX_VALUE
        lockJump = false
        lockGoNextPage = false
        lockGoPreviousPage = false
        lockRestart = false
        lockRefresh = false
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Operators (delegating to cache)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Compares paginators by the number of pages currently held in their cache.
     */
    override operator fun compareTo(other: Paginator<*>): Int = core.size - other.core.size

    operator fun iterator(): MutableIterator<PageState<T>> {
        return core.iterator()
    }

    operator fun contains(page: Int): Boolean = page in core

    operator fun contains(pageState: PageState<T>): Boolean = pageState in core

    operator fun get(page: Int): PageState<T>? = core[page]

    operator fun get(page: Int, index: Int): T? = core[page, index]

    override fun toString(): String = "Paginator(cache=$core, bookmarks=$bookmarks)"

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        const val TAG = "Paginator"
    }
}
