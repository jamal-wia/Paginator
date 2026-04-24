package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.CursorPagingCore.Companion.DEFAULT_CAPACITY
import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cache.CursorPagingCache
import com.jamal_aliev.paginator.exception.CursorLoadGuardedException
import com.jamal_aliev.paginator.exception.EndOfCursorFeedException
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
import com.jamal_aliev.paginator.load.CursorLoadResult
import com.jamal_aliev.paginator.load.Metadata
import com.jamal_aliev.paginator.logger.LogComponent
import com.jamal_aliev.paginator.logger.PaginatorLogger
import com.jamal_aliev.paginator.logger.debug
import com.jamal_aliev.paginator.logger.info
import com.jamal_aliev.paginator.logger.warn
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PageState.ProgressPage
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import com.jamal_aliev.paginator.serialization.CursorPaginatorSnapshot
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * A read-only, reactive cursor-based pagination manager.
 *
 * `CursorPaginator` is a LinkedList-style counterpart to the RandomAccess-style
 * [Paginator]: every page knows only its immediate neighbours through a
 * [CursorBookmark], and the paginator navigates by following `prev`/`next`
 * links instead of numeric page indices.
 *
 * ## Loading contract
 *
 * The [load] lambda receives an optional [CursorBookmark]:
 * - `null` on the very first call (from [restart] without an [initialCursor]),
 *   meaning "return the first page and its full bookmark".
 * - a bookmark "hint" in every other case. The hint carries the neighbour
 *   `self` key — `prev` when [goPreviousPage] is advancing the head edge,
 *   `next` when [goNextPage] is advancing the tail edge, or the bookmark
 *   passed to [jump].
 *
 * The [load] lambda is expected to return a [CursorLoadResult] whose
 * [CursorLoadResult.bookmark] describes the **real** `prev`/`self`/`next` links
 * of the loaded page. `next == null` is the canonical signal that the tail of
 * the feed has been reached; `prev == null` is the canonical head signal.
 *
 * ## Element-level mutation
 *
 * For CRUD operations (`addElement`, `removeElement`, `setElement`, …) use
 * [MutableCursorPaginator] instead.
 *
 * @param T The type of elements contained in each page.
 * @param core The cursor-based [CursorPagingCore] that owns cache, snapshot,
 *   initializers, etc.
 * @param load A suspending lambda that loads a page given an optional cursor hint.
 */
