// The deprecated L2 persistent cache is still wired through here until it is removed.
@file:Suppress("DEPRECATION")

package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.cursor.CursorPagingCore.Companion.DEFAULT_CAPACITY
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.bookmark.CursorBootstrapKey
import com.jamal_aliev.paginator.cursor.cache.CursorPagingCache
import com.jamal_aliev.paginator.cursor.exception.CursorBootstrapFailedException
import com.jamal_aliev.paginator.cursor.exception.CursorLoadGuardedException
import com.jamal_aliev.paginator.cursor.exception.EndOfCursorFeedException
import com.jamal_aliev.paginator.core.exception.LockedException.GoNextPageWasLockedException
import com.jamal_aliev.paginator.core.exception.LockedException.GoPreviousPageWasLockedException
import com.jamal_aliev.paginator.core.exception.LockedException.JumpWasLockedException
import com.jamal_aliev.paginator.core.exception.LockedException.RefreshWasLockedException
import com.jamal_aliev.paginator.core.exception.LockedException.RestartWasLockedException
import com.jamal_aliev.paginator.core.extension.isProgressState
import com.jamal_aliev.paginator.cursor.initializer.InitializerCursorErrorPage
import com.jamal_aliev.paginator.cursor.initializer.InitializerCursorProgressPage
import com.jamal_aliev.paginator.cursor.initializer.InitializerCursorSuccessPage
import com.jamal_aliev.paginator.cursor.load.CursorLoadResult
import com.jamal_aliev.paginator.core.load.Metadata
import com.jamal_aliev.paginator.core.logger.LogComponent
import com.jamal_aliev.paginator.core.logger.PaginatorLogger
import com.jamal_aliev.paginator.core.logger.debug
import com.jamal_aliev.paginator.core.logger.info
import com.jamal_aliev.paginator.core.logger.warn
import com.jamal_aliev.paginator.core.navigation.PaginatorNavigationEvent
import com.jamal_aliev.paginator.core.page.PageState
import com.jamal_aliev.paginator.cursor.page.CursorPageState
import com.jamal_aliev.paginator.cursor.serialization.CursorPaginatorSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * A read-only, reactive cursor-based pagination manager.
 *
 * `CursorPaginator` is a LinkedList-style counterpart to the RandomAccess-style
 * [Paginator]: every page knows only its immediate neighbours through a
 * [CursorBookmark<K>], and the paginator navigates by following `prev`/`next`
 * links instead of numeric page indices.
 *
 * ## Loading contract
 *
 * The [load] lambda receives an optional [CursorBookmark<K>]:
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
open class CursorPaginator<K : Any, T>(
    val core: CursorPagingCore<K, T> = CursorPagingCore(),
    var load: suspend CursorPaginator<K, T>.(cursor: CursorBookmark<K>?) -> CursorLoadResult<K, T>,
) {

    /**
     * The paginator's cache ([CursorPagingCore.cache]). While the core runs in untracked mode
     * ([CursorPagingCore.withUntrackedReads] — internal bookkeeping such as applying reactive
     * events) this is a view whose accesses do not count as a use of the pages for eviction
     * strategies.
     */
    val cache: CursorPagingCache<K, T> get() = if (core.untrackedReads) core.untrackedCache else core.cache

    /** Mutex serialising all mutations to cache across concurrent coroutines. */
    protected val navigationMutex = Mutex()

    /**
     * Runs [block] under [navigationMutex], for extensions in this module that write into the
     * cache from outside navigation (e.g. [com.jamal_aliev.paginator.cursor.extension.observe]).
     */
    internal suspend fun <R> withNavigationLock(block: suspend () -> R): R =
        navigationMutex.withLock { block() }

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
    var initialCursor: CursorBookmark<K>? = null

    /**
     * Predefined bookmark targets for quick navigation via [jumpForward] /
     * [jumpBack]. Defaults to an empty list (unlike [Paginator] which pre-seeds
     * page 1) because cursor keys are opaque and server-provided.
     */
    val bookmarks: MutableList<CursorBookmark<K>> = mutableListOf()

    var recyclingBookmark = false
    protected var bookmarkIndex: Int = 0

    private val _navigationEvents = MutableSharedFlow<PaginatorNavigationEvent<CursorBookmark<K>>>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Emits a [PaginatorNavigationEvent] whenever navigation lands — currently
     * [PaginatorNavigationEvent.Jumped] from [jump] and the [jumpForward] / [jumpBack] calls that
     * delegate to it. Lets a UI layer react (e.g. scroll a list to the jumped-to page) **regardless
     * of where navigation was triggered** (e.g. `paginator.jump(...)` from a ViewModel), since the
     * event comes from the paginator itself. Transient (no replay); collect it while the UI is on
     * screen — the Compose turnkeys do this for you.
     */
    val navigationEvents: Flow<PaginatorNavigationEvent<CursorBookmark<K>>> get() = _navigationEvents

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
        loadGuard: (cursor: CursorBookmark<K>, state: CursorPageState<K, T>?) -> Boolean = { _, _ -> true },
        lockJump: Boolean = this.lockJump,
        enableCacheFlow: Boolean = core.enableCacheFlow,
        initProgressState: InitializerCursorProgressPage<K, T> = core.initializerProgressPage,
        initSuccessState: InitializerCursorSuccessPage<K, T> = core.initializerSuccessPage,
        initErrorState: InitializerCursorErrorPage<K, T> = core.initializerErrorPage,
    ): Pair<CursorBookmark<K>, CursorPageState<K, T>>? {
        if (lockJump) throw JumpWasLockedException()
        logger.debug(LogComponent.NAVIGATION) { "jumpForward: recycling=$recycling" }

        val visibleSelves: Set<K> = core.snapshotSelves().toHashSet()
        var lastSkipped: CursorBookmark<K>? = null
        var candidate: CursorBookmark<K>? = null

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
        loadGuard: (cursor: CursorBookmark<K>, state: CursorPageState<K, T>?) -> Boolean = { _, _ -> true },
        lockJump: Boolean = this.lockJump,
        enableCacheFlow: Boolean = core.enableCacheFlow,
        initProgressState: InitializerCursorProgressPage<K, T> = core.initializerProgressPage,
        initSuccessState: InitializerCursorSuccessPage<K, T> = core.initializerSuccessPage,
        initErrorState: InitializerCursorErrorPage<K, T> = core.initializerErrorPage,
    ): Pair<CursorBookmark<K>, CursorPageState<K, T>>? {
        if (lockJump) throw JumpWasLockedException()
        logger.debug(LogComponent.NAVIGATION) { "jumpBack: recycling=$recycling" }

        val visibleSelves: Set<K> = core.snapshotSelves().toHashSet()
        var lastSkipped: CursorBookmark<K>? = null
        var candidate: CursorBookmark<K>? = null

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
     *   constructed stub (`CursorBookmark<K>(null, selfKey, null)`) when jumping
     *   to a known `self` without knowing its neighbours in advance — the
     *   server response will fill in the real links.
     */
    suspend fun jump(
        bookmark: CursorBookmark<K>,
        silentlyLoading: Boolean = false,
        silentlyResult: Boolean = false,
        loadGuard: (cursor: CursorBookmark<K>, state: CursorPageState<K, T>?) -> Boolean = { _, _ -> true },
        lockJump: Boolean = this.lockJump,
        enableCacheFlow: Boolean = core.enableCacheFlow,
        initProgressState: InitializerCursorProgressPage<K, T> = core.initializerProgressPage,
        initSuccessState: InitializerCursorSuccessPage<K, T> = core.initializerSuccessPage,
        initErrorState: InitializerCursorErrorPage<K, T> = core.initializerErrorPage,
    ): Pair<CursorBookmark<K>, CursorPageState<K, T>> = coroutineScope {
        if (lockJump) throw JumpWasLockedException()
        logger.debug(LogComponent.NAVIGATION) { "jump: self=${bookmark.self}" }

        var savedStart: CursorBookmark<K>? = core.startContextCursor
        var savedEnd: CursorBookmark<K>? = core.endContextCursor
        var savedState: CursorPageState<K, T>? = null
        var savedCursor: CursorBookmark<K>? = null
        var shouldCleanup = false

        navigationMutex.lock()
        try {
            // A jump starts the feed from a real key, ending any anchorless bootstrap phase.
            core.setBootstrapState(state = null, emit = false)
            // Check L1 first, then L2.
            var cachedState: CursorPageState<K, T>? = cache.getStateOf(bookmark.self)
            var effectiveCursor: CursorBookmark<K> = cache.getCursorOf(bookmark.self) ?: bookmark
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
                if (enableCacheFlow) core.repeatCacheFlow()
                if (!silentlyResult) core.snapshot()
                logger.debug(LogComponent.NAVIGATION) { "jump: self=${bookmark.self} cache hit" }
                syncBookmarkIndex(effectiveCursor)
                refreshDirtyCursorsInContext()
                _navigationEvents.tryEmit(PaginatorNavigationEvent.Jumped(effectiveCursor))
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
                    val progressState: CursorPageState.Progress<K, T> = core.coerceToCapacity(
                        state = initProgressState.invoke(cursor, data, pageState?.metadata)
                    ) as CursorPageState.Progress<K, T>
                    cache.setState(cursor, progressState, silently = true)
                    core.expandStartContextCursor(progressState, cursor)
                    core.expandEndContextCursor(progressState, cursor)
                    if (enableCacheFlow) core.repeatCacheFlow()
                    if (!silentlyLoading) core.snapshot()
                },
                initSuccessState = initSuccessState,
                initErrorState = initErrorState,
            )
            shouldCleanup = false
            cache.setState(resultCursor, resultState, silently = true)
            if (core.isFilledSuccessState(resultState)) {
                core.expandStartContextCursor(resultState, resultCursor)
                core.expandEndContextCursor(resultState, resultCursor)
            } else {
                // The landing page is not a filled boundary, but adjacent already-cached filled
                // pages should still be absorbed so loaded neighbors stay visible. Keep the landing
                // at a boundary — never span the window across it (that would bury a non-filled page
                // in the interior). Absorb the cached filled run on one side: backward (toward prev)
                // when there's a filled page below — the landing stays at endContextCursor; otherwise
                // forward (toward next), the landing stays at startContextCursor. Mirrors offset jump.
                val belowCursor: CursorBookmark<K>? = cache.walkBackward(resultCursor)
                val belowState: CursorPageState<K, T>? = belowCursor?.let { cache.getStateOf(it.self) }
                if (core.isFilledSuccessState(belowState)) {
                    core.endContextCursor = resultCursor
                    core.expandStartContextCursor(belowState, belowCursor)
                } else {
                    core.startContextCursor = resultCursor
                    val aboveCursor: CursorBookmark<K>? = cache.walkForward(resultCursor)
                    core.expandEndContextCursor(aboveCursor?.let { cache.getStateOf(it.self) }, aboveCursor)
                }
            }

            if (enableCacheFlow) core.repeatCacheFlow()
            if (!silentlyResult) core.snapshot()

            logger.debug(LogComponent.NAVIGATION) {
                "jump: self=${resultCursor.self} result=${resultState::class.simpleName}"
            }
            persistSuccessState(resultCursor, resultState)
            syncBookmarkIndex(resultCursor)
            refreshDirtyCursorsInContext()
            _navigationEvents.tryEmit(PaginatorNavigationEvent.Jumped(resultCursor))
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
        loadGuard: (cursor: CursorBookmark<K>, state: CursorPageState<K, T>?) -> Boolean = { _, _ -> true },
        lockGoNextPage: Boolean = this.lockGoNextPage,
        enableCacheFlow: Boolean = core.enableCacheFlow,
        initProgressState: InitializerCursorProgressPage<K, T> = core.initializerProgressPage,
        initSuccessState: InitializerCursorSuccessPage<K, T> = core.initializerSuccessPage,
        initErrorState: InitializerCursorErrorPage<K, T> = core.initializerErrorPage,
    ): CursorPageState<K, T> = coroutineScope {
        if (lockGoNextPage) throw GoNextPageWasLockedException()
        logger.debug(LogComponent.NAVIGATION) { "goNextPage" }
        if (!cache.isStarted) {
            // Implicit bootstrap: the goNext guard signature takes a non-null cursor,
            // which isn't applicable to the initial restart. Skip the guard here —
            // the caller can always run restart() explicitly with a nullable-cursor guard.
            val bootstrap: PageState<T>? = restartInternal(
                silentlyLoading = silentlyLoading,
                silentlyResult = silentlyResult,
                loadGuard = { _, _ -> true },
                enableCacheFlow = enableCacheFlow,
                initProgressState = initProgressState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState,
                implicit = true,
            )
            when (bootstrap) {
                // Another call started the feed while this one waited: continue as a normal step.
                null -> Unit
                is CursorPageState<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    return@coroutineScope bootstrap as CursorPageState<K, T>
                }
                // An anchorless first load that failed has no keyed page to return.
                is PageState.ErrorState<T> -> throw CursorBootstrapFailedException(bootstrap)
                else -> error("Unexpected bootstrap result: $bootstrap")
            }
        }

        var savedCursor: CursorBookmark<K>? = null
        var savedState: CursorPageState<K, T>? = null
        var shouldCleanup = false

        navigationMutex.lock()
        try {
            var pivotCursor: CursorBookmark<K> = cache.endContextCursor
                ?: throw IllegalStateException("endContextCursor is null but cache.isStarted is true")
            var pivotState: CursorPageState<K, T>? = cache.getStateOf(pivotCursor.self)
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
            val nextTargetSelf: K? = if (pivotIsFilled) pivotCursor.next else null
            if (pivotIsFilled && nextTargetSelf == null) {
                throw EndOfCursorFeedException(
                    attemptedCursorKey = pivotCursor.self,
                    direction = EndOfCursorFeedException.Direction.FORWARD,
                )
            }

            val targetCursor: CursorBookmark<K> =
                if (!pivotIsFilled) pivotCursor
                else cache.getCursorOf(nextTargetSelf!!)
                    ?: CursorBookmark<K>(
                        prev = pivotCursor.self,
                        self = nextTargetSelf,
                        next = null
                    )

            var targetState: CursorPageState<K, T>? = cache.getStateOf(targetCursor.self)
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
                    val progressState: CursorPageState.Progress<K, T> = core.coerceToCapacity(
                        state = initProgressState.invoke(cursor, data, cached?.metadata)
                    ) as CursorPageState.Progress<K, T>
                    cache.setState(cursor, progressState, silently = true)
                    if (enableCacheFlow) core.repeatCacheFlow()
                    if (!silentlyLoading) core.snapshot()
                },
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
        loadGuard: (cursor: CursorBookmark<K>, state: CursorPageState<K, T>?) -> Boolean = { _, _ -> true },
        enableCacheFlow: Boolean = core.enableCacheFlow,
        initProgressState: InitializerCursorProgressPage<K, T> = core.initializerProgressPage,
        initSuccessState: InitializerCursorSuccessPage<K, T> = core.initializerSuccessPage,
        initErrorState: InitializerCursorErrorPage<K, T> = core.initializerErrorPage,
    ): CursorPageState<K, T> = coroutineScope {
        if (lockGoPreviousPage) throw GoPreviousPageWasLockedException()
        logger.debug(LogComponent.NAVIGATION) { "goPreviousPage" }
        check(cache.isStarted) {
            "Paginator was not started. First of all paginator must be jumped (started) " +
                    "via jump() or restart()."
        }

        var savedCursor: CursorBookmark<K>? = null
        var savedState: CursorPageState<K, T>? = null
        var shouldCleanup = false

        navigationMutex.lock()
        try {
            var pivotCursor: CursorBookmark<K> = cache.startContextCursor
                ?: throw IllegalStateException("startContextCursor is null but cache.isStarted is true")
            var pivotState: CursorPageState<K, T>? = cache.getStateOf(pivotCursor.self)
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

            // Always step to the page before the anchor (mirror of offset goPreviousPage). A
            // non-filled start anchor (a partial tail or an errored/empty jump target) must NOT be
            // reloaded in place here: earlier pages always exist behind it via prev, so reloading
            // would trap backward navigation on that page forever. The anchor stays in the window as
            // the end boundary and remains retryable via goNextPage / refresh. Only a true head
            // (prev == null) is the backward terminus — unlike goNextPage, which reloads a non-filled
            // end because forward can run into the end of the feed.
            val prevTargetSelf: K = pivotCursor.prev
                ?: throw EndOfCursorFeedException(
                    attemptedCursorKey = pivotCursor.self,
                    direction = EndOfCursorFeedException.Direction.BACKWARD,
                )

            val targetCursor: CursorBookmark<K> =
                cache.getCursorOf(prevTargetSelf)
                    ?: CursorBookmark<K>(
                        prev = null,
                        self = prevTargetSelf,
                        next = pivotCursor.self
                    )

            var targetState: CursorPageState<K, T>? = cache.getStateOf(targetCursor.self)
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
                    val progressState: CursorPageState.Progress<K, T> = core.coerceToCapacity(
                        state = initProgressState.invoke(cursor, data, cached?.metadata)
                    ) as CursorPageState.Progress<K, T>
                    cache.setState(cursor, progressState, silently = true)
                    if (enableCacheFlow) core.repeatCacheFlow()
                    if (!silentlyLoading) core.snapshot()
                },
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
        loadGuard: (cursor: CursorBookmark<K>?, state: CursorPageState<K, T>?) -> Boolean = { _, _ -> true },
        enableCacheFlow: Boolean = core.enableCacheFlow,
        initProgressState: InitializerCursorProgressPage<K, T> = core.initializerProgressPage,
        initSuccessState: InitializerCursorSuccessPage<K, T> = core.initializerSuccessPage,
        initErrorState: InitializerCursorErrorPage<K, T> = core.initializerErrorPage,
    ): Unit = coroutineScope {
        if (lockRestart) throw RestartWasLockedException()
        logger.info(LogComponent.LIFECYCLE) { "restart" }
        restartInternal(
            silentlyLoading = silentlyLoading,
            silentlyResult = silentlyResult,
            loadGuard = { cursor, state -> loadGuard.invoke(cursor, state) },
            enableCacheFlow = enableCacheFlow,
            initProgressState = initProgressState,
            initSuccessState = initSuccessState,
            initErrorState = initErrorState,
        )
    }

    /**
     * Shared implementation behind [restart] and the implicit-restart branch of
     * [goNextPage] when the paginator has not been started yet.
     *
     * With an [initialCursor] the first page has a key from the start and goes through the cache
     * like any other load. Without one there is no key until the source answers, so the transient
     * Progress / Error state is published through [CursorPagingCore.bootstrapState] instead of
     * being cached under a fake key; the cache stays empty until the first page arrives.
     *
     * @param implicit `true` for the [goNextPage] bootstrap: if another call started the feed while
     *   this one waited for the lock, nothing is loaded and `null` is returned.
     * @return The resulting state — a [CursorPageState] once a page is cached, the bootstrap
     *   [PageState.ErrorState] when the anchorless first load failed, or `null` (see [implicit]).
     */
    internal suspend fun restartInternal(
        silentlyLoading: Boolean,
        silentlyResult: Boolean,
        loadGuard: (cursor: CursorBookmark<K>?, state: CursorPageState<K, T>?) -> Boolean,
        enableCacheFlow: Boolean,
        initProgressState: InitializerCursorProgressPage<K, T>,
        initSuccessState: InitializerCursorSuccessPage<K, T>,
        initErrorState: InitializerCursorErrorPage<K, T>,
        implicit: Boolean = false,
    ): PageState<T>? = coroutineScope {
        val anchor: CursorBookmark<K>? = initialCursor
        var shouldCleanup = false

        navigationMutex.lock()
        try {
            if (implicit && cache.isStarted) return@coroutineScope null

            cache.clear()
            // clear() keeps the context cursors, which would now point at pages that are gone.
            core.startContextCursor = null
            core.endContextCursor = null
            core.clearAllDirty()

            if (!loadGuard.invoke(anchor, null)) {
                throw CursorLoadGuardedException(
                    attemptedCursor = anchor
                        ?: CursorBookmark<Any>(prev = null, self = CursorBootstrapKey, next = null)
                )
            }

            shouldCleanup = true

            // Emit a transient progress state before hitting the network.
            if (anchor != null) {
                core.setBootstrapState(state = null, emit = false)
                val progressState: CursorPageState.Progress<K, T> = core.coerceToCapacity(
                    state = initProgressState.invoke(anchor, mutableListOf(), null)
                ) as CursorPageState.Progress<K, T>
                cache.setState(anchor, progressState, silently = true)
                core.startContextCursor = anchor
                core.endContextCursor = anchor
                if (enableCacheFlow) core.repeatCacheFlow()
                if (!silentlyLoading) core.snapshot()
            } else {
                core.setBootstrapState(
                    state = core.initializerBootstrapProgressPage.invoke(),
                    emit = !silentlyLoading,
                )
            }

            val loadResult: CursorLoadResult<K, T> = try {
                load.invoke(this@CursorPaginator, anchor)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (exception: Exception) {
                logger.warn(LogComponent.NAVIGATION) { "restart: exception=$exception" }
                shouldCleanup = false
                if (anchor == null) {
                    // No key to cache the failure under: it stays the bootstrap state, so
                    // uiState / visibleSnapshot keep publishing it (issue #3) and the next
                    // goNextPage() or restart() retries with load(null).
                    val errorState: PageState.ErrorState<T> =
                        core.initializerBootstrapErrorPage.invoke(exception)
                    core.setBootstrapState(state = errorState, emit = !silentlyResult)
                    if (enableCacheFlow) core.repeatCacheFlow()
                    return@coroutineScope errorState
                }
                val errorState = core.coerceToCapacity(
                    state = initErrorState.invoke(exception, anchor, mutableListOf(), null)
                )
                cache.setState(anchor, errorState, silently = true)
                if (enableCacheFlow) core.repeatCacheFlow()
                if (!silentlyResult) core.snapshot(anchor to anchor)
                return@coroutineScope errorState
            }

            val resultCursor: CursorBookmark<K> = loadResult.bookmark
            val data: List<T> = core.coerceToCapacity(loadResult.data)
            val resultState: CursorPageState<K, T> = core.coerceToCapacity(
                initSuccessState.invoke(
                    resultCursor,
                    data.toMutableList(),
                    loadResult.metadata
                )
            )

            shouldCleanup = false
            // The source may answer with a different self than the anchor hint.
            if (anchor != null && anchor.self != resultCursor.self) {
                cache.removeFromCache(anchor.self)
            }
            core.setBootstrapState(state = null, emit = false)
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
                    // The cache was cleared before loading, so there is nothing to go back to:
                    // drop the transient state and leave the paginator not started.
                    if (anchor != null) cache.removeFromCache(anchor.self)
                    core.startContextCursor = null
                    core.endContextCursor = null
                    core.setBootstrapState(state = null, emit = true)
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
        cursors: List<CursorBookmark<K>>,
        loadingSilently: Boolean = false,
        finalSilently: Boolean = false,
        loadGuard: (cursor: CursorBookmark<K>, state: CursorPageState<K, T>?) -> Boolean = { _, _ -> true },
        enableCacheFlow: Boolean = core.enableCacheFlow,
        initProgressState: InitializerCursorProgressPage<K, T> = core.initializerProgressPage,
        initSuccessState: InitializerCursorSuccessPage<K, T> = core.initializerSuccessPage,
        initErrorState: InitializerCursorErrorPage<K, T> = core.initializerErrorPage,
    ): Unit = coroutineScope {
        if (lockRefresh) throw RefreshWasLockedException()
        logger.debug(LogComponent.LIFECYCLE) { "refresh: cursors=${cursors.map { it.self }}" }

        var savedStates: Map<K, Pair<CursorBookmark<K>?, CursorPageState<K, T>?>> = emptyMap()
        // Ids of the Progress states installed under lock before loading. The lock is released
        // while the pages load, so by the time the results are committed (or rolled back on
        // cancellation) a page may have been evicted, cleared by restart/release, reloaded by
        // jump or overwritten by a reactive source. Only a page still holding its own Progress
        // state is written back; anything else is newer than the in-flight result and wins.
        val progressIds: MutableMap<K, Long> = mutableMapOf()

        fun ownsPage(self: K): Boolean {
            val ownId: Long = progressIds[self] ?: return false
            return cache.getStateOf(self)?.id == ownId
        }

        // Dirty flags of the refreshed pages are taken over before loading, so a page marked dirty
        // while the refresh is loading keeps its flag. Pages the refresh does not end up writing
        // get their original flag back.
        var dirtyAtStart: List<K> = emptyList()
        var committedSelves: Set<K> = emptySet()

        // A page the refresh did not write gets its flag back only while it still holds the
        // refresh's own Progress state or its pre-refresh state; a page another writer replaced
        // meanwhile (a reload, a reactive update) already holds fresher data.
        fun restoreUncommittedDirtyFlags() {
            core.markDirty(dirtyAtStart.filter { self ->
                if (self in committedSelves) return@filter false
                val currentId: Long = cache.getStateOf(self)?.id ?: return@filter false
                currentId == progressIds[self] || currentId == savedStates[self]?.second?.id
            })
        }

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
                    val cachedCursor: CursorBookmark<K> =
                        savedStates[candidate.self]?.first ?: candidate
                    val cachedState: CursorPageState<K, T>? = savedStates[candidate.self]?.second
                    val data: List<T> = core.coerceToCapacity(cachedState?.data ?: mutableListOf())
                    val progressState: CursorPageState.Progress<K, T> = core.coerceToCapacity(
                        state = initProgressState.invoke(
                            cachedCursor,
                            data,
                            cachedState?.metadata
                        )
                    ) as CursorPageState.Progress<K, T>
                    cache.setState(cachedCursor, progressState, silently = true)
                    progressIds[cachedCursor.self] = progressState.id
                }
                dirtyAtStart = cursors.map { it.self }.filter { core.isDirty(it) }
                core.clearDirty(cursors.map { it.self })
            } finally {
                navigationMutex.unlock()
            }
            if (enableCacheFlow) core.repeatCacheFlow()
            if (!loadingSilently) core.snapshot()

            val results: List<Pair<CursorBookmark<K>, CursorPageState<K, T>>> =
                cursors.map { candidate ->
                async {
                    loadOrGetPageState(
                        hint = candidate,
                        cachedCursor = savedStates[candidate.self]?.first ?: candidate,
                        forceLoading = true,
                        initSuccessState = initSuccessState,
                        initErrorState = initErrorState,
                    )
                }
            }.awaitAll()

            val committed: List<Pair<CursorBookmark<K>, CursorPageState<K, T>>>
            navigationMutex.lock()
            try {
                committed = results.filterIndexed { index, _ -> ownsPage(cursors[index].self) }
                committedSelves = cursors.filterIndexed { index, _ -> results[index] in committed }
                    .mapTo(HashSet()) { it.self }
                committed.forEach { (cursor, state) ->
                    cache.setState(cursor, state, silently = true)
                }
                if (committed.size < results.size) {
                    logger.debug(LogComponent.LIFECYCLE) {
                        "refresh: skipped cursors changed during loading=" +
                                cursors.filterIndexed { index, _ -> results[index] !in committed }
                                    .map { it.self }
                    }
                }
                // Re-anchor the context window if it collapsed onto non-filled pages.
                // Happens when the backend deleted the currently viewed range while the
                // client was offline: the refreshed pages come back as empty Success, the
                // window keeps pointing at them, and the user sees a blank screen even
                // though other filled pages are still cached. Snap the window to the
                // nearest filled-success group so those live pages become visible again.
                val startC: CursorBookmark<K>? = cache.startContextCursor
                val endC: CursorBookmark<K>? = cache.endContextCursor
                if (startC != null && endC != null
                    && (!core.isFilledSuccessState(core.getStateOf(startC.self))
                            || !core.isFilledSuccessState(core.getStateOf(endC.self)))
                ) {
                    core.findNearContextCursor(startCursor = startC, endCursor = endC)
                    // The window moved to a new position; realign the bookmark cursor so a
                    // subsequent jumpForward/jumpBack resumes from here, not from the deleted
                    // range — consistent with jump/restart, which both syncBookmarkIndex.
                    core.startContextCursor?.let { syncBookmarkIndex(it) }
                }
            } finally {
                navigationMutex.unlock()
            }

            core.persistentCache?.let { pc ->
                val successes = committed.filter { it.second is CursorPageState.Success<*, *> }
                if (successes.isNotEmpty()) pc.saveAll(successes)
            }

            restoreUncommittedDirtyFlags()

            if (enableCacheFlow) core.repeatCacheFlow()
            if (!finalSilently) core.snapshot()
            logger.debug(LogComponent.LIFECYCLE) { "refresh: done" }
        } catch (e: CancellationException) {
            restoreUncommittedDirtyFlags()
            if (savedStates.isNotEmpty()) {
                withContext(NonCancellable) {
                    navigationMutex.lock()
                    try {
                        savedStates.forEach { (self, saved) ->
                            if (!ownsPage(self)) return@forEach
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
        hint: CursorBookmark<K>,
        cachedCursor: CursorBookmark<K> = hint,
        forceLoading: Boolean = false,
        loading: ((cursor: CursorBookmark<K>, state: CursorPageState<K, T>?) -> Unit) = { _, _ -> },
        noinline load: suspend CursorPaginator<K, T>.(cursor: CursorBookmark<K>?) -> CursorLoadResult<K, T> = this.load,
        noinline initSuccessState: InitializerCursorSuccessPage<K, T> = core.initializerSuccessPage,
        noinline initErrorState: InitializerCursorErrorPage<K, T> = core.initializerErrorPage,
    ): Pair<CursorBookmark<K>, CursorPageState<K, T>> {
        logger.debug(LogComponent.NAVIGATION) {
            "loadOrGetPageState: self=${hint.self} forceLoading=$forceLoading"
        }
        val cachedState: CursorPageState<K, T>? = cache.getStateOf(hint.self)
        if (!forceLoading && core.isFilledSuccessState(cachedState)) {
            return cachedCursor to cachedState
        }
        loading.invoke(cachedCursor, cachedState)
        return try {
            val loadResult: CursorLoadResult<K, T> = load.invoke(this, hint)
            val data: MutableList<T> = loadResult.data.let {
                if (core.isCapacityUnlimited) it else it.take(core.capacity)
            }.toMutableList()
            val resultCursor = loadResult.bookmark
            val resultState: CursorPageState<K, T> = core.coerceToCapacity(
                initSuccessState.invoke(
                    resultCursor,
                    data,
                    loadResult.metadata
                )
            )
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
                    cachedCursor,
                    data,
                    cachedState?.metadata
                )
            )
            cachedCursor to errorState
        }
    }

    private suspend fun persistSuccessState(
        cursor: CursorBookmark<K>,
        state: CursorPageState<K, T>
    ) {
        if (state is CursorPageState.Success<*, *>) core.persistentCache?.save(cursor, state)
    }

    /**
     * Launches a fire-and-forget refresh for all dirty cursors within the current
     * context window.
     */
    protected fun CoroutineScope.refreshDirtyCursorsInContext() {
        if (!cache.isStarted) return
        val start = core.startContextCursor ?: return
        val end = core.endContextCursor ?: return
        val dirty: List<K> = core.drainDirtyCursorsInRange(start, end) ?: return
        val bookmarks = dirty.mapNotNull { cache.getCursorOf(it) }
        if (bookmarks.isEmpty()) return
        logger.debug(LogComponent.LIFECYCLE) { "refreshDirtyCursorsInContext: ${bookmarks.map { it.self }}" }
        launch { refresh(cursors = bookmarks, loadingSilently = true) }
    }

    /**
     * Synchronises [bookmarkIndex] so that it sits right after the last bookmark
     * whose `self` key was reached during navigation.
     */
    internal fun syncBookmarkIndex(cursor: CursorBookmark<K>) {
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

    private class CursorTransactionSavepoint<K : Any, T>(
        val states: List<Pair<CursorBookmark<K>, CursorPageState<K, T>>>,
        val startContextCursor: CursorBookmark<K>?,
        val endContextCursor: CursorBookmark<K>?,
        val capacity: Int,
        val dirtyCursors: Set<K>,
        val bookmarks: List<CursorBookmark<K>>,
        val bookmarkIndex: Int,
        val recyclingBookmark: Boolean,
        val initialCursor: CursorBookmark<K>?,
        val lockJump: Boolean,
        val lockGoNextPage: Boolean,
        val lockGoPreviousPage: Boolean,
        val lockRestart: Boolean,
        val lockRefresh: Boolean,
    )

    private fun createSavepoint(): CursorTransactionSavepoint<K, T> {
        val statesCopy: List<Pair<CursorBookmark<K>, CursorPageState<K, T>>> =
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

    open suspend fun <R> transaction(block: suspend CursorPaginator<K, T>.() -> R): R {
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

    private fun rollback(savepoint: CursorTransactionSavepoint<K, T>) {
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
        core.markCacheReplaced()

        core.snapshot()
        if (core.enableCacheFlow) core.repeatCacheFlow()
    }

    // ── Serialization ───────────────────────────────────────────────────────

    suspend fun saveState(
        selfEncoder: (K) -> JsonElement,
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
        selfDecoder: (JsonElement) -> K,
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
                bookmarks.add(CursorBookmark<K>(prev = prevKey, self = selfKey, next = nextKey))
            }
            bookmarkIndex = snapshot.bookmarkIndex.coerceIn(0, bookmarks.size)
            recyclingBookmark = snapshot.recyclingBookmark

            initialCursor = snapshot.initialCursorSelf?.let(selfDecoder)?.let { self ->
                CursorBookmark<K>(
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

    operator fun iterator(): Iterator<CursorPageState<K, T>> = core.iterator()

    operator fun contains(self: K): Boolean = cache.getStateOf(self) != null

    operator fun contains(pageState: CursorPageState<K, T>): Boolean {
        // PageState.page is synthetic here; equality goes by id (see PageState.equals).
        for (state in core.states) if (state == pageState) return true
        return false
    }

    operator fun get(self: K): CursorPageState<K, T>? = cache.getStateOf(self)

    operator fun get(self: K, index: Int): T? = cache.getElement(self, index)

    override fun toString(): String = "CursorPaginator(cache=$cache, bookmarks=$bookmarks)"

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = super.hashCode()

    // Kept (empty) for binary compatibility: it used to hold the restart sentinels.
    companion object
}
