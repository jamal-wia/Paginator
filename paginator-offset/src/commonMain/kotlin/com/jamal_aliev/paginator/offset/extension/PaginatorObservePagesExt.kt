// The deprecated L2 persistent cache is only probed here to warn about it.
@file:Suppress("DEPRECATION")

package com.jamal_aliev.paginator.offset.extension

import com.jamal_aliev.paginator.core.logger.LogComponent
import com.jamal_aliev.paginator.core.logger.warn
import com.jamal_aliev.paginator.offset.MutablePaginator
import com.jamal_aliev.paginator.offset.Paginator
import com.jamal_aliev.paginator.offset.cache.PagingCache
import com.jamal_aliev.paginator.offset.load.LoadResult
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Backs every cached page with its own live query: the paginator subscribes to [source]`(page)`
 * when it loads a page and unsubscribes when the page leaves the cache.
 *
 * This is the bridge for a local database that can observe one page at a time — typically a Room
 * or SQLDelight query with `LIMIT` / `OFFSET` that re-emits whenever its table changes:
 *
 * ```kotlin
 * @Query("SELECT * FROM users ORDER BY name LIMIT :limit OFFSET :offset")
 * fun observeUsers(limit: Int, offset: Int): Flow<List<User>>
 *
 * val job = paginator.observePages(viewModelScope) { page ->
 *     dao.observeUsers(limit = core.capacity, offset = (page - 1) * core.capacity)
 * }
 * ```
 *
 * Navigation stays exactly the same (`jump`, `goNextPage`, prefetch, `refresh`, …) — only the data
 * of each page now comes from its live query instead of a one-shot `load`. Nothing subscribes to
 * the whole table, so this scales to databases far larger than the cache.
 *
 * ## How a page lives
 *
 * 1. **Load = subscribe.** While the returned [Job] is active, the paginator's
 *    [load][Paginator.load] is routed through [source]: loading page `N` (by any navigation call)
 *    subscribes to `source(N)` and uses its **first** emission as the load result, so the page goes
 *    through the normal Progress → Success pipeline. A flow that fails before emitting turns the
 *    page into an Error page, exactly as a throwing `load` would. Reloading a page (`refresh`,
 *    dirty-page refresh, a retry after an error) re-subscribes.
 * 2. **Emissions = updates.** Every later emission replaces the page's data in place — the page
 *    keeps its id, class and (unless the emission brings new metadata) its metadata, and the data is
 *    trimmed to [capacity][com.jamal_aliev.paginator.offset.PagingCore.capacity]. A live update
 *    produces the same state a fresh load would: an emptied page becomes an empty Success page, a
 *    page of the window that shrank re-anchors the window like a CRUD removal. Updates pending
 *    together are applied as one batch with a single snapshot.
 * 3. **Leaving the cache = unsubscribe.** A page's query lives as long as the page is in the
 *    cache: eviction strategies, `restart`, `release`, `removeState`, transaction rollback and
 *    capacity changes all end (or, for `resize`, restart) the affected subscriptions — silent
 *    calls included, since the subscriptions are reconciled after every finished operation. A
 *    cancelled load keeps its page live.
 * 4. **Pages that entered the cache without a load** — restored by `restoreState`, promoted from
 *    the deprecated L2 cache, created by CRUD overflow, or loaded before this call — are subscribed
 *    on the fly, so every cached Success page is live.
 * 5. **Wholesale replacement = resubscribe.** `restoreState` (also with `silently = true`), a
 *    transaction rollback and a relabelling `removeState` leave pages with data their queries
 *    never emitted; every
 *    live page is then re-queried, so a recreated paginator restored from a saved state shows
 *    the database's current content, not the saved one.
 *
 * If a page's flow fails **after** its first emission, [onError] is called and the page is marked
 * dirty: it keeps its last data and is reloaded — and re-subscribed — by the next navigation that
 * reaches it. A flow that completes normally simply stops updating its page. Nothing escapes into
 * [scope]: one page's failure never cancels the others, and an [onError] that throws is logged.
 *
 * ## Coexistence with the rest of the library
 *
 * - Every cache access (live writes and bookkeeping) holds the paginator's navigation lock, so it
 *   never interleaves with a navigation call and is safe on multi-threaded dispatchers; an update
 *   that lands while `refresh` is loading the same page wins over the refresh's older result.
 * - The database is the source of truth: element-level CRUD on a live page is overwritten by the
 *   page's next emission. Change the database, not the paginator.
 * - `observePages` and [observe] are mutually exclusive on one paginator — both drive the cached
 *   pages from a reactive source. Starting one while the other is active throws
 *   [IllegalStateException].
 * - The deprecated L2 persistent cache is redundant (and unsupported) here; a warning is logged
 *   when one is attached.
 * - Each page is an independent query, so after a change that shifts rows across pages the
 *   neighbouring pages update when their own queries answer; until then the same row can appear
 *   on two adjacent pages. Batching merges only emissions already pending together — keep that in
 *   mind if a lazy list uses item keys.
 *
 * ## Lifecycle
 *
 * Cancel the returned [Job] to stop: every page subscription is cancelled and the paginator's
 * original [load][Paginator.load] is restored (unless it was replaced in the meantime); a load that
 * was waiting for its first emission falls back to that original `load`. The Job is a child of
 * [scope], so cancelling the scope (e.g. `viewModelScope`) stops it too.
 *
 * @param scope The scope that owns the page subscriptions.
 * @param onError Invoked when a page's flow fails after its first emission (or a live update cannot
 *   be applied). Defaults to a warning via the paginator's [logger][Paginator.logger].
 * @param source Opens the live query of one page. The receiver is the paginator, like in `load`.
 *   Use [observePageResults] when the query also produces page metadata.
 * @return The Job that owns every page subscription. Cancel it to stop observing.
 * @throws IllegalStateException If [observe] or another `observePages` is already active on this
 *   paginator.
 */
