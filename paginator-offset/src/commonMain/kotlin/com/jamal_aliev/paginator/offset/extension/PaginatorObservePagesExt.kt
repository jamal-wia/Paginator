// The deprecated L2 persistent cache is only probed here to warn about it.
@file:Suppress("DEPRECATION")

package com.jamal_aliev.paginator.offset.extension

import com.jamal_aliev.paginator.core.extension.isEmptyState
import com.jamal_aliev.paginator.core.extension.isErrorState
import com.jamal_aliev.paginator.core.extension.isSuccessState
import com.jamal_aliev.paginator.core.logger.LogComponent
import com.jamal_aliev.paginator.core.logger.debug
import com.jamal_aliev.paginator.core.logger.warn
import com.jamal_aliev.paginator.offset.MutablePaginator
import com.jamal_aliev.paginator.offset.Paginator
import com.jamal_aliev.paginator.offset.cache.PagingCache
import com.jamal_aliev.paginator.offset.load.LoadResult
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Keeps every cached page live: once a page is in the cache, the paginator subscribes it to
 * [source]`(page)`, applies the query's emissions to the page, and unsubscribes when the page
 * leaves the cache.
 *
 * Pages are still loaded by the paginator's [load][Paginator.load] — `observePages` is an overlay,
 * like [observe]: `load` loads, `observePages` keeps the loaded data in line with the database.
 * This is the bridge for a local database that can observe one page at a time — typically a Room
 * or SQLDelight query with `LIMIT` / `OFFSET` that re-emits whenever its table changes:
 *
 * ```kotlin
 * @Query("SELECT * FROM users ORDER BY name LIMIT :limit OFFSET :offset")
 * fun observeUsers(limit: Int, offset: Int): Flow<List<User>>
 *
 * val source: Paginator<User>.(Int) -> Flow<List<User>> = { page ->
 *     dao.observeUsers(limit = core.capacity, offset = (page - 1) * core.capacity)
 * }
 * val paginator = MutablePaginator<User>(load = { page -> LoadResult(source(page).first()) })
 * val job = paginator.observePages(viewModelScope, source = source)
 * ```
 *
 * `load` may as well fetch the page from the network, write it into the database and return what
 * the database now holds — the live query then keeps it current. Nothing subscribes to the whole
 * table, so this scales to databases far larger than the cache.
 *
 * ## How a page lives
 *
 * 1. **Load, then subscribe.** A page is subscribed as soon as it has settled in the cache — a
 *    Success page (also an empty one) or an Error page — however it got there: a navigation call's
 *    load, `restoreState`, CRUD overflow, or a page cached before this call. The query's first
 *    emission is compared with the cached data: if it is equal, nothing happens (no snapshot); if
 *    it differs, it is applied like any later emission.
 * 2. **Emissions = updates.** Every emission replaces the page's data in place — the page keeps its
 *    id, class and (unless the emission brings new metadata) its metadata, and the data is trimmed
 *    to [capacity][com.jamal_aliev.paginator.offset.PagingCore.capacity]. An Error page stays an
 *    Error page with the database's rows, so the user still sees the retry. A page of the window
 *    that shrank re-anchors the window like a CRUD removal. Updates pending together are applied
 *    as one batch with a single snapshot.
 * 3. **Reloads keep the subscription.** While a page reloads (`refresh`, dirty-page refresh, a
 *    retry) its query keeps running, and its emissions are applied to the loading page in place —
 *    the reload still owns the page and commits its own result (data, metadata or error). After
 *    the commit the query either *adopts* the result — its latest emission holds the same data, so
 *    nothing is re-queried — or is re-queried, and its first emission brings the database's view.
 * 4. **Leaving the cache = unsubscribe.** A page's query lives as long as the page is in the
 *    cache: eviction strategies, `restart`, `release`, `removeState` and transaction rollback end
 *    the affected subscriptions, a capacity change restarts them — silent calls included, since
 *    the subscriptions are reconciled after every finished operation.
 * 5. **Wholesale replacement = re-query.** `restoreState` (also with `silently = true`), a
 *    transaction rollback and a relabelling `removeState` leave pages with data their queries
 *    never emitted; every live page is then re-queried, so a paginator restored after process
 *    death shows the database's current content without calling `load`.
 *
 * If a page's flow fails, [onError] is called and the page is marked dirty: it keeps its data and
 * is reloaded (through `load`) — and re-subscribed — by the next navigation that reaches it. A flow
 * that completes normally simply stops updating its page. Nothing escapes into [scope]: one page's
 * failure never cancels the others, and an [onError] that throws is logged.
 *
 * ## `load` and the source should agree
 *
 * Right after a load, the query's first emission should equal what `load` returned — read the
 * page back from the database at the end of `load`. If it differs (e.g. `load` returned the
 * network response and the database orders or merges it differently), the page shows the load
 * result first and is then corrected to the database's view. Data equality uses `equals`, so give
 * `T` a meaningful one (a data class) — otherwise every first emission causes a redundant snapshot.
 *
 * ## Coexistence with the rest of the library
 *
 * - Every cache access (live writes and bookkeeping) holds the paginator's navigation lock, so it
 *   never interleaves with a navigation call and is safe on multi-threaded dispatchers.
 * - The database is the source of truth: element-level CRUD on a live page is overwritten by the
 *   page's next emission. Change the database, not the paginator.
 * - `observePages` and [observe] are mutually exclusive on one paginator — both drive the cached
 *   pages from a reactive source. Starting one while the other is active throws
 *   [IllegalStateException].
 * - The deprecated L2 persistent cache is redundant (and unsupported) here; a warning is logged
 *   when one is attached.
 * - Each page is an independent query, so after a change that shifts rows across pages the
 *   neighbouring pages update when their own queries answer; until then the same row can appear
 *   on two adjacent pages. Pass `settle` to apply such updates in consistent rounds instead.
 *
 * ## Lifecycle
 *
 * Cancel the returned [Job] to stop: every page subscription is cancelled and the pages keep their
 * last data; `load` goes on working as before. The Job is a child of [scope], so cancelling the
 * scope (e.g. `viewModelScope`) stops it too.
 *
 * @param scope The scope that owns the page subscriptions.
 * @param onError Invoked when a page's flow fails (or a live update cannot be applied). Defaults to
 *   a warning via the paginator's [logger][Paginator.logger].
 * @param settle Consistent updates across pages, off by default ([Duration.ZERO]). When positive,
 *   an emission opens a **round**: its update is held until every other live page has emitted too —
 *   a table change re-runs all of its queries, so with Room / SQLDelight each page answers — or
 *   until [settle] has passed, and the round is then applied as one batch. The screen never mixes a
 *   page that already saw the change with a neighbour that has not, so a row moving across pages
 *   is never shown twice or missed. The cost is latency: an update waits for the slowest query, at
 *   most [settle]. Pick a value a bit above your page query time (e.g. 100 ms). The first emission
 *   of a new subscription is not a table change and is applied without waiting for a round.
 * @param source Opens the live query of one page. The receiver is the paginator, like in `load`.
 *   Use [observePageResults] when the query also produces page metadata.
 * @return The Job that owns every page subscription. Cancel it to stop observing.
 * @throws IllegalStateException If [observe] or another `observePages` is already active on this
 *   paginator.
 */