open class CursorPaginator<T>(
    val core: CursorPagingCore<T> = CursorPagingCore(),
    var load: suspend CursorPaginator<T>.(cursor: CursorBookmark?) -> CursorLoadResult<T>,
) {

    val cache: CursorPagingCache<T> get() = core.cache

    /** Mutex serialising all mutations to cache across concurrent coroutines. */
    protected val navigationMutex = Mutex()

    /** Logger for paginator operations. Mirrors [Paginator.logger]. */
    var logger: PaginatorLogger? = null
        set(value) {
            field = value
            core.logger = value
        }

    /**
     * Optional "anchor" cursor used by [restart] to bootstrap the feed.
     *
     * If non-null, [restart] will [jump] to this cursor. If null, [restart]
     * calls [load] with a `null` cursor hint — the server is expected to
     * return the first page of the feed.
     */
    var initialCursor: CursorBookmark? = null

    /**
     * Predefined bookmark targets for quick navigation via [jumpForward] /
     * [jumpBack]. Defaults to an empty list (unlike [Paginator] which pre-seeds
     * page 1) because cursor keys are opaque and server-provided.
     */
    val bookmarks: MutableList<CursorBookmark> = mutableListOf()

    var recyclingBookmark = false
    protected var bookmarkIndex: Int = 0

    var lockJump = false
    var lockGoNextPage: Boolean = false
    var lockGoPreviousPage: Boolean = false
    var lockRestart: Boolean = false
    var lockRefresh: Boolean = false

    /**
     * Moves forward to the next bookmark whose `self` key is **outside** the
     * current visible snapshot, and [jump]s to it.
     */
    suspend fun jumpForward(
        recycling: Boolean = recyclingBookmark,
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        loadGuard: (cursor: CursorBookmark, state: PageState<T>?) -> Boolean = { _, _ -> true },
        lockJump: Boolean = this.lockJump,
        enableCacheFlow: Boolean = core.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = core.initializerProgressPage,
        initSuccessState: InitializerSuccessPage<T> = core.initializerSuccessPage,
        initEmptyState: InitializerEmptyPage<T> = core.initializerEmptyPage,
        initErrorState: InitializerErrorPage<T> = core.initializerErrorPage,
    ): Pair<CursorBookmark, PageState<T>>? {
        if (lockJump) throw JumpWasLockedException()
        logger.debug(LogComponent.NAVIGATION) { "jumpForward: recycling=$recycling" }

        val visibleSelves: Set<Any> = core.snapshotSelves().toHashSet()
        var lastSkipped: CursorBookmark? = null
        var candidate: CursorBookmark? = null

        val bookmarksSize = bookmarks.size
        if (bookmarksSize > 0) {
            bookmarkIndex = bookmarkIndex.coerceIn(0, bookmarksSize)
            val limit = if (recycling) bookmarksSize else bookmarksSize - bookmarkIndex
            for (i in 0 until limit) {
                val index = (bookmarkIndex + i) % bookmarksSize
                val current = bookmarks[index]
                if (current.self in visibleSelves) {
                    lastSkipped = current
                    continue
                }
                candidate = current
                bookmarkIndex = index + 1
                break
            }
            if (candidate == null) {
                bookmarkIndex = if (recycling) bookmarkIndex else bookmarksSize
                candidate = lastSkipped
            }
        }

        if (candidate != null) {
            val savedIndex = bookmarkIndex
            val result = jump(
                bookmark = candidate,
                silentlyLoading = silentlyLoading,
                silentlyResult = silentlyResult,
                loadGuard = loadGuard,
                enableCacheFlow = enableCacheFlow,
                initProgressState = initProgressState,
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState,
            )
            bookmarkIndex = savedIndex
            return result
        }

        logger.debug(LogComponent.NAVIGATION) { "jumpForward: no bookmark available" }
        return null
    }

    /**
     * Mirror of [jumpForward] but scanning backward through [bookmarks].
     */
    suspend fun jumpBack(
        recycling: Boolean = recyclingBookmark,
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        loadGuard: (cursor: CursorBookmark, state: PageState<T>?) -> Boolean = { _, _ -> true },
        lockJump: Boolean = this.lockJump,
        enableCacheFlow: Boolean = core.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = core.initializerProgressPage,
        initSuccessState: InitializerSuccessPage<T> = core.initializerSuccessPage,
        initEmptyState: InitializerEmptyPage<T> = core.initializerEmptyPage,
        initErrorState: InitializerErrorPage<T> = core.initializerErrorPage,
    ): Pair<CursorBookmark, PageState<T>>? {
        if (lockJump) throw JumpWasLockedException()
        logger.debug(LogComponent.NAVIGATION) { "jumpBack: recycling=$recycling" }

        val visibleSelves: Set<Any> = core.snapshotSelves().toHashSet()
        var lastSkipped: CursorBookmark? = null
        var candidate: CursorBookmark? = null

        val bookmarksSize = bookmarks.size
        if (bookmarksSize > 0) {
            bookmarkIndex = bookmarkIndex.coerceIn(0, bookmarksSize)
            val limit = if (recycling) bookmarksSize else bookmarkIndex
            for (i in 1..limit) {
                val index = (bookmarkIndex - i + bookmarksSize) % bookmarksSize
                val current = bookmarks[index]
                if (current.self in visibleSelves) {
                    lastSkipped = current
                    continue
                }
                candidate = current
                bookmarkIndex = index
                break
            }
            if (candidate == null) {
                bookmarkIndex = if (recycling) bookmarkIndex else 0
                candidate = lastSkipped
            }
        }

        if (candidate != null) {
            val savedIndex = bookmarkIndex
            val result = jump(
                bookmark = candidate,
                silentlyLoading = silentlyLoading,
                silentlyResult = silentlyResult,
                loadGuard = loadGuard,
                enableCacheFlow = enableCacheFlow,
                initProgressState = initProgressState,
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState,
            )
            bookmarkIndex = savedIndex
            return result
        }

        logger.debug(LogComponent.NAVIGATION) { "jumpBack: no bookmark available" }
        return null
    }

    /**
     * Jumps directly to the page identified by [bookmark].
     *
     * If the target page is already cached as a filled success page, it is
     * returned immediately without reloading. Otherwise the context window is
     * reset around the target and the page is loaded from [load].
     *
     * @param bookmark The cursor bookmark to navigate to. May be a freshly
     *   constructed stub (`CursorBookmark(null, selfKey, null)`) when jumping
     *   to a known `self` without knowing its neighbours in advance — the
     *   server response will fill in the real links.
     */
    suspend fun jump(
        bookmark: CursorBookmark,
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        loadGuard: (cursor: CursorBookmark, state: PageState<T>?) -> Boolean = { _, _ -> true },
        lockJump: Boolean = this.lockJump,
        enableCacheFlow: Boolean = core.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = core.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = core.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = core.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = core.initializerErrorPage,
    ): Pair<CursorBookmark, PageState<T>> = coroutineScope {
        if (lockJump) throw JumpWasLockedException()
        logger.debug(LogComponent.NAVIGATION) { "jump: self=${bookmark.self}" }

        var savedStart: CursorBookmark? = core.startContextCursor
        var savedEnd: CursorBookmark? = core.endContextCursor
        var savedState: PageState<T>? = null
        var savedCursor: CursorBookmark? = null
        var shouldCleanup = false

        navigationMutex.lock()
        try {
            // Check L1 first, then L2.
            var cachedState: PageState<T>? = cache.getStateOf(bookmark.self)
            var effectiveCursor: CursorBookmark = cache.getCursorOf(bookmark.self) ?: bookmark
            if (cachedState == null) {
                val persisted = core.loadFromPersistentCache(bookmark.self)
                if (persisted != null) {
                    cachedState = persisted.second
                    effectiveCursor = persisted.first
                }
            }

            if (core.isFilledSuccessState(cachedState)) {
                core.expandStartContextCursor(cachedState, effectiveCursor)
                core.expandEndContextCursor(cachedState, effectiveCursor)
                if (!silentlyResult) core.snapshot()
                logger.debug(LogComponent.NAVIGATION) { "jump: self=${bookmark.self} cache hit" }
                syncBookmarkIndex(effectiveCursor)
                refreshDirtyCursorsInContext()
                return@coroutineScope effectiveCursor to cachedState
            }

            if (!loadGuard.invoke(bookmark, cachedState)) {
                throw CursorLoadGuardedException(attemptedCursor = bookmark)
            }

            savedStart = core.startContextCursor
            savedEnd = core.endContextCursor
            savedState = cachedState
            savedCursor = effectiveCursor
            shouldCleanup = true

            core.startContextCursor = effectiveCursor
            core.endContextCursor = effectiveCursor

            val (resultCursor, resultState) = loadOrGetPageState(
                hint = bookmark,
                cachedCursor = effectiveCursor,
                forceLoading = true,
                loading = { cursor, pageState ->
                    val data: List<T> = core.coerceToCapacity(pageState?.data ?: mutableListOf())
                    val progressState: ProgressPage<T> = core.coerceToCapacity(
                        state = initProgressState.invoke(syntheticPage(), data, pageState?.metadata)
                    ) as ProgressPage
                    cache.setState(cursor, progressState, silently = true)
                    core.expandStartContextCursor(progressState, cursor)
                    core.expandEndContextCursor(progressState, cursor)
                    if (enableCacheFlow) core.repeatCacheFlow()
                    if (!silentlyLoading) core.snapshot()
                },
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState,
            )
            shouldCleanup = false
            cache.setState(resultCursor, resultState, silently = true)
            core.expandStartContextCursor(resultState, resultCursor)
            core.expandEndContextCursor(resultState, resultCursor)

            if (enableCacheFlow) core.repeatCacheFlow()
            if (!silentlyResult) core.snapshot()

            logger.debug(LogComponent.NAVIGATION) {
                "jump: self=${resultCursor.self} result=${resultState::class.simpleName}"
            }
            persistSuccessState(resultCursor, resultState)
            syncBookmarkIndex(resultCursor)
            refreshDirtyCursorsInContext()
            return@coroutineScope resultCursor to resultState
        } catch (e: CancellationException) {
            if (shouldCleanup) {
                withContext(NonCancellable) {
                    core.startContextCursor = savedStart
                    core.endContextCursor = savedEnd
                    val originalState = savedState
                    val originalCursor = savedCursor
                    if (originalState != null && originalCursor != null) {
                        cache.setState(originalCursor, originalState, silently = true)
                    } else {
                        cache.removeFromCache(bookmark.self)
                    }
                    core.snapshot()
                }
            }
            throw e
        } finally {
            navigationMutex.unlock()
        }
    }

    /**
     * Loads the next page after the current [core].endContextCursor by following
     * its `next` link. If the paginator has not been started yet, this falls back
     * to [restart].
     *
     * @throws EndOfCursorFeedException If the tail of the feed has been reached
     *   (`endContextCursor.next == null`).
     */
    suspend fun goNextPage(
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        loadGuard: (cursor: CursorBookmark, state: PageState<T>?) -> Boolean = { _, _ -> true },
        lockGoNextPage: Boolean = this.lockGoNextPage,
        enableCacheFlow: Boolean = core.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = core.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = core.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = core.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = core.initializerErrorPage,
    ): PageState<T> = coroutineScope {
        if (lockGoNextPage) throw GoNextPageWasLockedException()
        logger.debug(LogComponent.NAVIGATION) { "goNextPage" }
        if (!cache.isStarted) {
            // Implicit bootstrap: the goNext guard signature takes a non-null cursor,
            // which isn't applicable to the initial restart. Skip the guard here —
            // the caller can always run restart() explicitly with a nullable-cursor guard.
            return@coroutineScope restartInternal(
                silentlyLoading = silentlyLoading,
                silentlyResult = silentlyResult,
                loadGuard = { _, _ -> true },
                enableCacheFlow = enableCacheFlow,
                initProgressState = initProgressState,
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState,
            )
        }

        var savedCursor: CursorBookmark? = null
        var savedState: PageState<T>? = null
        var shouldCleanup = false

        navigationMutex.lock()
        try {
            var pivotCursor: CursorBookmark = cache.endContextCursor
                ?: throw IllegalStateException("endContextCursor is null but cache.isStarted is true")
            var pivotState: PageState<T>? = cache.getStateOf(pivotCursor.self)
            val pivotIsFilled = core.isFilledSuccessState(pivotState)
            if (pivotIsFilled) {
                val nextCursor = cache.walkForward(pivotCursor)
                if (nextCursor != null) {
                    val nextState = cache.getStateOf(nextCursor.self)
                    core.expandEndContextCursor(nextState, nextCursor)?.also { expanded ->
                        pivotState = expanded
                        pivotCursor = cache.endContextCursor ?: pivotCursor
                    }
                }
            }

            // Determine next target
            val nextTargetSelf: Any? = if (pivotIsFilled) pivotCursor.next else null
            if (pivotIsFilled && nextTargetSelf == null) {
                throw EndOfCursorFeedException(
                    attemptedCursorKey = pivotCursor.self,
                    direction = EndOfCursorFeedException.Direction.FORWARD,
                )
            }

            val targetCursor: CursorBookmark =
                if (!pivotIsFilled) pivotCursor
                else cache.getCursorOf(nextTargetSelf!!)
                    ?: CursorBookmark(prev = pivotCursor.self, self = nextTargetSelf, next = null)

            var targetState: PageState<T>? = cache.getStateOf(targetCursor.self)
            if (targetState == null) {
                val persisted = core.loadFromPersistentCache(targetCursor.self)
                if (persisted != null) targetState = persisted.second
            }

            if (targetState.isProgressState()) return@coroutineScope targetState

            if (core.isFilledSuccessState(targetState)) {
                val resolvedCursor = cache.getCursorOf(targetCursor.self) ?: targetCursor
                core.endContextCursor = resolvedCursor
                val maybeNext = cache.walkForward(resolvedCursor)
                if (maybeNext != null) {
                    core.expandEndContextCursor(cache.getStateOf(maybeNext.self), maybeNext)
                }
                if (enableCacheFlow) core.repeatCacheFlow()
                if (!silentlyResult) core.snapshot()
                refreshDirtyCursorsInContext()
                return@coroutineScope targetState
            }

            if (!loadGuard.invoke(targetCursor, targetState)) {
                throw CursorLoadGuardedException(attemptedCursor = targetCursor)
            }

            savedCursor = targetCursor
            savedState = targetState
            shouldCleanup = true

            val (resultCursor, resultState) = loadOrGetPageState(
                hint = targetCursor,
                cachedCursor = targetCursor,
                forceLoading = true,
                loading = { cursor, cached ->
                    val data: List<T> = core.coerceToCapacity(cached?.data ?: mutableListOf())
                    val progressState: ProgressPage<T> = core.coerceToCapacity(
                        state = initProgressState.invoke(syntheticPage(), data, cached?.metadata)
                    ) as ProgressPage
                    cache.setState(cursor, progressState, silently = true)
                    if (enableCacheFlow) core.repeatCacheFlow()
                    if (!silentlyLoading) core.snapshot()
                },
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState,
            )
            shouldCleanup = false
            cache.setState(resultCursor, resultState, silently = true)
            if (core.endContextCursor?.self == pivotCursor.self && core.isFilledSuccessState(
                    resultState
                )
            ) {
                core.endContextCursor = resultCursor
                val maybeNext = cache.walkForward(resultCursor)
                if (maybeNext != null) {
                    core.expandEndContextCursor(cache.getStateOf(maybeNext.self), maybeNext)
                }
            }
            if (enableCacheFlow) core.repeatCacheFlow()
            if (!silentlyResult) core.snapshot()

            logger.debug(LogComponent.NAVIGATION) {
                "goNextPage: self=${resultCursor.self} result=${resultState::class.simpleName}"
            }
            persistSuccessState(resultCursor, resultState)
            refreshDirtyCursorsInContext()
            return@coroutineScope resultState
        } catch (e: CancellationException) {
            if (shouldCleanup) {
                withContext(NonCancellable) {
                    val originalState = savedState
                    val originalCursor = savedCursor
                    if (originalState != null && originalCursor != null) {
                        cache.setState(originalCursor, originalState, silently = true)
                    } else if (originalCursor != null) {
                        cache.removeFromCache(originalCursor.self)
                    }
                    core.snapshot()
                }
            }
            throw e
        } finally {
            navigationMutex.unlock()
        }
    }

    /**
     * Loads the previous page before the current [core].startContextCursor by
     * following its `prev` link.
     *
     * @throws EndOfCursorFeedException If the head of the feed has been reached
     *   (`startContextCursor.prev == null`).
     */
    suspend fun goPreviousPage(
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        loadGuard: (cursor: CursorBookmark, state: PageState<T>?) -> Boolean = { _, _ -> true },
        enableCacheFlow: Boolean = core.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = core.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = core.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = core.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = core.initializerErrorPage,
    ): PageState<T> = coroutineScope {
        if (lockGoPreviousPage) throw GoPreviousPageWasLockedException()
        logger.debug(LogComponent.NAVIGATION) { "goPreviousPage" }
        check(cache.isStarted) {
            "Paginator was not started. First of all paginator must be jumped (started) " +
                    "via jump() or restart()."
        }

        var savedCursor: CursorBookmark? = null
        var savedState: PageState<T>? = null
        var shouldCleanup = false

        navigationMutex.lock()
        try {
            var pivotCursor: CursorBookmark = cache.startContextCursor
                ?: throw IllegalStateException("startContextCursor is null but cache.isStarted is true")
            var pivotState: PageState<T>? = cache.getStateOf(pivotCursor.self)
            val pivotIsFilled = core.isFilledSuccessState(pivotState)
            if (pivotIsFilled) {
                val prevCursor = cache.walkBackward(pivotCursor)
                if (prevCursor != null) {
                    core.expandStartContextCursor(cache.getStateOf(prevCursor.self), prevCursor)
                        ?.also { expanded ->
                            pivotState = expanded
                            pivotCursor = cache.startContextCursor ?: pivotCursor
                        }
                }
            }

            val prevTargetSelf: Any? = if (pivotIsFilled) pivotCursor.prev else null
            if (pivotIsFilled && prevTargetSelf == null) {
                throw EndOfCursorFeedException(
                    attemptedCursorKey = pivotCursor.self,
                    direction = EndOfCursorFeedException.Direction.BACKWARD,
                )
            }

            val targetCursor: CursorBookmark =
                if (!pivotIsFilled) pivotCursor
                else cache.getCursorOf(prevTargetSelf!!)
                    ?: CursorBookmark(prev = null, self = prevTargetSelf, next = pivotCursor.self)

            var targetState: PageState<T>? = cache.getStateOf(targetCursor.self)
            if (targetState == null) {
                val persisted = core.loadFromPersistentCache(targetCursor.self)
                if (persisted != null) targetState = persisted.second
            }

            if (targetState.isProgressState()) return@coroutineScope targetState

            if (core.isFilledSuccessState(targetState)) {
                val resolvedCursor = cache.getCursorOf(targetCursor.self) ?: targetCursor
                core.startContextCursor = resolvedCursor
                val maybePrev = cache.walkBackward(resolvedCursor)
                if (maybePrev != null) {
                    core.expandStartContextCursor(cache.getStateOf(maybePrev.self), maybePrev)
                }
                if (enableCacheFlow) core.repeatCacheFlow()
                if (!silentlyResult) core.snapshot()
                refreshDirtyCursorsInContext()
                return@coroutineScope targetState
            }

            if (!loadGuard.invoke(targetCursor, targetState)) {
                throw CursorLoadGuardedException(attemptedCursor = targetCursor)
            }

            savedCursor = targetCursor
            savedState = targetState
            shouldCleanup = true

            val (resultCursor, resultState) = loadOrGetPageState(
                hint = targetCursor,
                cachedCursor = targetCursor,
                forceLoading = true,
                loading = { cursor, cached ->
                    val data: List<T> = core.coerceToCapacity(cached?.data ?: mutableListOf())
                    val progressState: ProgressPage<T> = core.coerceToCapacity(
                        state = initProgressState.invoke(syntheticPage(), data, cached?.metadata)
                    ) as ProgressPage
                    cache.setState(cursor, progressState, silently = true)
                    if (enableCacheFlow) core.repeatCacheFlow()
                    if (!silentlyLoading) core.snapshot()
                },
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState,
            )
            shouldCleanup = false
            cache.setState(resultCursor, resultState, silently = true)
            if (core.startContextCursor?.self == pivotCursor.self && core.isFilledSuccessState(
                    resultState
                )
            ) {
                core.startContextCursor = resultCursor
                val maybePrev = cache.walkBackward(resultCursor)
                if (maybePrev != null) {
                    core.expandStartContextCursor(cache.getStateOf(maybePrev.self), maybePrev)
                }
            }
            if (enableCacheFlow) core.repeatCacheFlow()
            if (!silentlyResult) core.snapshot()

            logger.debug(LogComponent.NAVIGATION) {
                "goPreviousPage: self=${resultCursor.self} result=${resultState::class.simpleName}"
            }
            persistSuccessState(resultCursor, resultState)
            refreshDirtyCursorsInContext()
            return@coroutineScope resultState
        } catch (e: CancellationException) {
            if (shouldCleanup) {
                withContext(NonCancellable) {
                    val originalState = savedState
                    val originalCursor = savedCursor
                    if (originalState != null && originalCursor != null) {
                        cache.setState(originalCursor, originalState, silently = true)
                    } else if (originalCursor != null) {
                        cache.removeFromCache(originalCursor.self)
                    }
                    core.snapshot()
                }
            }
            throw e
        } finally {
            navigationMutex.unlock()
        }
    }

    /**
     * Resets the paginator to its initial state and reloads the first page.
     *
     * If [initialCursor] is set, [jump]s to it. Otherwise invokes [load] with a
     * `null` cursor hint to request the first page of the feed.
     */
    suspend fun restart(
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        loadGuard: (cursor: CursorBookmark?, state: PageState<T>?) -> Boolean = { _, _ -> true },
        enableCacheFlow: Boolean = core.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = core.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = core.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = core.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = core.initializerErrorPage,
    ): Unit = coroutineScope {
        if (lockRestart) throw RestartWasLockedException()
        logger.info(LogComponent.LIFECYCLE) { "restart" }
        restartInternal(
            silentlyLoading = silentlyLoading,
            silentlyResult = silentlyResult,
            loadGuard = { cursor, state -> loadGuard.invoke(cursor, state) },
            enableCacheFlow = enableCacheFlow,
            initProgressState = initProgressState,
            initEmptyState = initEmptyState,
            initSuccessState = initSuccessState,
            initErrorState = initErrorState,
        )
    }

    /**
     * Shared implementation behind [restart] and the implicit-restart branch of
     * [goNextPage] when the paginator has not been started yet.
     */
    @PublishedApi
    internal suspend fun restartInternal(
        silentlyLoading: Boolean,
        silentlyResult: Boolean,
        loadGuard: (cursor: CursorBookmark?, state: PageState<T>?) -> Boolean,
        enableCacheFlow: Boolean,
        initProgressState: InitializerProgressPage<T>,
        initEmptyState: InitializerEmptyPage<T>,
        initSuccessState: InitializerSuccessPage<T>,
        initErrorState: InitializerErrorPage<T>,
    ): PageState<T> = coroutineScope {
        val anchor: CursorBookmark? = initialCursor

        var savedStart: CursorBookmark? = core.startContextCursor
        var savedEnd: CursorBookmark? = core.endContextCursor
        var shouldCleanup = false

        navigationMutex.lock()
        try {
            savedStart = core.startContextCursor
            savedEnd = core.endContextCursor

            cache.clear()
            core.clearAllDirty()

            if (!loadGuard.invoke(anchor, null)) {
                if (anchor != null) throw CursorLoadGuardedException(attemptedCursor = anchor)
                else throw CursorLoadGuardedException(
                    attemptedCursor = CursorBookmark(prev = null, self = "<restart>", next = null)
                )
            }

            shouldCleanup = true

            // Emit a transient progress state before hitting the network.
            val progressCursor =
                anchor ?: CursorBookmark(prev = null, self = PROGRESS_SENTINEL, next = null)
            val progressState: ProgressPage<T> = core.coerceToCapacity(
                state = initProgressState.invoke(syntheticPage(), emptyList(), null)
            ) as ProgressPage
            cache.setState(progressCursor, progressState, silently = true)
            core.startContextCursor = progressCursor
            core.endContextCursor = progressCursor
            if (enableCacheFlow) core.repeatCacheFlow()
            if (!silentlyLoading) core.snapshot()

            val loadResult: CursorLoadResult<T> = try {
                load.invoke(this@CursorPaginator, anchor)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (exception: Exception) {
                logger.warn(LogComponent.NAVIGATION) { "restart: exception=$exception" }
                // Replace the transient progress state with an error state carrying the exception.
                val errorState = core.coerceToCapacity(
                    state = initErrorState.invoke(exception, syntheticPage(), emptyList(), null)
                )
                // Drop the synthetic progress entry so we don't pollute the cache with sentinels.
                if (progressCursor.self == PROGRESS_SENTINEL) {
                    cache.removeFromCache(PROGRESS_SENTINEL)
                    core.startContextCursor = null
                    core.endContextCursor = null
                }
                if (enableCacheFlow) core.repeatCacheFlow()
                if (!silentlyResult) core.snapshot()
                @Suppress("ThrowableNotThrown")
                return@coroutineScope errorState
            }

            val resultCursor: CursorBookmark = loadResult.bookmark
            val data: List<T> = core.coerceToCapacity(loadResult.data)
            val resultState: PageState<T> = if (data.isEmpty()) {
                core.coerceToCapacity(
                    initEmptyState.invoke(
                        syntheticPage(),
                        data,
                        loadResult.metadata
                    )
                )
            } else {
                core.coerceToCapacity(
                    initSuccessState.invoke(
                        syntheticPage(),
                        data.toMutableList(),
                        loadResult.metadata
                    )
                )
            }

            shouldCleanup = false
            // Replace the sentinel progress entry with the real one.
            if (progressCursor.self != resultCursor.self) {
                cache.removeFromCache(progressCursor.self)
            }
            cache.setState(resultCursor, resultState, silently = true)
            core.startContextCursor = resultCursor
            core.endContextCursor = resultCursor
            core.expandStartContextCursor(resultState, resultCursor)
            core.expandEndContextCursor(resultState, resultCursor)

            if (enableCacheFlow) core.repeatCacheFlow()
            if (!silentlyResult) core.snapshot()
            syncBookmarkIndex(resultCursor)
            logger.debug(LogComponent.NAVIGATION) { "restart: result=${resultState::class.simpleName}" }
            persistSuccessState(resultCursor, resultState)
            return@coroutineScope resultState
        } catch (e: CancellationException) {
            if (shouldCleanup) {
                withContext(NonCancellable) {
                    core.startContextCursor = savedStart
                    core.endContextCursor = savedEnd
                    cache.removeFromCache(PROGRESS_SENTINEL)
                    core.snapshot()
                }
            }
            throw e
        } finally {
            navigationMutex.unlock()
        }
    }

    /**
     * Refreshes the specified cursors by reloading them from [load] in parallel.
     */
    suspend fun refresh(
        cursors: List<CursorBookmark>,
        loadingSilently: Boolean = false,
        finalSilently: Boolean = false,
        loadGuard: (cursor: CursorBookmark, state: PageState<T>?) -> Boolean = { _, _ -> true },
        enableCacheFlow: Boolean = core.enableCacheFlow,
        initProgressState: InitializerProgressPage<T> = core.initializerProgressPage,
        initEmptyState: InitializerEmptyPage<T> = core.initializerEmptyPage,
        initSuccessState: InitializerSuccessPage<T> = core.initializerSuccessPage,
        initErrorState: InitializerErrorPage<T> = core.initializerErrorPage,
    ): Unit = coroutineScope {
        if (lockRefresh) throw RefreshWasLockedException()
        logger.debug(LogComponent.LIFECYCLE) { "refresh: cursors=${cursors.map { it.self }}" }

        var savedStates: Map<Any, Pair<CursorBookmark?, PageState<T>?>> = emptyMap()

        try {
            navigationMutex.lock()
            try {
                savedStates = cursors.associate { c ->
                    c.self to (cache.getCursorOf(c.self) to cache.getStateOf(c.self))
                }
                cursors.forEach { candidate ->
                    val cachedState = savedStates[candidate.self]?.second
                    if (!loadGuard.invoke(candidate, cachedState)) {
                        throw CursorLoadGuardedException(attemptedCursor = candidate)
                    }
                }
                cursors.forEach { candidate ->
                    val cachedCursor: CursorBookmark =
                        savedStates[candidate.self]?.first ?: candidate
                    val cachedState: PageState<T>? = savedStates[candidate.self]?.second
                    val data: List<T> = core.coerceToCapacity(cachedState?.data ?: mutableListOf())
                    val progressState: ProgressPage<T> = core.coerceToCapacity(
                        state = initProgressState.invoke(
                            syntheticPage(),
                            data,
                            cachedState?.metadata
                        )
                    ) as ProgressPage
                    cache.setState(cachedCursor, progressState, silently = true)
                }
            } finally {
                navigationMutex.unlock()
            }
            if (enableCacheFlow) core.repeatCacheFlow()
            if (!loadingSilently) core.snapshot()

            val results: List<Pair<CursorBookmark, PageState<T>>> = cursors.map { candidate ->
                async {
                    loadOrGetPageState(
                        hint = candidate,
                        cachedCursor = savedStates[candidate.self]?.first ?: candidate,
                        forceLoading = true,
                        initEmptyState = initEmptyState,
                        initSuccessState = initSuccessState,
                        initErrorState = initErrorState,
                    )
                }
            }.awaitAll()

            navigationMutex.lock()
            try {
                results.forEach { (cursor, state) ->
                    cache.setState(cursor, state, silently = true)
                }
            } finally {
                navigationMutex.unlock()
            }

            core.persistentCache?.let { pc ->
                val successes = results.filter { it.second is SuccessPage }
                if (successes.isNotEmpty()) pc.saveAll(successes)
            }

            core.clearDirty(cursors.map { it.self })

            if (enableCacheFlow) core.repeatCacheFlow()
            if (!finalSilently) core.snapshot()
            logger.debug(LogComponent.LIFECYCLE) { "refresh: done" }
        } catch (e: CancellationException) {
            if (savedStates.isNotEmpty()) {
                withContext(NonCancellable) {
                    navigationMutex.lock()
                    try {
                        savedStates.forEach { (self, saved) ->
                            val (savedCursor, savedState) = saved
                            if (savedCursor != null && savedState != null) {
                                cache.setState(savedCursor, savedState, silently = true)
                            } else {
                                cache.removeFromCache(self)
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
     * Low-level loading primitive. Returns a `(cursor, state)` pair; the cursor
     * reflects whatever the source returned (potentially updating the hint's
     * `prev`/`next` links).
     */
    suspend inline fun loadOrGetPageState(
        hint: CursorBookmark,
        cachedCursor: CursorBookmark = hint,
        forceLoading: Boolean = false,
        loading: ((cursor: CursorBookmark, state: PageState<T>?) -> Unit) = { _, _ -> },
        noinline load: suspend CursorPaginator<T>.(cursor: CursorBookmark?) -> CursorLoadResult<T> = this.load,
        noinline initEmptyState: InitializerEmptyPage<T> = core.initializerEmptyPage,
        noinline initSuccessState: InitializerSuccessPage<T> = core.initializerSuccessPage,
        noinline initErrorState: InitializerErrorPage<T> = core.initializerErrorPage,
    ): Pair<CursorBookmark, PageState<T>> {
        logger.debug(LogComponent.NAVIGATION) {
            "loadOrGetPageState: self=${hint.self} forceLoading=$forceLoading"
        }
        val cachedState: PageState<T>? = cache.getStateOf(hint.self)
        if (!forceLoading && core.isFilledSuccessState(cachedState)) {
            return cachedCursor to cachedState
        }
        loading.invoke(cachedCursor, cachedState)
        return try {
            val loadResult: CursorLoadResult<T> = load.invoke(this, hint)
            val data: MutableList<T> = loadResult.data.let {
                if (core.isCapacityUnlimited) it else it.take(core.capacity)
            }.toMutableList()
            val resultCursor = loadResult.bookmark
            val resultState: PageState<T> = if (data.isEmpty()) {
                core.coerceToCapacity(
                    initEmptyState.invoke(
                        syntheticPage(),
                        data,
                        loadResult.metadata
                    )
                )
            } else {
                core.coerceToCapacity(
                    initSuccessState.invoke(
                        syntheticPage(),
                        data,
                        loadResult.metadata
                    )
                )
            }
            resultCursor to resultState
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn(LogComponent.NAVIGATION) {
                "loadOrGetPageState: self=${hint.self} exception=$exception"
            }
            val data: List<T> = core.coerceToCapacity(cachedState?.data ?: mutableListOf())
            val errorState = core.coerceToCapacity(
                state = initErrorState.invoke(
                    exception,
                    syntheticPage(),
                    data,
                    cachedState?.metadata
                )
            )
            cachedCursor to errorState
        }
    }

    private suspend fun persistSuccessState(cursor: CursorBookmark, state: PageState<T>) {
        if (state is SuccessPage) core.persistentCache?.save(cursor, state)
    }

    /**
     * Launches a fire-and-forget refresh for all dirty cursors within the current
     * context window.
     */
    protected fun CoroutineScope.refreshDirtyCursorsInContext() {
        if (!cache.isStarted) return
        val start = core.startContextCursor ?: return
        val end = core.endContextCursor ?: return
        val dirty: List<Any> = core.drainDirtyCursorsInRange(start, end) ?: return
        val bookmarks = dirty.mapNotNull { cache.getCursorOf(it) }
        if (bookmarks.isEmpty()) return
        logger.debug(LogComponent.LIFECYCLE) { "refreshDirtyCursorsInContext: ${bookmarks.map { it.self }}" }
        launch { refresh(cursors = bookmarks, loadingSilently = true) }
    }

    /**
     * Synchronises [bookmarkIndex] so that it sits right after the last bookmark
     * whose `self` key was reached during navigation.
     */
    private fun syncBookmarkIndex(cursor: CursorBookmark) {
        if (bookmarks.isEmpty()) return
        val index = bookmarks.indexOfFirst { it.self == cursor.self }
        bookmarkIndex = if (index == -1) bookmarks.size else (index + 1)
    }

    /**
     * Releases all resources and resets the paginator to its initial state.
     */
    fun release(
        capacity: Int = DEFAULT_CAPACITY,
        silently: Boolean = false,
    ) {
        logger.info(LogComponent.LIFECYCLE) { "release" }
        core.release(capacity, silently)
        bookmarks.clear()
        bookmarkIndex = 0
        initialCursor = null
        lockJump = false
        lockGoNextPage = false
        lockGoPreviousPage = false
        lockRestart = false
        lockRefresh = false
    }

    // ── Transaction / snapshot save-point ───────────────────────────────────

    private class CursorTransactionSavepoint<T>(
        val states: List<Pair<CursorBookmark, PageState<T>>>,
        val startContextCursor: CursorBookmark?,
        val endContextCursor: CursorBookmark?,
        val capacity: Int,
        val dirtyCursors: Set<Any>,
        val bookmarks: List<CursorBookmark>,
        val bookmarkIndex: Int,
        val recyclingBookmark: Boolean,
        val initialCursor: CursorBookmark?,
        val lockJump: Boolean,
        val lockGoNextPage: Boolean,
        val lockGoPreviousPage: Boolean,
        val lockRestart: Boolean,
        val lockRefresh: Boolean,
    )

    private fun createSavepoint(): CursorTransactionSavepoint<T> {
        val statesCopy: List<Pair<CursorBookmark, PageState<T>>> =
            cache.cursors.mapNotNull { cursor ->
                val state = cache.getStateOf(cursor.self) ?: return@mapNotNull null
                cursor to state.copy(data = state.data.toMutableList())
            }
        return CursorTransactionSavepoint(
            states = statesCopy,
            startContextCursor = core.startContextCursor,
            endContextCursor = core.endContextCursor,
            capacity = core.capacity,
            dirtyCursors = core.dirtyCursors,
            bookmarks = bookmarks.toList(),
            bookmarkIndex = bookmarkIndex,
            recyclingBookmark = recyclingBookmark,
            initialCursor = initialCursor,
            lockJump = lockJump,
            lockGoNextPage = lockGoNextPage,
            lockGoPreviousPage = lockGoPreviousPage,
            lockRestart = lockRestart,
            lockRefresh = lockRefresh,
        )
    }

    open suspend fun <R> transaction(block: suspend CursorPaginator<T>.() -> R): R {
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

    private fun rollback(savepoint: CursorTransactionSavepoint<T>) {
        cache.clear()
        core.clearAllDirty()
        savepoint.states.forEach { (cursor, state) ->
            cache.setState(cursor, state, silently = true)
        }
        savepoint.dirtyCursors.forEach { core.markDirty(it) }
        core.capacity = savepoint.capacity
        core.startContextCursor = savepoint.startContextCursor
        core.endContextCursor = savepoint.endContextCursor

        initialCursor = savepoint.initialCursor
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
        if (core.enableCacheFlow) core.repeatCacheFlow()
    }

    // ── Serialization ───────────────────────────────────────────────────────

    suspend fun saveState(
        selfEncoder: (Any) -> JsonElement,
        contextOnly: Boolean = false,
        metadataEncoder: ((Metadata?) -> JsonElement?)? = null,
    ): CursorPaginatorSnapshot<T> {
        navigationMutex.lock()
        try {
            return CursorPaginatorSnapshot(
                coreSnapshot = core.saveState(contextOnly, selfEncoder, metadataEncoder),
                bookmarkSelves = bookmarks.map { selfEncoder.invoke(it.self) },
                bookmarkPrevSelves = bookmarks.map { it.prev?.let(selfEncoder) },
                bookmarkNextSelves = bookmarks.map { it.next?.let(selfEncoder) },
                bookmarkIndex = bookmarkIndex,
                recyclingBookmark = recyclingBookmark,
                initialCursorSelf = initialCursor?.self?.let(selfEncoder),
                initialCursorPrevSelf = initialCursor?.prev?.let(selfEncoder),
                initialCursorNextSelf = initialCursor?.next?.let(selfEncoder),
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

    suspend fun restoreState(
        snapshot: CursorPaginatorSnapshot<T>,
        selfDecoder: (JsonElement) -> Any,
        silently: Boolean = false,
        metadataDecoder: ((JsonElement?) -> Metadata?)? = null,
    ) {
        navigationMutex.lock()
        try {
            core.restoreState(snapshot.coreSnapshot, silently, selfDecoder, metadataDecoder)

            bookmarks.clear()
            for (i in snapshot.bookmarkSelves.indices) {
                val selfKey = selfDecoder.invoke(snapshot.bookmarkSelves[i])
                val prevKey = snapshot.bookmarkPrevSelves.getOrNull(i)?.let(selfDecoder)
                val nextKey = snapshot.bookmarkNextSelves.getOrNull(i)?.let(selfDecoder)
                bookmarks.add(CursorBookmark(prev = prevKey, self = selfKey, next = nextKey))
            }
            bookmarkIndex = snapshot.bookmarkIndex.coerceIn(0, bookmarks.size)
            recyclingBookmark = snapshot.recyclingBookmark

            initialCursor = snapshot.initialCursorSelf?.let(selfDecoder)?.let { self ->
                CursorBookmark(
                    prev = snapshot.initialCursorPrevSelf?.let(selfDecoder),
                    self = self,
                    next = snapshot.initialCursorNextSelf?.let(selfDecoder),
                )
            }

            lockJump = snapshot.lockJump
            lockGoNextPage = snapshot.lockGoNextPage
            lockGoPreviousPage = snapshot.lockGoPreviousPage
            lockRestart = snapshot.lockRestart
            lockRefresh = snapshot.lockRefresh
        } finally {
            navigationMutex.unlock()
        }
    }

    // ── Operators ───────────────────────────────────────────────────────────

    operator fun iterator(): Iterator<PageState<T>> = core.iterator()

    operator fun contains(self: Any): Boolean = cache.getStateOf(self) != null

    operator fun contains(pageState: PageState<T>): Boolean {
        // PageState.page is synthetic here; equality goes by id (see PageState.equals).
        for (state in core.states) if (state == pageState) return true
        return false
    }

    operator fun get(self: Any): PageState<T>? = cache.getStateOf(self)

    operator fun get(self: Any, index: Int): T? = cache.getElement(self, index)

    override fun toString(): String = "CursorPaginator(cache=$cache, bookmarks=$bookmarks)"

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = super.hashCode()

    companion object {
        @PublishedApi
        internal val pageCounter = atomic(0)

        @PublishedApi
        internal const val PROGRESS_SENTINEL: String = "__cursor_paginator_progress_sentinel__"

        /**
         * Synthesises a positive `page` number for [PageState] objects produced by
         * the cursor paginator. The cursor paginator does not read this field — it
         * is required only by the factory signature inherited from [PagingCore].
         */
        @PublishedApi
        internal fun syntheticPage(): Int {
            val next = pageCounter.incrementAndGet()
            return if (next <= 0) {
                pageCounter.value = 1
                1
            } else next
        }
    }
}