fun <T> MutablePaginator<T>.observePages(
    scope: CoroutineScope,
    onError: ((page: Int, error: Throwable) -> Unit)? = null,
    source: Paginator<T>.(page: Int) -> Flow<List<T>>,
): Job = observePageResults(scope = scope, onError = onError) { page: Int ->
    source(page).map { data: List<T> -> LoadResult(data) }
}

/**
 * [observePages] for a source that also carries page metadata (a total count, a server cursor, …):
 * each emission is a [LoadResult], whose metadata — when non-null — replaces the page's metadata.
 * Everything else behaves exactly like [observePages].
 *
 * @see observePages
 */
fun <T> MutablePaginator<T>.observePageResults(
    scope: CoroutineScope,
    onError: ((page: Int, error: Throwable) -> Unit)? = null,
    source: Paginator<T>.(page: Int) -> Flow<LoadResult<T>>,
): Job {
    val paginator: MutablePaginator<T> = this
    val job = Job(parent = scope.coroutineContext[Job])
    // Nothing has been launched on the job yet, so a refused install cancels it without effects.
    paginator.claimReactiveSlot(job = job, caller = "observePages()")
    if (paginator.core.persistentCache != null) {
        paginator.logger.warn(LogComponent.LIFECYCLE) {
            "observePages: this paginator has a PersistentPagingCache (L2) attached. " +
                    "The observed database already is the source of truth; L2 is redundant here, " +
                    "deprecated since 10.3.0 and not supported together with reactive sources. Remove it."
        }
    }

    val livePages = LivePages(
        paginator = paginator,
        scope = CoroutineScope(scope.coroutineContext + job),
        source = source,
        onError = onError ?: { page: Int, error: Throwable ->
            paginator.logger.warn(LogComponent.LIFECYCLE) {
                "observePages: page=$page source error: $error"
            }
        },
    )

    val originalLoad: suspend Paginator<T>.(page: Int) -> LoadResult<T> = paginator.load
    val liveLoad: suspend Paginator<T>.(page: Int) -> LoadResult<T> = { page: Int ->
        if (!job.isActive) {
            originalLoad(page)
        } else {
            try {
                livePages.load(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // observePages was cancelled while this load waited for the page's first emission:
                // the subscription died with it, so finish the load the way it would run now.
                if (!job.isActive) originalLoad(page) else throw e
            }
        }
    }
    paginator.load = liveLoad
    job.invokeOnCompletion {
        if (paginator.load === liveLoad) paginator.load = originalLoad
    }

    livePages.start()
    return job
}

/**
 * The per-page subscriptions behind [observePages].
 *
 * Cache access: every read and live write here uses [PagingCache.peekStateOf] /
 * [PagingCache.setStateUntracked] — bookkeeping and database emissions are not user access, so
 * they must not move pages in an LRU order or restart a TTL timer.
 *
 * Locking: [registryLock] guards [subscriptions], [pending] and [failed]. **Every cache access
 * holds the paginator's navigation lock**, always taken before [registryLock]. The live [load] is
 * the one entry point that may run without it (refresh loads its pages outside the lock) — and
 * while holding it (jump / goNextPage / …), so it must neither take the lock nor touch the cache:
 * it only registers the subscription; the cache-dependent bookkeeping runs in [reconcile] and
 * [applyPending], which hold the lock.
 *
 * Nothing thrown inside these coroutines escapes into the caller's scope: a failing source or
 * callback is reported, never allowed to cancel the other pages' queries.
 */
private class LivePages<T>(
    private val paginator: MutablePaginator<T>,
    private val scope: CoroutineScope,
    private val source: Paginator<T>.(page: Int) -> Flow<LoadResult<T>>,
    private val onError: (page: Int, error: Throwable) -> Unit,
) {

    /**
     * One page's live query. Stays registered after its flow fails or completes, as a tombstone: a
     * dead source is not resubscribed in a loop — only a reload of the page, its removal from the
     * cache or a capacity change clears it.
     */
    private class Subscription(val capacity: Int) {
        lateinit var job: Job
    }

    private val registryLock = Mutex()
    private val subscriptions: MutableMap<Int, Subscription> = HashMap()
    private val pending: MutableMap<Int, Pair<Subscription, LoadResult<T>>> = LinkedHashMap()

    /** Pages whose query failed after its first emission; [applyPending] marks them dirty. */
    private val failed: MutableSet<Int> = HashSet()
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        scope.launch {
            for (signal in wakeUp) guarded { applyPending() }
        }
        // Pages cached before observePages() was called become live right away.
        scope.launch { guarded { reconcile() } }
        // Any finished operation that may have changed which pages are cached — navigation (also
        // silent), CRUD, evictions inside them — and any published snapshot.
        scope.launch {
            onEveryChange(paginator.core.cacheChanges) { guarded { reconcile() } }
        }
        scope.launch {
            paginator.core.snapshot.collect { guarded { reconcile() } }
        }
        // restoreState (silent or not), a transaction rollback, release or a removeState that
        // relabelled pages replace cached data wholesale: a page may now hold data its query never
        // emitted, and the query will not emit again until the table changes. Re-run every query.
        scope.launch {
            onEveryChange(paginator.core.cacheReplacements) { guarded { resubscribe() } }
        }
    }

    /** Runs [action] for every value of [counter] other than the one it holds right now. */
    private suspend fun onEveryChange(counter: StateFlow<Long>, action: suspend () -> Unit) {
        var seen: Long = counter.value
        counter.collect { value: Long ->
            if (value == seen) return@collect
            seen = value
            action()
        }
    }

    /** The paginator's `load` while observing: (re)subscribes [page] and awaits its first emission. */
    suspend fun load(page: Int): LoadResult<T> {
        val first = CompletableDeferred<LoadResult<T>>()
        val subscription: Subscription = registryLock.withLock { subscribeLocked(page, first) }
        try {
            return first.await()
        } catch (e: CancellationException) {
            // The load was cancelled: the navigation rolls the page back to its previous state.
            // Drop the half-started query instead of leaving it as a tombstone, so the next
            // reconcile subscribes the restored page again.
            withContext(NonCancellable) {
                registryLock.withLock {
                    if (subscriptions[page] === subscription) {
                        subscriptions.remove(page)
                        pending.remove(page)
                    }
                }
            }
            subscription.job.cancel()
            throw e
        }
    }

    private suspend fun reconcile() {
        paginator.withNavigationLock { registryLock.withLock { reconcileLocked() } }
    }

    private suspend fun resubscribe() {
        paginator.withNavigationLock { registryLock.withLock { resubscribeLocked() } }
    }

    /**
     * Brings [subscriptions] in line with the cache: drops the queries of pages that left it (or
     * were opened for another capacity) and subscribes cached Success pages that have none.
     * Requires the navigation lock.
     */
    private fun reconcileLocked() {
        val cache = paginator.cache
        val capacity: Int = paginator.core.capacity
        val iterator = subscriptions.entries.iterator()
        while (iterator.hasNext()) {
            val (page, subscription) = iterator.next()
            if (cache.peekStateOf(page) == null || subscription.capacity != capacity) {
                subscription.job.cancel()
                pending.remove(page)
                iterator.remove()
            }
        }
        for (page in cache.pages) {
            if (page !in subscriptions && cache.peekStateOf(page) is OffsetPageState.Success) {
                subscribeLocked(page, first = null)
            }
        }
    }

    /**
     * Restarts the query of every cached Success page, so its first emission brings the page back
     * in line with the database. Pages still loading keep their subscription — the pending load
     * already takes a fresh first emission. Requires the navigation lock.
     */
    private fun resubscribeLocked() {
        val cache = paginator.cache
        val iterator = subscriptions.entries.iterator()
        while (iterator.hasNext()) {
            val (page, subscription) = iterator.next()
            if (cache.peekStateOf(page) is OffsetPageState.Success) {
                subscription.job.cancel()
                pending.remove(page)
                iterator.remove()
            }
        }
        reconcileLocked()
    }

    /**
     * Replaces [page]'s subscription with a fresh one. With [first], the first emission completes
     * it (a load is waiting); without, every emission — the first included — is a live update.
     * Touches no cache state, so it is safe without the navigation lock.
     */
    private fun subscribeLocked(page: Int, first: CompletableDeferred<LoadResult<T>>?): Subscription {
        subscriptions.remove(page)?.job?.cancel()
        pending.remove(page)
        failed.remove(page)
        val subscription = Subscription(capacity = paginator.core.capacity)
        subscription.job = scope.launch { collect(page, subscription, first) }
        // Also covers a job that never ran (scope already cancelled), so a waiting load never hangs.
        subscription.job.invokeOnCompletion {
            first?.completeExceptionally(
                IllegalStateException("observePages: the source of page $page ended before emitting")
            )
        }
        subscriptions[page] = subscription
        return subscription
    }

    private suspend fun collect(
        page: Int,
        subscription: Subscription,
        first: CompletableDeferred<LoadResult<T>>?,
    ) {
        var delivered = false
        try {
            source.invoke(paginator, page).collect { result: LoadResult<T> ->
                if (!delivered) {
                    delivered = true
                    if (first != null) {
                        first.complete(result)
                        return@collect
                    }
                }
                val isCurrent: Boolean = registryLock.withLock {
                    (subscriptions[page] === subscription).also { current ->
                        if (current) pending[page] = subscription to result
                    }
                }
                if (isCurrent) wakeUp.trySend(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (first != null && !delivered) {
                first.completeExceptionally(e)
            } else {
                // Keep the last data visible but have the next navigation reload — and so
                // re-subscribe — the page; marking it dirty touches the core, so the applier does it.
                val isCurrent: Boolean = registryLock.withLock {
                    (subscriptions[page] === subscription).also { current -> if (current) failed += page }
                }
                if (isCurrent) wakeUp.trySend(Unit)
                report(page, e)
            }
        }
    }

    /** Writes every pending emission into the cache as one batch with a single snapshot. */
    private suspend fun applyPending() {
        paginator.withNavigationLock {
            var failedPages: List<Int> = emptyList()
            val batch: List<Pair<Int, LoadResult<T>>> = registryLock.withLock {
                reconcileLocked()
                failedPages = failed.toList()
                failed.clear()
                pending.map { (page, entry) -> page to entry.second }
                    .also { pending.clear() }
            }
            for (page in failedPages) {
                if (paginator.cache.peekStateOf(page) != null) paginator.core.markDirty(page)
            }
            if (batch.isNotEmpty()) applyLocked(batch)
        }
    }

    private fun applyLocked(batch: List<Pair<Int, LoadResult<T>>>) {
        val core = paginator.core
        val cache = paginator.cache
        val changedPages = mutableListOf<Int>()
        for ((page, result) in batch) {
            try {
                val current: OffsetPageState<T> = cache.peekStateOf(page) ?: continue
                val data: List<T> = core.coerceToCapacity(result.data.toMutableList())
                val metadata = result.metadata ?: current.metadata
                val updated: OffsetPageState<T> =
                    if (current is OffsetPageState.Success) {
                        if (current.data == data && current.metadata == metadata) continue
                        current.copy(data = data, metadata = metadata)
                    } else {
                        // A refresh of this page is in flight (its Progress state). A new state with
                        // a new id makes the refresh drop its older result instead of overwriting.
                        core.initializerSuccessPage(page, data, metadata)
                    }
                cache.setStateUntracked(updated, silently = true)
                changedPages += page
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(page, e)
            }
        }
        if (changedPages.isEmpty()) return

        val startBefore: Int = cache.startContextPage
        val endBefore: Int = cache.endContextPage
        if (cache.isStarted) {
            val windowFilled: Boolean = (startBefore..endBefore).all { page ->
                core.isFilledSuccessState(cache.peekStateOf(page))
            }
            if (!windowFilled) {
                // A page of the window shrank or emptied — at an edge or inside. Re-anchor like a
                // CRUD removal does, so a non-filled page never stays buried inside the window.
                core.findNearContextPage(startPoint = startBefore, endPoint = endBefore)
            } else {
                // An edge may have grown into a full page: absorb the filled neighbours.
                core.expandStartContextPage(cache.peekStateOf(startBefore))
                core.expandEndContextPage(cache.peekStateOf(endBefore))
            }
        }
        if (core.enableCacheFlow) core.repeatCacheFlow()

        val windowMoved: Boolean =
            cache.startContextPage != startBefore || cache.endContextPage != endBefore
        val visibleRange: IntRange? = run {
            val first = paginator.walkBackwardWhile(core[cache.startContextPage]) ?: return@run null
            val last = paginator.walkForwardWhile(core[cache.endContextPage]) ?: return@run null
            first.page..last.page
        }
        if (windowMoved || (visibleRange != null && changedPages.any { it in visibleRange })) {
            core.snapshot()
        }
    }

    /** Hands [error] to `onError`; a callback that throws is logged, never propagated. */
    private fun report(page: Int, error: Throwable) {
        try {
            onError(page, error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            paginator.logger.warn(LogComponent.LIFECYCLE) {
                "observePages: onError threw for page=$page: $e (source error: $error)"
            }
        }
    }

    /** Runs internal bookkeeping so that an unexpected failure is logged instead of escaping. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            paginator.logger.warn(LogComponent.LIFECYCLE) { "observePages: internal error: $e" }
        }
    }
}
