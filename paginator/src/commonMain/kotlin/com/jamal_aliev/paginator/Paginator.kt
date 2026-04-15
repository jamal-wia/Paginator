package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.PagingCore.Companion.DEFAULT_CAPACITY
import com.jamal_aliev.paginator.bookmark.Bookmark
import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import com.jamal_aliev.paginator.cache.PagingCache
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
import com.jamal_aliev.paginator.load.LoadResult
import com.jamal_aliev.paginator.logger.LogComponent
import com.jamal_aliev.paginator.logger.PaginatorLogger
import com.jamal_aliev.paginator.logger.debug
import com.jamal_aliev.paginator.logger.info
import com.jamal_aliev.paginator.logger.warn
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PageState.ProgressPage
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import com.jamal_aliev.paginator.serialization.PaginatorSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * A read-only, reactive pagination manager for Kotlin/Android.
 *
 * `Paginator` handles bidirectional navigation, bookmark-based jumping,
 * and emits state updates via Kotlin Flows. All cache-related state and
 * operations are delegated to [cache] ([PagingCore]).
 *
 * For element-level CRUD, capacity management, and state manipulation,
 * use [MutablePaginator].
 *
 * **Key concepts:**
 * - **Context window** ([cache].startContextPage..[cache].endContextPage): the contiguous
 *   range of filled success pages visible to the UI via the [cache].snapshot flow.
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
 * @param load A suspending lambda that loads data for a given page number.
 *   The receiver is the paginator itself, giving access to its properties during loading.
 *
 * @see PageState
 * @see Bookmark
 * @see MutablePaginator
 * @see PagingCore
 */
