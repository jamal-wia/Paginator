// The deprecated L2 persistent cache is only probed here to warn about it.
@file:Suppress("DEPRECATION")

package com.jamal_aliev.paginator.offset.extension

import com.jamal_aliev.paginator.core.logger.LogComponent
import com.jamal_aliev.paginator.core.logger.warn
import com.jamal_aliev.paginator.offset.MutablePaginator
import com.jamal_aliev.paginator.offset.Paginator
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
 *    shrunk window edge is re-anchored like after a CRUD removal. Updates arriving close together
 *    are applied as one batch with a single snapshot.
 * 3. **Leaving the cache = unsubscribe.** A page's query lives exactly as long as the page is in
 *    the cache: eviction strategies, `restart`, `release`, `removeState`, transaction rollback and
 *    capacity changes all end (or, for `resize`, restart) the affected subscriptions.
 * 4. **Pages that entered the cache without a load** — restored by `restoreState`, promoted from
 *    the deprecated L2 cache, created by CRUD overflow, or loaded before this call — are subscribed
 *    on the fly, so every cached Success page is live.
 * 5. **Wholesale replacement = resubscribe.** `restoreState` (also with `silently = true`) and a
 *    transaction rollback replace the cached pages with data their queries never emitted; every
 *    live page is then re-queried, so a recreated paginator restored from a saved state shows
 *    the database's current content, not the saved one.
 *
 * If a page's flow fails **after** its first emission, [onError] is called and the page is marked
 * dirty: it keeps its last data and is reloaded — and re-subscribed — by the next navigation that
 * reaches it. A flow that completes normally simply stops updating its page.
 *
 * ## Coexistence with the rest of the library
 *
 * - All writes go through the paginator's navigation lock, so they never interleave with a
 *   navigation call; an update that lands while `refresh` is loading the same page wins over the
 *   refresh's older result.
 * - The database is the source of truth: element-level CRUD on a live page is overwritten by the
 *   page's next emission. Change the database, not the paginator.
 * - `observePages` and [observe] are mutually exclusive on one paginator — both drive the cached
 *   pages from a reactive source. Starting one while the other is active throws
 *   [IllegalStateException].
 * - The deprecated L2 persistent cache is redundant (and unsupported) here; a warning is logged
 *   when one is attached.
 * - Each page is an independent query, so right after a change that shifts rows across pages the
 *   neighbouring pages may update a few milliseconds apart. Batching keeps this window short, but
 *   while it lasts the same row can briefly appear on two adjacent pages — keep that in mind if a
 *   lazy list uses item keys.
 *
 * ## Lifecycle
 *
 * Cancel the returned [Job] to stop: every page subscription is cancelled and the paginator's
 * original [load][Paginator.load] is restored (unless it was replaced in the meantime). The Job is
 * a child of [scope], so cancelling the scope (e.g. `viewModelScope`) stops it too.
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
        if (job.isActive) livePages.load(page) else originalLoad(page)
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
 * Locking: [registryLock] guards [subscriptions] and [pending]. Cache writes additionally hold the
 * paginator's navigation lock, always taken **before** [registryLock] — the live `load` runs under
 * the navigation lock already (jump / goNextPage / …), so the reverse order would deadlock.
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
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        scope.launch {
            for (signal in wakeUp) applyPending()
        }
        // The snapshot is a StateFlow, so this also runs once right away: pages cached before
        // observePages() was called become live immediately.
        scope.launch {
            paginator.core.snapshot.collect { registryLock.withLock { reconcileLocked() } }
        }
        // restoreState (silent or not), a transaction rollback or release replace the cached
        // pages wholesale: a page may now hold restored data its query never emitted, and the
        // query will not emit again until the table changes. Re-run every live query.
        val replacementsAtStart: Long = paginator.core.cacheReplacements.value
        scope.launch {
            var seen: Long = replacementsAtStart
            paginator.core.cacheReplacements.collect { replacements: Long ->
                if (replacements == seen) return@collect
                seen = replacements
                registryLock.withLock { resubscribeLocked() }
            }
        }
    }

    /** The paginator's `load` while observing: (re)subscribes [page] and awaits its first emission. */
    suspend fun load(page: Int): LoadResult<T> {
        val first = CompletableDeferred<LoadResult<T>>()
        val subscription: Subscription = registryLock.withLock {
            reconcileLocked()
            subscribeLocked(page, first)
        }
        try {
            return first.await()
        } catch (e: CancellationException) {
            subscription.job.cancel()
            throw e
        }
    }

    /**
     * Brings [subscriptions] in line with the cache: drops the queries of pages that left it (or
     * were opened for another capacity) and subscribes cached Success pages that have none.
     */
    private fun reconcileLocked() {
        val cache = paginator.cache
        val capacity: Int = paginator.core.capacity
        val iterator = subscriptions.entries.iterator()
        while (iterator.hasNext()) {
            val (page, subscription) = iterator.next()
            if (cache.getStateOf(page) == null || subscription.capacity != capacity) {
                subscription.job.cancel()
                pending.remove(page)
                iterator.remove()
            }
        }
        for (page in cache.pages) {
            if (page !in subscriptions && cache.getStateOf(page) is OffsetPageState.Success) {
                subscribeLocked(page, first = null)
            }
        }
    }

    /**
     * Restarts the query of every cached Success page, so its first emission brings the page back
     * in line with the database. Pages still loading keep their subscription — the pending load
     * already takes a fresh first emission.
     */
    private fun resubscribeLocked() {
        val cache = paginator.cache
        val iterator = subscriptions.entries.iterator()
        while (iterator.hasNext()) {
            val (page, subscription) = iterator.next()
            if (cache.getStateOf(page) is OffsetPageState.Success) {
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
     */
    private fun subscribeLocked(page: Int, first: CompletableDeferred<LoadResult<T>>?): Subscription {
        subscriptions.remove(page)?.job?.cancel()
        pending.remove(page)
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
                onError(page, e)
                // Keep the last data visible but have the next navigation reload — and so
                // re-subscribe — the page.
                if (paginator.cache.getStateOf(page) != null) paginator.core.markDirty(page)
            }
        }
    }

    /** Writes every pending emission into the cache as one batch with a single snapshot. */
    private suspend fun applyPending() {
        paginator.withNavigationLock {
            val batch: List<Pair<Int, LoadResult<T>>> = registryLock.withLock {
                reconcileLocked()
                pending.map { (page, entry) -> page to entry.second }
                    .also { pending.clear() }
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
                val current: OffsetPageState<T> = cache.getStateOf(page) ?: continue
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
                cache.setState(updated, silently = true)
                changedPages += page
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(page, e)
            }
        }
        if (changedPages.isEmpty()) return

        val startBefore: Int = cache.startContextPage
        val endBefore: Int = cache.endContextPage
        if (cache.isStarted) {
            val startState: OffsetPageState<T>? = cache.getStateOf(startBefore)
            val endState: OffsetPageState<T>? = cache.getStateOf(endBefore)
            if (!core.isFilledSuccessState(startState) || !core.isFilledSuccessState(endState)) {
                // A window edge shrank or emptied: re-anchor exactly like a CRUD removal does.
                core.findNearContextPage(startPoint = startBefore, endPoint = endBefore)
            } else {
                // An edge may have grown into a full page: absorb the filled neighbours.
                core.expandStartContextPage(startState)
                core.expandEndContextPage(endState)
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
}