fun <T> MutablePaginator<T>.observePages(
    scope: CoroutineScope,
    onError: ((page: Int, error: Throwable) -> Unit)? = null,
    settle: Duration = Duration.ZERO,
    source: Paginator<T>.(page: Int) -> Flow<List<T>>,
): Job = observePageResults(scope = scope, onError = onError, settle = settle) { page: Int ->
    source(page).map { data: List<T> -> LoadResult(data) }
}

/**
 * [observePages] for a source that also carries page metadata (a total count, a server cursor, …):
 * each emission is a [LoadResult]. An emission's metadata — when non-null — replaces the page's
 * metadata; a `null` one keeps the metadata the page was loaded with. Everything else behaves
 * exactly like [observePages].
 *
 * @see observePages
 */
fun <T> MutablePaginator<T>.observePageResults(
    scope: CoroutineScope,
    onError: ((page: Int, error: Throwable) -> Unit)? = null,
    settle: Duration = Duration.ZERO,
    source: Paginator<T>.(page: Int) -> Flow<LoadResult<T>>,
): Job {
    require(!settle.isNegative()) { "settle must not be negative, was $settle" }
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

    LivePages(
        paginator = paginator,
        scope = CoroutineScope(scope.coroutineContext + job),
        source = source,
        settle = settle,
        onError = onError ?: { page: Int, error: Throwable ->
            paginator.logger.warn(LogComponent.LIFECYCLE) {
                "observePages: page=$page source error: $error"
            }
        },
    ).start()
    return job
}