open class Paginator<T>(
    val core: PagingCore<T> = PagingCore(),
    var load: suspend Paginator<T>.(page: Int) -> LoadResult<T>
) : Comparable<Paginator<*>> {

    val cache: PagingCache<T> get() = core.cache

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
        set(value) {
            field = value
            core.logger = value
        }

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

    /**
     * Predefined page targets for quick navigation via [jumpForward] and [jumpBack].
     *
     * By default, contains a single bookmark pointing to page 1. You can add, remove, or
     * replace bookmarks at any time. The internal [bookmarkIndex] tracks the current position
     * within this list.
     */
    val bookmarks: MutableList<Bookmark> = mutableListOf(BookmarkInt(page = 1))

    /**
     * If `true`, bookmark navigation ([jumpForward]/[jumpBack]) wraps around when
     * reaching the end/beginning of the [bookmarks] list. Default: `false`.
     */
    var recyclingBookmark = false
    protected var bookmarkIndex: Int = 0

    /**
     * Synchronises [bookmarkIndex] so that it sits right after the last bookmark
     * whose page is ≤ [page]. This keeps [jumpForward]/[jumpBack] consistent
     * after a direct [jump] to an arbitrary page.
     */
    private fun syncBookmarkIndex(page: Int) {
        if (bookmarks.isEmpty()) return
        val index = bookmarks.indexOfFirst { it.page > page }
        bookmarkIndex = if (index == -1) bookmarks.size else index
    }

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
        logger.debug(LogComponent.NAVIGATION) { "jumpForward: recycling=$recycling" }

        val visibleRange: IntRange? = core.snapshotPageRange()
        var lastSkippedBookmark: Bookmark? = null
        var bookmark: Bookmark? = null

        val bookmarkSize: Int = bookmarks.size
        if (bookmarkSize > 0) {
            // Guard against external modification of the bookmarks list
            bookmarkIndex = bookmarkIndex.coerceIn(0, bookmarkSize)

            // Without recycling: scan only from bookmarkIndex to the end.
            // With recycling: scan the entire list, wrapping around to the beginning,
            //   so we visit every bookmark exactly once starting from bookmarkIndex.
            val limit: Int =
                if (recycling) bookmarkSize
                else bookmarkSize - bookmarkIndex

            for (i in 0 until limit) {
                // Modular index allows the loop to wrap past the end back to 0
                val index: Int = (bookmarkIndex + i) % bookmarkSize
                val candidate: Bookmark = bookmarks[index]
                if (visibleRange != null && candidate.page in visibleRange) {
                    lastSkippedBookmark = candidate
                    continue
                }
                bookmark = candidate
                // Advance past the found bookmark so the next jumpForward continues from here
                bookmarkIndex = index + 1
                break
            }

            if (bookmark == null) {
                // No suitable bookmark found outside the visible range.
                // Without recycling: park the index at the end (exhausted).
                // With recycling: keep the index unchanged (next call retries from the same spot).
                bookmarkIndex =
                    if (recycling) bookmarkIndex
                    else bookmarkSize
                // Fall back to the last bookmark that was inside the visible range (if any)
                bookmark = lastSkippedBookmark
            }
        }

        if (bookmark != null) {
            val savedBookmarkIndex = bookmarkIndex
            val result = jump(
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
            bookmarkIndex = savedBookmarkIndex
            return result
        }

        logger.debug(LogComponent.NAVIGATION) { "jumpForward: no bookmark available" }
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
        logger.debug(LogComponent.NAVIGATION) { "jumpBack: recycling=$recycling" }

        val visibleRange: IntRange? = core.snapshotPageRange()
        var lastSkippedBookmark: Bookmark? = null
        var bookmark: Bookmark? = null

        val bookmarkSize: Int = bookmarks.size
        if (bookmarkSize > 0) {
            // Guard against external modification of the bookmarks list
            bookmarkIndex = bookmarkIndex.coerceIn(0, bookmarkSize)

            // Without recycling: scan only from bookmarkIndex back to the beginning.
            // With recycling: scan the entire list, wrapping around to the end,
            //   so we visit every bookmark exactly once going backward from bookmarkIndex.
            val limit: Int =
                if (recycling) bookmarkSize
                else bookmarkIndex

            // i starts from 1: the first candidate is one step before bookmarkIndex
            for (i in 1..limit) {
                // Modular index allows the loop to wrap past 0 back to the end
                val index = (bookmarkIndex - i + bookmarkSize) % bookmarkSize
                val candidate: Bookmark = bookmarks[index]
                if (visibleRange != null && candidate.page in visibleRange) {
                    lastSkippedBookmark = candidate
                    continue
                }
                bookmark = candidate
                // Park the index at the found bookmark so the next jumpBack continues from here
                bookmarkIndex = index
                break
            }

            if (bookmark == null) {
                // No suitable bookmark found outside the visible range.
                // Without recycling: park the index at 0 (exhausted).
                // With recycling: keep the index unchanged (next call retries from the same spot).
                bookmarkIndex =
                    if (recycling) bookmarkIndex
                    else 0
                // Fall back to the last bookmark that was inside the visible range (if any)
                bookmark = lastSkippedBookmark
            }
        }

        if (bookmark != null) {
            val savedBookmarkIndex = bookmarkIndex
            val result = jump(
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
            bookmarkIndex = savedBookmarkIndex
            return result
        }

        logger.debug(LogComponent.NAVIGATION) { "jumpBack: no bookmark available" }
        return null
    }

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

    /**
     * Jumps directly to a specific page identified by [bookmark].
     *
     * If the target page is already cached as a filled success page, it is returned
     * immediately without reloading. Otherwise, the context window is reset to the
     * target page and the page is loaded from the [load].
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
        logger.debug(LogComponent.NAVIGATION) { "jump: page=${bookmark.page}" }

        if (exceedsFinal(bookmark.page, finalPage)) {
            throw FinalPageExceededException(
                attemptedPage = bookmark.page,
                finalPage = finalPage
            )
        }

        var savedStartContextPage: Int = cache.startContextPage
        var savedEndContextPage: Int = cache.endContextPage
        var savedPageState: PageState<T>? = null
        var shouldCleanup = false

        navigationMutex.lock()
        try {
            var probablySuccessBookmarkPage: PageState<T>? = cache.getStateOf(bookmark.page)
            if (probablySuccessBookmarkPage == null) {
                probablySuccessBookmarkPage = core.loadFromPersistentCache(bookmark.page)
            }
            if (core.isFilledSuccessState(probablySuccessBookmarkPage)) {
                core.expandStartContextPage(probablySuccessBookmarkPage)
                core.expandEndContextPage(probablySuccessBookmarkPage)
                if (!silentlyResult) {
                    core.snapshot()
                }
                logger.debug(LogComponent.NAVIGATION) { "jump: page=${bookmark.page} cache hit" }
                syncBookmarkIndex(bookmark.page)
                refreshDirtyPagesInContext()
                return@coroutineScope bookmark to probablySuccessBookmarkPage
            }

            if (!loadGuard.invoke(bookmark.page, probablySuccessBookmarkPage)) {
                throw LoadGuardedException(attemptedPage = bookmark.page)
            }

            savedStartContextPage = cache.startContextPage
            savedEndContextPage = cache.endContextPage
            savedPageState = probablySuccessBookmarkPage
            shouldCleanup = true

            core.startContextPage = bookmark.page
            core.endContextPage = bookmark.page

            val resultState: PageState<T> = loadOrGetPageState(
                page = bookmark.page,
                forceLoading = true,
                loading = { page: Int, pageState: PageState<T>? ->
                    val data: List<T> = coerceToCapacity(pageState?.data ?: mutableListOf())
                    val progressState: ProgressPage<T> = coerceToCapacity(
                        state = initProgressState.invoke(page, data)
                    ) as ProgressPage
                    cache.setState(
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
            shouldCleanup = false
            cache.setState(
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

            logger.debug(LogComponent.NAVIGATION) { "jump: page=${bookmark.page} result=${resultState::class.simpleName}" }
            persistSuccessState(resultState)
            syncBookmarkIndex(bookmark.page)
            refreshDirtyPagesInContext()
            return@coroutineScope bookmark to resultState
        } catch (e: CancellationException) {
            if (shouldCleanup) {
                core.startContextPage = savedStartContextPage
                core.endContextPage = savedEndContextPage
                if (savedPageState != null) {
                    cache.setState(savedPageState, silently = true)
                } else {
                    cache.removeFromCache(bookmark.page)
                }
                core.snapshot()
            }
            throw e
        } finally {
            navigationMutex.unlock()
        }
    }

    /**
     * Loads the next page after the current [cache].endContextPage.
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
        logger.debug(LogComponent.NAVIGATION) { "goNextPage: endContextPage=${cache.endContextPage}" }
        if (!cache.isStarted) {
            logger.debug(LogComponent.NAVIGATION) { "goNextPage: not started, jumping to page 1" }
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

        var savedNextPage = -1
        var savedNextPageState: PageState<T>? = null
        var shouldCleanup = false

        navigationMutex.lock()
        try {
            var pivotContextPage: Int = cache.endContextPage
            var pivotContextPageState: PageState<T>? = cache.getStateOf(pivotContextPage)
            val isPivotContextPageValid: Boolean = core.isFilledSuccessState(pivotContextPageState)
            if (isPivotContextPageValid) {
                core.expandEndContextPage(cache.getStateOf(pivotContextPage + 1))
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

            var nextPageState: PageState<T>? =
                if (nextPage == pivotContextPage) pivotContextPageState
                else cache.getStateOf(nextPage)

            if (nextPageState == null) {
                nextPageState = core.loadFromPersistentCache(nextPage)
            }

            if (nextPageState.isProgressState())
                return@coroutineScope nextPageState

            if (core.isFilledSuccessState(nextPageState)) {
                core.endContextPage = nextPage
                core.expandEndContextPage(cache.getStateOf(nextPage + 1))
                if (enableCacheFlow) core.repeatCacheFlow()
                if (!silentlyResult) core.snapshot()
                refreshDirtyPagesInContext()
                return@coroutineScope nextPageState
            }

            if (!loadGuard.invoke(nextPage, nextPageState)) {
                throw LoadGuardedException(attemptedPage = nextPage)
            }

            savedNextPage = nextPage
            savedNextPageState = nextPageState
            shouldCleanup = true

            loadOrGetPageState(
                page = nextPage,
                forceLoading = true,
                loading = { page: Int, pageState: PageState<T>? ->
                    val data: List<T> = coerceToCapacity(pageState?.data ?: mutableListOf())
                    val progressState: ProgressPage<T> = coerceToCapacity(
                        state = initProgressState.invoke(page, data)
                    ) as ProgressPage
                    cache.setState(
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
                shouldCleanup = false
                cache.setState(
                    state = resultState,
                    silently = true
                )
                if (cache.endContextPage == pivotContextPage
                    && core.isFilledSuccessState(resultState)
                ) {
                    core.endContextPage = nextPage
                    core.expandEndContextPage(cache.getStateOf(nextPage + 1))
                }
                if (enableCacheFlow) {
                    core.repeatCacheFlow()
                }
                if (!silentlyResult) {
                    core.snapshot()
                }

                logger.debug(LogComponent.NAVIGATION) {
                    "goNextPage: page=$nextPage result=${resultState::class.simpleName}"
                }
                persistSuccessState(resultState)
                refreshDirtyPagesInContext()
            }
        } catch (e: CancellationException) {
            if (shouldCleanup) {
                if (savedNextPageState != null) {
                    cache.setState(savedNextPageState, silently = true)
                } else {
                    cache.removeFromCache(savedNextPage)
                }
                core.snapshot()
            }
            throw e
        } finally {
            navigationMutex.unlock()
        }
    }

    /**
     * Loads the page before the current [cache].startContextPage.
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
        logger.debug(LogComponent.NAVIGATION) { "goPreviousPage: startContextPage=${cache.startContextPage}" }
        check(cache.isStarted) {
            "startContextPage=0 or endContextPage=0 so paginator was not jumped (started). " +
                    "First of all paginator must be jumped (started). " +
                    "Please use jump function to start paginator before use goPreviousPage"
        }

        var savedPreviousPage = -1
        var savedPreviousPageState: PageState<T>? = null
        var shouldCleanup = false

        navigationMutex.lock()
        try {
            var pivotContextPage: Int = cache.startContextPage
            var pivotContextPageState = cache.getStateOf(pivotContextPage)
            val pivotContextPageValid = core.isFilledSuccessState(pivotContextPageState)
            if (pivotContextPageValid) {
                core.expandStartContextPage(cache.getStateOf(pivotContextPage - 1))
                    ?.also { expanded: PageState<T> ->
                        pivotContextPage = expanded.page
                        pivotContextPageState = expanded
                    }
            }

            val previousPage: Int =
                if (pivotContextPageValid) pivotContextPage - 1
                else pivotContextPage
            check(previousPage >= 1) { "previousPage is $previousPage. you can't go below page 1" }
            var previousPageState: PageState<T>? =
                if (previousPage == pivotContextPage) pivotContextPageState
                else cache.getStateOf(previousPage)

            if (previousPageState == null) {
                previousPageState = core.loadFromPersistentCache(previousPage)
            }

            if (previousPageState.isProgressState())
                return@coroutineScope previousPageState

            if (core.isFilledSuccessState(previousPageState)) {
                core.startContextPage = previousPage
                core.expandStartContextPage(cache.getStateOf(previousPage - 1))
                if (enableCacheFlow) core.repeatCacheFlow()
                if (!silentlyResult) core.snapshot()
                refreshDirtyPagesInContext()
                return@coroutineScope previousPageState!!
            }

            if (!loadGuard.invoke(previousPage, previousPageState)) {
                throw LoadGuardedException(attemptedPage = previousPage)
            }

            savedPreviousPage = previousPage
            savedPreviousPageState = previousPageState
            shouldCleanup = true

            loadOrGetPageState(
                page = previousPage,
                forceLoading = true,
                loading = { page: Int, pageState: PageState<T>? ->
                    val data: List<T> = coerceToCapacity(pageState?.data ?: mutableListOf())
                    val progressState: ProgressPage<T> = coerceToCapacity(
                        state = initProgressState.invoke(page, data)
                    ) as ProgressPage
                    cache.setState(
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
                shouldCleanup = false
                cache.setState(
                    state = resultState,
                    silently = true
                )
                if (cache.startContextPage == pivotContextPage
                    && core.isFilledSuccessState(resultState)
                ) {
                    core.startContextPage = previousPage
                    core.expandStartContextPage(cache.getStateOf(previousPage - 1))
                }
                if (enableCacheFlow) {
                    core.repeatCacheFlow()
                }
                if (!silentlyResult) {
                    core.snapshot()
                }

                logger.debug(LogComponent.NAVIGATION) {
                    "goPreviousPage: page=$previousPage result=${resultState::class.simpleName}"
                }
                persistSuccessState(resultState)
                refreshDirtyPagesInContext()
            }
        } catch (e: CancellationException) {
            if (shouldCleanup) {
                if (savedPreviousPageState != null) {
                    cache.setState(savedPreviousPageState, silently = true)
                } else {
                    cache.removeFromCache(savedPreviousPage)
                }
                core.snapshot()
            }
            throw e
        } finally {
            navigationMutex.unlock()
        }
    }

    /**
     * Resets the paginator to its initial state and reloads the first page.
     *
     * Clears all cached pages except page 1's structure, resets the context window
     * to page 1, and reloads it from the [load]. Ideal for swipe-to-refresh scenarios.
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
        logger.info(LogComponent.LIFECYCLE) { "restart" }

        var savedStartContextPage: Int = cache.startContextPage
        var savedEndContextPage: Int = cache.endContextPage
        var savedFirstPageState: PageState<T>? = null
        var shouldCleanup = false

        navigationMutex.lock()
        try {
            savedStartContextPage = cache.startContextPage
            savedEndContextPage = cache.endContextPage

            val firstPage: PageState<T>? = cache.getStateOf(1)
            cache.clear()
            if (firstPage != null) {
                cache.setState(
                    state = firstPage,
                    silently = true
                )
            }

            core.clearAllDirty()

            if (!loadGuard.invoke(1, firstPage)) {
                throw LoadGuardedException(attemptedPage = 1)
            }

            savedFirstPageState = firstPage
            shouldCleanup = true

            core.startContextPage = 1
            core.endContextPage = 1
            loadOrGetPageState(
                page = 1,
                forceLoading = true,
                loading = { page, pageState ->
                    val dataOfState: List<T> = coerceToCapacity(pageState?.data ?: mutableListOf())
                    val progressState: ProgressPage<T> = coerceToCapacity(
                        state = initProgressState.invoke(page, dataOfState)
                    ) as ProgressPage
                    cache.setState(
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
                shouldCleanup = false
                cache.setState(
                    state = resultPageState,
                    silently = true
                )
                if (enableCacheFlow) {
                    core.repeatCacheFlow()
                }
                if (!silentlyResult) {
                    core.snapshot(1..1)
                }
                syncBookmarkIndex(1)
                logger.debug(LogComponent.NAVIGATION) { "restart: result=${resultPageState::class.simpleName}" }
                persistSuccessState(resultPageState)
            }
        } catch (e: CancellationException) {
            if (shouldCleanup) {
                core.startContextPage = savedStartContextPage
                core.endContextPage = savedEndContextPage
                if (savedFirstPageState != null) {
                    cache.setState(savedFirstPageState, silently = true)
                } else {
                    cache.removeFromCache(1)
                }
                core.snapshot()
            }
            throw e
        } finally {
            navigationMutex.unlock()
        }
    }

    /**
     * Refreshes the specified pages by reloading them from the [load] in parallel.
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
        logger.debug(LogComponent.LIFECYCLE) { "refresh: pages=$pages" }

        var savedStates: Map<Int, PageState<T>?> = emptyMap()

        try {
            // Phase 1: validate guards and set progress states under lock
            navigationMutex.lock()
            try {
                savedStates = pages.associateWith { cache.getStateOf(it) }
                pages.forEach { page: Int ->
                    if (!loadGuard.invoke(page, savedStates[page])) {
                        throw LoadGuardedException(attemptedPage = page)
                    }
                }
                pages.forEach { page: Int ->
                    val dataOfPage = coerceToCapacity(savedStates[page]?.data ?: mutableListOf())
                    val progressState = coerceToCapacity(
                        state = initProgressState.invoke(page, dataOfPage)
                    ) as ProgressPage
                    cache.setState(
                        state = progressState,
                        silently = true
                    )
                }
            } finally {
                navigationMutex.unlock()
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
                navigationMutex.lock()
                try {
                    results.forEach { finalPageState: PageState<T> ->
                        cache.setState(
                            state = finalPageState,
                            silently = true
                        )
                    }
                } finally {
                    navigationMutex.unlock()
                }

                // Phase 4: persist successful results to L2
                core.persistentCache?.let { pc ->
                    val successStates = results.filterIsInstance<SuccessPage<T>>()
                    if (successStates.isNotEmpty()) {
                        pc.saveAll(successStates)
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
            logger.debug(LogComponent.LIFECYCLE) { "refresh: pages=$pages completed" }
        } catch (e: CancellationException) {
            if (savedStates.isNotEmpty()) {
                withContext(NonCancellable) {
                    // TODO Maybe lock here is unnecessary
                    navigationMutex.lock()
                    try {
                        savedStates.forEach { (page, state) ->
                            if (state != null) {
                                cache.setState(state, silently = true)
                            } else {
                                cache.removeFromCache(page)
                            }
                        }
                        core.snapshot()
                    } finally {
                        navigationMutex.unlock()
                    }
                }
            }
            throw e
        }
    }

    /**
     * Loads a page from the [load] or returns the cached state if it is already a filled success page.
     *
     * This is the low-level loading primitive used internally by [goNextPage], [goPreviousPage],
     * [jump], [restart], and [refresh]. It does **not** update context pages or emit snapshots —
     * callers are responsible for that.
     *
     * @param page The page number to load.
     * @param forceLoading If `true`, always reloads from [load] even if a valid cached state exists.
     * @param loading A callback invoked just before loading starts.
     * @param load The data source suspend function. Defaults to [Paginator.load].
     * @param initEmptyState Factory for empty page instances.
     * @param initSuccessState Factory for success page instances.
     * @param initErrorState Factory for error page instances.
     * @return The resulting [PageState] after loading or from cache.
     */
    suspend inline fun loadOrGetPageState(
        page: Int,
        forceLoading: Boolean = false,
        loading: ((page: Int, pageState: PageState<T>?) -> Unit) = { _, _ -> },
        noinline load: suspend Paginator<T>.(page: Int) -> LoadResult<T> = this.load,
        noinline initEmptyState: InitializerEmptyPage<T> = core.initializerEmptyPage,
        noinline initSuccessState: InitializerSuccessPage<T> = core.initializerSuccessPage,
        noinline initErrorState: InitializerErrorPage<T> = core.initializerErrorPage
    ): PageState<T> {
        logger.debug(LogComponent.NAVIGATION) {
            "loadOrGetPageState: page=$page forceLoading=$forceLoading"
        }
        val cachedState: PageState<T>? = cache.getStateOf(page)
        if (!forceLoading && core.isFilledSuccessState(cachedState)) {
            logger.debug(LogComponent.NAVIGATION) {
                "loadOrGetPageState: page=$page cachedState(isFilledSuccessState)=$cachedState"
            }
            return cachedState
        }
        loading.invoke(page, cachedState)
        return try {
            val loadResult: LoadResult<T> = load.invoke(this, page)
            val data: MutableList<T> = loadResult.data.let {
                if (core.isCapacityUnlimited) it
                else it.take(core.capacity)
            }.toMutableList()
            if (data.isEmpty()) {
                logger.debug(LogComponent.NAVIGATION) {
                    "loadOrGetPageState: page=$page data.isEmpty()"
                }
                coerceToCapacity(
                    state = initEmptyState.invoke(page, data, loadResult.metadata)
                )
            } else {
                logger.debug(LogComponent.NAVIGATION) {
                    "loadOrGetPageState: page=$page data.isNotEmpty()"
                }
                coerceToCapacity(
                    state = initSuccessState.invoke(page, data, loadResult.metadata)
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn(LogComponent.NAVIGATION) {
                "loadOrGetPageState: page=$page exception=$exception"
            }
            val data: List<T> = coerceToCapacity(
                data = cachedState?.data ?: mutableListOf()
            )
            coerceToCapacity(
                state = initErrorState.invoke(exception, page, data)
            )
        }
    }

    fun coerceToCapacity(data: List<T>): List<T> {
        val capacity = core.capacity
        if (core.isCapacityUnlimited || data.size <= capacity) {
            return data
        }
        return if (data.size / 2 >= capacity) {
            ArrayList<T>(capacity).apply {
                for (i in 0 until capacity) {
                    add(data[i])
                }
            }
        } else {
            ArrayList(data).apply {
                subList(capacity, size).clear()
            }
        }
    }

    fun coerceToCapacity(state: PageState<T>): PageState<T> {
        val newData = coerceToCapacity(state.data)
        return if (newData === state.data) {
            state
        } else {
            state.copy(data = newData)
        }
    }

    /**
     * Saves [state] to the [persistent cache][PagingCore.persistentCache] if it is
     * a [SuccessPage] (including [PageState.EmptyPage]).
     *
     * Error and progress pages are **not** persisted because they represent
     * transient states that should be re-fetched from the source.
     */
    private suspend fun persistSuccessState(state: PageState<T>) {
        if (state is SuccessPage) {
            core.persistentCache?.save(state)
        }
    }

    /**
     * Launches a fire-and-forget refresh for all dirty pages within the current context window.
     *
     * Finds dirty pages that fall within startContextPage..endContextPage,
     * removes them from the dirty set, and starts a parallel [refresh] for those pages.
     */
    protected fun CoroutineScope.refreshDirtyPagesInContext() {
        if (!cache.isStarted) return
        val dirtyInContext: List<Int> =
            core.drainDirtyPagesInRange(cache.startContextPage..cache.endContextPage)
                ?: return
        logger.debug(LogComponent.LIFECYCLE) { "refreshDirtyPagesInContext: pages=$dirtyInContext" }
        launch {
            refresh(pages = dirtyInContext, loadingSilently = true)
        }
    }

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
        logger.info(LogComponent.LIFECYCLE) { "release" }
        core.release(capacity, silently)
        bookmarks.clear()
        bookmarks.add(BookmarkInt(page = 1))
        bookmarkIndex = 0
        finalPage = Int.MAX_VALUE
        lockJump = false
        lockGoNextPage = false
        lockGoPreviousPage = false
        lockRestart = false
        lockRefresh = false
    }

    /**
     * Creates a serializable snapshot of this Paginator's full state,
     * including the [PagingCore] cache and Paginator-level fields
     * ([finalPage], [bookmarks], [bookmarkIndex], lock flags).
     *
     * Thread-safe: acquires [navigationMutex] to ensure a consistent snapshot.
     *
     * @param contextOnly If `true`, only pages within the context window are included.
     * @return A [PaginatorSnapshot] containing all the state needed for restoration.
     */
    suspend fun saveState(
        contextOnly: Boolean = false,
    ): PaginatorSnapshot<T> {
        navigationMutex.lock()
        try {
            return PaginatorSnapshot(
                coreSnapshot = core.saveState(contextOnly),
                finalPage = finalPage,
                bookmarkPages = bookmarks.map { it.page },
                bookmarkIndex = bookmarkIndex,
                recyclingBookmark = recyclingBookmark,
                lockJump = lockJump,
                lockGoNextPage = lockGoNextPage,
                lockGoPreviousPage = lockGoPreviousPage,
                lockRestart = lockRestart,
                lockRefresh = lockRefresh,
            )
        } finally {
            navigationMutex.unlock()
        }
    }

    /**
     * Restores this Paginator's full state from a previously saved [snapshot].
     *
     * Restores both the [PagingCore] cache and Paginator-level fields
     * ([finalPage], [bookmarks], [bookmarkIndex], lock flags).
     *
     * Thread-safe: acquires [navigationMutex] to prevent concurrent mutations.
     *
     * @param snapshot The snapshot to restore from.
     * @param silently If `true`, no snapshot is emitted after restoration.
     * @throws IllegalArgumentException If the snapshot contains invalid data.
     */
    suspend fun restoreState(
        snapshot: PaginatorSnapshot<T>,
        silently: Boolean = false,
    ) {
        navigationMutex.lock()
        try {
            core.restoreState(snapshot.coreSnapshot, silently)

            finalPage = snapshot.finalPage

            bookmarks.clear()
            if (snapshot.bookmarkPages.isEmpty()) {
                bookmarks.add(BookmarkInt(page = 1))
            } else {
                snapshot.bookmarkPages.forEach { page ->
                    bookmarks.add(BookmarkInt(page = page))
                }
            }
            bookmarkIndex = snapshot.bookmarkIndex.coerceIn(0, bookmarks.size)

            recyclingBookmark = snapshot.recyclingBookmark
            lockJump = snapshot.lockJump
            lockGoNextPage = snapshot.lockGoNextPage
            lockGoPreviousPage = snapshot.lockGoPreviousPage
            lockRestart = snapshot.lockRestart
            lockRefresh = snapshot.lockRefresh
        } finally {
            navigationMutex.unlock()
        }
    }

    private class TransactionSavepoint<T>(
        val states: List<PageState<T>>,
        val startContextPage: Int,
        val endContextPage: Int,
        val capacity: Int,
        val dirtyPages: Set<Int>,
        val finalPage: Int,
        val bookmarks: List<Bookmark>,
        val bookmarkIndex: Int,
        val recyclingBookmark: Boolean,
        val lockJump: Boolean,
        val lockGoNextPage: Boolean,
        val lockGoPreviousPage: Boolean,
        val lockRestart: Boolean,
        val lockRefresh: Boolean,
    )

    private fun createSavepoint() = TransactionSavepoint(
        states = core.states.map { it.copy(data = it.data.toMutableList()) },
        startContextPage = cache.startContextPage,
        endContextPage = cache.endContextPage,
        capacity = core.capacity,
        dirtyPages = core.dirtyPages,
        finalPage = finalPage,
        bookmarks = bookmarks.toList(),
        bookmarkIndex = bookmarkIndex,
        recyclingBookmark = recyclingBookmark,
        lockJump = lockJump,
        lockGoNextPage = lockGoNextPage,
        lockGoPreviousPage = lockGoPreviousPage,
        lockRestart = lockRestart,
        lockRefresh = lockRefresh,
    )

    /**
     * Executes [block] as an atomic operation: if [block] throws any exception
     * (including [CancellationException]), the paginator's entire state is rolled back
     * to the point before the block was entered.
     *
     * On success, the block's return value is returned and all state changes are kept.
     *
     * **Snapshot fidelity:** unlike [saveState]/[restoreState] (which are designed for
     * serialization and convert [PageState.ErrorPage]/[PageState.ProgressPage] to success entries),
     * this method preserves exact [PageState] types through an in-memory deep copy.
     *
     * **Concurrency:** the [navigationMutex] is **not** held during [block] execution,
     * so navigation operations inside the block work without deadlock. This matches the
     * concurrency model of CRUD operations.
     *
     * **Nesting:** nested `transaction` calls work naturally — each creates its own
     * save point. An inner failure rolls back to the inner save point; an outer failure
     * rolls back to the outer save point.
     *
     * @param R The return type of the block.
     * @param block The transactional block to execute with this paginator as receiver.
     * @return The result of [block] if it completes successfully.
     * @throws Throwable Re-throws whatever [block] threw, after rolling back state.
     */
    open suspend fun <R> transaction(block: suspend Paginator<T>.() -> R): R {
        val savepoint = createSavepoint()
        try {
            return block()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { rollback(savepoint) }
            throw e
        } catch (e: Throwable) {
            rollback(savepoint)
            throw e
        }
    }

    private fun rollback(savepoint: TransactionSavepoint<T>) {
        cache.clear()
        core.clearAllDirty()

        savepoint.states.forEach { cache.setState(it, silently = true) }
        savepoint.dirtyPages.forEach { core.markDirty(it) }

        core.capacity = savepoint.capacity
        core.startContextPage = savepoint.startContextPage
        core.endContextPage = savepoint.endContextPage

        finalPage = savepoint.finalPage
        bookmarks.clear()
        bookmarks.addAll(savepoint.bookmarks)
        bookmarkIndex = savepoint.bookmarkIndex
        recyclingBookmark = savepoint.recyclingBookmark
        lockJump = savepoint.lockJump
        lockGoNextPage = savepoint.lockGoNextPage
        lockGoPreviousPage = savepoint.lockGoPreviousPage
        lockRestart = savepoint.lockRestart
        lockRefresh = savepoint.lockRefresh

        core.snapshot()
        if (core.enableCacheFlow) {
            core.repeatCacheFlow()
        }
    }

    /**
     * Compares paginators by the number of pages currently held in their cache.
     */
    override operator fun compareTo(other: Paginator<*>): Int = cache.size - other.cache.size

    operator fun iterator(): Iterator<PageState<T>> {
        return core.iterator()
    }

    operator fun contains(page: Int): Boolean = cache.getStateOf(page) != null

    operator fun contains(pageState: PageState<T>): Boolean =
        cache.getStateOf(pageState.page) != null

    operator fun get(page: Int): PageState<T>? = cache.getStateOf(page)

    operator fun get(page: Int, index: Int): T? = cache.getElement(page, index)

    override fun toString(): String = "Paginator(cache=$cache, bookmarks=$bookmarks)"

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = super.hashCode()

}