/**
 * The per-page subscriptions behind [observePages].
 *
 * Cache access: every read and live write here uses [PagingCache.peekStateOf] /
 * [PagingCache.setStateUntracked] — bookkeeping and database emissions are not user access, so
 * they must not move pages in an LRU order or restart a TTL timer.
 *
 * Locking: [registryLock] guards [subscriptions], [pending] and [failed] and the subscriptions'
 * bookkeeping fields. **Every cache access holds the paginator's navigation lock**, always taken
 * before [registryLock]; the collectors only touch the registry.
 *
 * Nothing thrown inside these coroutines escapes into the caller's scope: a failing source or
 * callback is reported, never allowed to cancel the other pages' queries.
 */
private class LivePages<T>(
    private val paginator: MutablePaginator<T>,
    private val scope: CoroutineScope,
    private val source: Paginator<T>.(page: Int) -> Flow<LoadResult<T>>,
    private val settle: Duration,
    private val onError: (page: Int, error: Throwable) -> Unit,
) {

    /**
     * One page's live query. Stays registered after its flow fails or completes, as a tombstone: a
     * dead source is not resubscribed in a loop — only a reload of the page (a new state id), its
     * removal from the cache or a capacity change clears it.
     */
    private class Subscription<T>(val capacity: Int, var syncedId: Long) {
        lateinit var job: Job

        /** Has delivered its first emission — it takes part in [settle] rounds. */
        var live: Boolean = false

        /** The latest emission, used to adopt a reload's result without re-querying. */
        var latest: LoadResult<T>? = null

        /** An emission was applied to the page while it was reloading (a Progress state). */
        var foldedWhileLoading: Boolean = false
    }

    /** An emission waiting for the applier; [first] marks a subscription's first emission. */
    private class Pending<T>(val subscription: Subscription<T>, val result: LoadResult<T>, val first: Boolean)

    private val registryLock = Mutex()
    private val subscriptions: MutableMap<Int, Subscription<T>> = HashMap()
    private val pending: MutableMap<Int, Pending<T>> = LinkedHashMap()

    /** Pages whose query failed; [applyPending] marks them dirty. */
    private val failed: MutableSet<Int> = HashSet()

    /**
     * The [PagingCore.cacheChanges] value and cache size the last reconcile saw. While both are
     * unchanged no operation has changed which pages are cached, so reconciling again would only
     * repeat the same O(pages) pass (a navigation call signals cacheChanges *and* publishes a
     * snapshot; every applied batch publishes one too).
     */
    private var reconciledAt: Long = -1
    private var reconciledSize: Int = -1
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        scope.launch {
            for (signal in wakeUp) {
                if (settle.isPositive()) guarded { awaitRound() }
                guarded { applyPending() }
            }
        }
        // Pages cached before observePages() was called become live right away.
        scope.launch { guarded { reconcile() } }
        // Any finished operation that may have changed which pages are cached or what they hold —
        // navigation (also silent), CRUD, evictions inside them — and any published snapshot.
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

    /**
     * Holds the pending updates until every live page has one — the round is complete — or until
     * [settle] has passed since the round opened. Signals consumed here (further emissions, a
     * failure) only re-check the round; the applier runs right after.
     */
    private suspend fun awaitRound() {
        withTimeoutOrNull(settle) {
            while (!registryLock.withLock { roundCompleteLocked() }) wakeUp.receive()
        }
    }

    private fun roundCompleteLocked(): Boolean {
        // First emissions catch a new subscription up; they are not a table change to wait for.
        if (pending.values.all { it.first }) return true
        return subscriptions.all { (page, subscription) ->
            !subscription.live || !subscription.job.isActive || page in pending
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

    private suspend fun reconcile() {
        paginator.withNavigationLock { registryLock.withLock { reconcileIfStaleLocked() } }
    }

    private fun reconcileIfStaleLocked() {
        if (paginator.core.cacheChanges.value != reconciledAt || paginator.cache.size != reconciledSize) {
            reconcileLocked()
        }
    }

    private suspend fun resubscribe() {
        paginator.withNavigationLock { registryLock.withLock { resubscribeLocked() } }
    }

    /** Whether [state] has settled — loaded (Success, also empty) or failed — rather than loading. */
    private fun isSettled(state: OffsetPageState<T>?): Boolean =
        state.isSuccessState() || state.isEmptyState() || state.isErrorState()

    /**
     * Brings [subscriptions] in line with the cache: drops the queries of pages that left it (or
     * were opened for another capacity), subscribes settled pages that have none, and syncs the
     * query of a page that something else rewrote (see [syncLocked]). A loading page keeps its
     * query and gets none. Requires the navigation lock.
     */
    private fun reconcileLocked() {
        val cache = paginator.cache
        reconciledAt = paginator.core.cacheChanges.value
        reconciledSize = cache.size
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
            val state: OffsetPageState<T> = cache.peekStateOf(page) ?: continue
            if (!isSettled(state)) continue
            val subscription: Subscription<T>? = subscriptions[page]
            if (subscription == null) subscribeLocked(page, state) else syncLocked(page, state, subscription)
        }
    }

    /**
     * Live writes keep a page's id, so a new id means someone else wrote the page — a load's commit,
     * a CRUD overflow page, a direct `setState` — or a reload rolled back over data this query had
     * folded into it. The query then either adopts the page (its latest emission already holds the
     * same data) or is re-queried, and its first emission brings the database's view.
     */
    private fun syncLocked(page: Int, state: OffsetPageState<T>, subscription: Subscription<T>) {
        if (state.id == subscription.syncedId && !subscription.foldedWhileLoading) return
        val latest: LoadResult<T>? = subscription.latest
        when {
            // A dead query (tombstone) is restarted only by a reload, i.e. a new id.
            !subscription.job.isActive ->
                if (state.id != subscription.syncedId) subscribeLocked(page, state)

            // Not delivered yet: its first emission is compared with whatever the page holds then.
            !subscription.live -> subscription.syncedId = state.id

            latest != null && agrees(latest, state) -> {
                subscription.syncedId = state.id
                subscription.foldedWhileLoading = false
            }

            else -> subscribeLocked(page, state)
        }
    }

    /** Whether applying [result] to [state] would leave it unchanged. */
    private fun agrees(result: LoadResult<T>, state: OffsetPageState<T>): Boolean =
        paginator.core.coerceToCapacity(result.data.toMutableList()) == state.data &&
                (result.metadata == null || result.metadata == state.metadata)

    /**
     * Restarts the query of every settled page, so its first emission brings the page back in line
     * with the database. A loading page keeps its query; its reload is synced once it commits.
     * Requires the navigation lock.
     */
    private fun resubscribeLocked() {
        val cache = paginator.cache
        for (page in cache.pages) {
            val state: OffsetPageState<T> = cache.peekStateOf(page) ?: continue
            if (isSettled(state)) subscribeLocked(page, state)
        }
        reconcileLocked()
    }

    /**
     * Replaces [page]'s subscription with a fresh one, synced with [state]. Every emission — the
     * first included — is an update, the first one compared with what the page holds then.
     */
    private fun subscribeLocked(page: Int, state: OffsetPageState<T>) {
        subscriptions.remove(page)?.job?.cancel()
        pending.remove(page)
        failed.remove(page)
        val subscription = Subscription<T>(capacity = paginator.core.capacity, syncedId = state.id)
        subscription.job = scope.launch { collect(page, subscription) }
        subscriptions[page] = subscription
    }

    private suspend fun collect(page: Int, subscription: Subscription<T>) {
        try {
            source.invoke(paginator, page).collect { result: LoadResult<T> ->
                val isCurrent: Boolean = registryLock.withLock {
                    (subscriptions[page] === subscription).also { current ->
                        if (!current) return@also
                        val first: Boolean = !subscription.live
                        subscription.live = true
                        subscription.latest = result
                        // A first emission still waiting keeps its mark: it is not a table change.
                        val stillFirst: Boolean = first || pending[page]?.first == true
                        pending[page] = Pending(subscription, result, stillFirst)
                    }
                }
                if (isCurrent) wakeUp.trySend(Unit)
            }
        } catch (e: Throwable) {
            // A CancellationException is ours only if this coroutine is being cancelled; one thrown
            // by the source while we are still active (withTimeout, Flow.timeout) is a failure.
            if (e is CancellationException && !currentCoroutineContext().isActive) throw e
            // Keep the page's data visible but have the next navigation reload — and so
            // re-subscribe — it; marking it dirty touches the core, so the applier does it.
            val isCurrent: Boolean = registryLock.withLock {
                (subscriptions[page] === subscription).also { current -> if (current) failed += page }
            }
            if (isCurrent) wakeUp.trySend(Unit)
            report(page, e)
        }
    }

    /** Writes every pending emission into the cache as one batch with a single snapshot. */
    private suspend fun applyPending() {
        paginator.withNavigationLock {
            registryLock.withLock {
                reconcileIfStaleLocked()
                for (page in failed) {
                    if (paginator.cache.peekStateOf(page) != null) paginator.core.markDirty(page)
                }
                failed.clear()
                val batch: List<Pair<Int, Pending<T>>> = pending.toList()
                pending.clear()
                if (batch.isNotEmpty()) applyLocked(batch)
            }
        }
    }

    private fun applyLocked(batch: List<Pair<Int, Pending<T>>>) {
        val core = paginator.core
        val cache = paginator.cache
        val changedPages = mutableListOf<Int>()
        for ((page, entry) in batch) {
            try {
                val current: OffsetPageState<T> = cache.peekStateOf(page) ?: continue
                val subscription: Subscription<T> = entry.subscription
                val data: List<T> = core.coerceToCapacity(entry.result.data.toMutableList())
                val metadata = entry.result.metadata ?: current.metadata
                val settled: Boolean = isSettled(current)
                if (settled) {
                    subscription.syncedId = current.id
                    subscription.foldedWhileLoading = false
                }
                if (current.data == data && current.metadata == metadata) continue
                if (entry.first && settled) {
                    paginator.logger.debug(LogComponent.LIFECYCLE) {
                        "observePages: page=$page the database differs from what was loaded; applying it"
                    }
                }
                // copy keeps the id: a reload in flight (a Progress state) still owns the page and
                // commits its own result, after which the query adopts it or is re-queried.
                if (!settled) subscription.foldedWhileLoading = true
                cache.setStateUntracked(current.copy(data = data, metadata = metadata), silently = true)
                changedPages += page
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(page, e)
            }
        }
        if (changedPages.isEmpty()) return

        // The window fix and the snapshot below read pages through the core; none of it is a use
        // of those pages, so it must not reshape an LRU order or restart TTL timers.
        core.withUntrackedReads { fixWindowAndPublish(changedPages) }
    }

    private fun fixWindowAndPublish(changedPages: List<Int>) {
        val core = paginator.core
        val cache = paginator.cache
        val startBefore: Int = cache.startContextPage
        val endBefore: Int = cache.endContextPage
        if (cache.isStarted) {
            // Only a page that changed can have become non-filled; the edges are checked too
            // because the window may extend once an edge has grown into a full page.
            val window = startBefore..endBefore
            val windowFilled: Boolean = (changedPages.filter { it in window } + startBefore + endBefore)
                .all { page -> core.isFilledSuccessState(cache.peekStateOf(page)) }
            if (!windowFilled) {
                // A page of the window shrank or emptied — at an edge or inside. Re-anchor like a
                // refresh does, so a non-filled page never stays buried inside the window (and an
                // emptied table shows the empty state instead of its deleted rows).
                core.reanchorAfterRewrite(startPoint = startBefore, endPoint = endBefore)
                // The window moved to a new position: realign the bookmark cursor so jumpForward /
                // jumpBack resume from here, as after a refresh that re-anchors.
                if (cache.isStarted && cache.startContextPage != startBefore) {
                    paginator.syncBookmarkIndex(cache.startContextPage)
                }
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
