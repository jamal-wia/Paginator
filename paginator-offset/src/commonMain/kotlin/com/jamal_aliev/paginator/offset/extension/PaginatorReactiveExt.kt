// The deprecated L2 persistent cache is still wired through here until it is removed.
@file:Suppress("DEPRECATION")

package com.jamal_aliev.paginator.offset.extension

import com.jamal_aliev.paginator.core.cache.reactive.InitialSyncPolicy
import com.jamal_aliev.paginator.core.cache.reactive.InsertPosition
import com.jamal_aliev.paginator.core.cache.reactive.PaginatorReactiveCache
import com.jamal_aliev.paginator.core.cache.reactive.ReactiveEvent
import com.jamal_aliev.paginator.core.cache.reactive.UnknownItemPolicy
import com.jamal_aliev.paginator.core.extension.isProgressState
import com.jamal_aliev.paginator.core.logger.LogComponent
import com.jamal_aliev.paginator.core.logger.warn
import com.jamal_aliev.paginator.offset.MutablePaginator
import com.jamal_aliev.paginator.offset.defaultOverflowPageFactory
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Subscribes [source] to this paginator so that changes in the underlying
 * reactive data source flow into the L1 cache as point CRUD operations
 * instead of triggering fresh `load()` calls.
 *
 * This is the recommended way to bridge Room/SQLDelight/Realm DAOs (or any
 * other reactive source: WebSocket, Server-Sent Events, in-app event bus,
 * Realm sync, …) to a paginator. Subscribing to a reactive source from
 * *inside* the `load` lambda is an antipattern — the first emission triggers
 * a navigation call (e.g. `refresh`) that deadlocks on the non-reentrant
 * navigation mutex already held by the outer load.
 *
 * ## What this function does, step by step
 *
 * 1. Subscribes to [source].changes(), buffering its events.
 * 2. Optionally issues `refreshAll()` to reconcile the L1 cache with any
 *    source mutations that landed between the most recent page load and the
 *    subscribe point (controlled by [initialSync]). When `cache.pages` is
 *    empty at subscribe time the reconciliation is skipped — there is
 *    nothing to refresh and the upcoming first `load()` will already see the
 *    latest source state. Events arriving meanwhile are applied after it.
 * 3. Translates each [ReactiveEvent] to the equivalent CRUD call on this
 *    paginator. Emissions are applied serially in arrival order, each under
 *    the paginator's navigation lock — neither the source nor the caller need
 *    to add their own synchronisation, and [scope] may use any dispatcher.
 *    A `Batch` is applied atomically: its rollback undoes only the batch.
 *    Requested `refreshAll()`s (`Invalidate`, [UnknownItemPolicy.RefreshAll])
 *    run once after the event or batch.
 *
 * The CRUD is bookkeeping, not user access: it does not reshape an LRU order
 * or renew TTLs. An event that changes a page a `refresh` is still loading
 * wins over the refresh's older result; the page is marked dirty instead.
 * After the cache is replaced wholesale (`restoreState`, a transaction
 * rollback) the subscription reconciles it with `refreshAll()`.
 *
 * ## Lifecycle
 *
 * Cancel the returned [Job] when the consumer (Activity, Composable,
 * ViewModel) goes away. Passing a `viewModelScope` keeps the subscription
 * alive for the lifetime of that ViewModel; passing a custom scope lets the
 * caller scope the subscription tighter.
 *
 * The returned Job is **not** a child of the paginator — the paginator does
 * not own this subscription's lifetime and cannot cancel it. This is
 * intentional: a paginator may outlive any single subscription.
 *
 * ## Single-subscription guard
 *
 * At most one observe() may be active per paginator at a time. Calling
 * observe() while a previous Job is still active fails fast with
 * [IllegalStateException] — two parallel subscriptions would double-apply
 * every event and the second one's `RefreshAll` initial sync would race the
 * first. Cancel the previous Job before subscribing again. A completed or
 * cancelled previous Job is not a problem; the slot clears itself on
 * completion. The slot is shared with [observePages] — the two bridges are
 * mutually exclusive on one paginator.
 *
 * ## Error handling
 *
 * An error while applying an event is routed to [onError] and the next event
 * is applied. A failing [source] — including a `CancellationException` of its
 * own, such as `withTimeout` — is routed to [onError] once per failure and
 * re-subscribed with exponential backoff (500 ms doubling up to 30 s),
 * followed by `refreshAll()` to reconcile what was missed. Only a source flow
 * that completes normally ends the Job. Pass `onError = { throw it }` if you
 * would rather have the Job fail on the first error: [onError] is invoked once
 * and its exception fails the Job.
 *
 * ## Persistent cache warning
 *
 * If the paginator has a [com.jamal_aliev.paginator.core.cache.persistent.PersistentPagingCache]
 * (L2) attached, this function logs a warning — the reactive source is
 * almost certainly the same database that L2 would cache, so L2 becomes
 * redundant. L2 is also deprecated since 10.3.0 and not supported together
 * with reactive sources — remove it in this configuration.
 *
 * @param scope The coroutine scope that owns the subscription's lifetime.
 * @param source The reactive cache adapter for the underlying data source.
 * @param initialSync Whether to reconcile with `refreshAll()` after subscribe.
 *   Defaults to [InitialSyncPolicy.RefreshAll]. No-op when `cache.pages` is
 *   empty at subscribe time.
 * @param unknownItem What to do with events whose target identity is not in
 *   the cache. Defaults to [UnknownItemPolicy.Drop].
 * @param deduplicateInserts When `true` (default), an [ReactiveEvent.Inserted]
 *   whose identity is **already** present in the cache is applied as an in-place
 *   update instead of inserting a duplicate. This makes the hybrid model safe —
 *   where the `load` lambda writes the fetched page into the same source the
 *   adapter observes, so paginating re-delivers those rows as inserts. Set to
 *   `false` to keep the literal "always insert" behaviour. Duplicate-by-identity
 *   inserts are never correct, so the default is on.
 * @param initPageState Factory for trailing pages created when a reactive insert overflows the
 *   **last** cached page. **Defaults** to a factory that creates a new page of the **same class** as
 *   the page that overflowed (custom subclasses and fresh ids preserved), so inserted rows are not
 *   silently dropped at the tail. Pass a custom factory to override page creation, or pass `null` to
 *   **opt out** — overflow past the last page is then dropped. This governs end-overflow only; an
 *   explicit [InsertPosition.At] pointing to an **uncached** page is always treated as out-of-window
 *   (deferred to [unknownItem]), never materialised.
 * @param onError Invoked for errors of the source and of event application
 *   (see "Error handling"). The default logs at warn level via the paginator's
 *   [com.jamal_aliev.paginator.offset.Paginator.logger].
 * @return The Job that owns the subscription. Cancel it to stop observing.
 * @throws IllegalStateException if another observe() Job is still active on
 *   this paginator.
 */
fun <T, ID : Any> MutablePaginator<T>.observe(
    scope: CoroutineScope,
    source: PaginatorReactiveCache<T, ID>,
    initialSync: InitialSyncPolicy = InitialSyncPolicy.RefreshAll,
    unknownItem: UnknownItemPolicy = UnknownItemPolicy.Drop,
    deduplicateInserts: Boolean = true,
    initPageState: ((page: Int, data: List<T>) -> OffsetPageState<T>)? = defaultOverflowPageFactory(),
    onError: ((Throwable) -> Unit)? = null,
): Job {
    val paginator = this
    val errorHandler: (Throwable) -> Unit = onError ?: { throwable ->
        paginator.logger.warn(LogComponent.LIFECYCLE) {
            "observe: reactive source emitted an error: $throwable"
        }
    }
    val job = scope.launch(start = CoroutineStart.LAZY) {
        val bridge = ReactiveBridge(
            paginator = paginator,
            source = source,
            unknownItem = unknownItem,
            deduplicateInserts = deduplicateInserts,
            initPageState = initPageState,
            onError = errorHandler,
        )
        bridge.run(initialSync)
    }
    // The launched job is LAZY, so it can be cancelled without side effects if the slot is taken.
    paginator.claimReactiveSlot(job = job, caller = "observe()")
    if (paginator.core.persistentCache != null) {
        paginator.logger.warn(LogComponent.LIFECYCLE) {
            "observe: this paginator has a PersistentPagingCache (L2) attached. " +
                    "When the reactive source is the same database L2 would cache, " +
                    "L2 is redundant here, deprecated since 10.3.0 and not supported together with " +
                    "reactive sources. Remove it."
        }
    }
    job.start()
    return job
}

/**
 * The running subscription behind [observe].
 *
 * - **Subscribe first.** The source is collected into an unbounded buffer *before* the initial
 *   `refreshAll`, so a change that lands between the last load and the subscription — or while
 *   that refresh runs — is buffered and applied afterwards instead of being lost (events are
 *   idempotent enough: inserts are deduplicated by identity).
 * - **Under the navigation lock.** Every event's CRUD runs while holding the paginator's navigation
 *   lock, so it never interleaves with navigation even when [observe]'s scope runs on a
 *   multi-threaded dispatcher. A `Batch` / `Moved` is applied atomically in one critical section —
 *   its rollback can only undo its own changes. `refreshAll` (an `Invalidate`, or
 *   [UnknownItemPolicy.RefreshAll]) is requested during the critical section and run once after it.
 * - **Untracked.** The CRUD runs with the core in untracked mode: searching the pages for an
 *   identity and writing the result are not a use of the pages for LRU / TTL caches.
 * - **Refresh in flight.** An event that changes a page a `refresh` is still loading gives that
 *   page a fresh Success state, so the refresh's older result does not overwrite the event; the
 *   page is marked dirty, so the refreshed data still arrives on the next navigation.
 * - **Resilient source.** A failing source (including a `CancellationException` of its own, e.g.
 *   `withTimeout`) is reported and re-subscribed with exponential backoff, followed by a
 *   `refreshAll` to reconcile what was missed. Only a source that completes normally ends the Job.
 * - **Wholesale replacement.** After `restoreState` or a transaction rollback the cache no longer
 *   matches what the events were applied to, so it is reconciled with `refreshAll`.
 */
private class ReactiveBridge<T, ID : Any>(
    private val paginator: MutablePaginator<T>,
    private val source: PaginatorReactiveCache<T, ID>,
    private val unknownItem: UnknownItemPolicy,
    private val deduplicateInserts: Boolean,
    private val initPageState: ((page: Int, data: List<T>) -> OffsetPageState<T>)?,
    private val onError: (Throwable) -> Unit,
) {

    /** What the applier works through, in order. */
    private sealed interface Command<out T, out ID : Any> {
        class Event<T, ID : Any>(val event: ReactiveEvent<T, ID>) : Command<T, ID>

        /** The source was re-subscribed after a failure: changes may have been missed. */
        object Resubscribed : Command<Nothing, Nothing>

        /** [PagingCore.cacheReplacements] moved: the cache may have been replaced wholesale. */
        object Replaced : Command<Nothing, Nothing>
    }

    private val commands = Channel<Command<T, ID>>(Channel.UNLIMITED)

    /** [PagingCore.cacheReplacements] as of the end of the last critical section of this bridge. */
    private var replacementsSeen: Long = paginator.core.cacheReplacements.value

    /** Set while applying one event (or batch) when a `refreshAll` must follow it. */
    private var refreshRequested = false

    suspend fun run(initialSync: InitialSyncPolicy): Unit = coroutineScope {
        // Subscribe before the initial sync: the collector runs undispatched up to its first
        // suspension, i.e. until the source is actually being collected.
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { collectSource() }
        val replacementWatcher = launch {
            val replacements = paginator.core.cacheReplacements
            var last: Long = replacements.value
            replacements.collect { value: Long ->
                if (value == last) return@collect
                last = value
                commands.send(Command.Replaced)
            }
        }
        try {
            if (initialSync == InitialSyncPolicy.RefreshAll) refreshAllReported()
            for (command in commands) {
                when (command) {
                    is Command.Event -> applyReported(command.event)
                    Command.Resubscribed -> refreshAllReported()
                    Command.Replaced -> {
                        // A replacement made by this bridge's own critical section (a rolled-back
                        // batch, a relabelling removal) is already accounted for.
                        val replacements: Long = paginator.core.cacheReplacements.value
                        if (replacements != replacementsSeen) {
                            replacementsSeen = replacements
                            refreshAllReported()
                        }
                    }
                }
            }
        } finally {
            // The source completed normally (or the Job is cancelled): the subscription is over.
            collector.cancel()
            replacementWatcher.cancel()
        }
    }

    private suspend fun collectSource() {
        var backoffMillis: Long = INITIAL_BACKOFF_MILLIS
        while (true) {
            try {
                source.changes().collect { event ->
                    backoffMillis = INITIAL_BACKOFF_MILLIS
                    commands.send(Command.Event(event))
                }
                commands.close() // completed normally
                return
            } catch (e: Throwable) {
                // Our own cancellation ends the collector; any other failure — including a
                // CancellationException the source throws itself (withTimeout) — is reported.
                if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                onError(e)
            }
            delay(backoffMillis)
            backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
            // Changes may have been missed while the source was down.
            commands.send(Command.Resubscribed)
        }
    }

    private suspend fun refreshAllReported() {
        if (paginator.cache.pages.isEmpty()) return
        try {
            paginator.refreshAll()
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            onError(e)
        } catch (e: Exception) {
            onError(e)
        }
    }

    private suspend fun applyReported(event: ReactiveEvent<T, ID>) {
        refreshRequested = false
        try {
            paginator.withNavigationLock {
                paginator.core.withUntrackedReads {
                    val inFlight: Map<Int, OffsetPageState<T>> = progressPages()
                    try {
                        paginator.applyEvent(event)
                    } finally {
                        keepOverInFlightRefresh(inFlight)
                        replacementsSeen = paginator.core.cacheReplacements.value
                    }
                }
            }
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            onError(e)
        } catch (e: Exception) {
            onError(e)
        }
        if (refreshRequested) refreshAllReported()
    }

    /** The cached Progress pages — pages a refresh (or a CRUD overflow) currently holds. */
    private fun progressPages(): Map<Int, OffsetPageState<T>> {
        val cache = paginator.cache
        val result = HashMap<Int, OffsetPageState<T>>()
        for (page in cache.pages) {
            val state = cache.peekStateOf(page)
            if (state.isProgressState()) result[page] = state
        }
        return result
    }

    /**
     * An event that changed a page while a `refresh` was loading it kept the page's id, so the
     * refresh would still commit its older result over the change. Give such a page a fresh
     * Success state (the refresh then drops its result) and mark it dirty so the refreshed data
     * still comes on the next navigation.
     */
    private fun keepOverInFlightRefresh(inFlight: Map<Int, OffsetPageState<T>>) {
        if (inFlight.isEmpty()) return
        val core = paginator.core
        val cache = paginator.cache
        for ((page, before) in inFlight) {
            val current: OffsetPageState<T> = cache.peekStateOf(page) ?: continue
            if (current === before || current.id != before.id || !current.isProgressState()) continue
            cache.setState(core.initializerSuccessPage(page, current.data, current.metadata), silently = true)
            core.markDirty(page)
        }
    }

    /**
     * Translates a single [ReactiveEvent] into the equivalent CRUD call(s). Runs under the
     * navigation lock without suspending, except for the transaction around `Batch` / `Moved`.
     */
    private suspend fun MutablePaginator<T>.applyEvent(event: ReactiveEvent<T, ID>) {
        when (event) {
            is ReactiveEvent.Updated<T> -> {
                val replaced = updateWhere(
                    predicate = { source.identity(it) == source.identity(event.item) },
                    transform = { event.item },
                )
                if (replaced == 0) unknown()
            }

            is ReactiveEvent.Removed<ID> -> {
                val removed = removeElement { source.identity(it) == event.identity }
                if (removed == null) unknown()
            }

            is ReactiveEvent.Inserted<T, ID> -> {
                val landed = insertAt(source, event.item, event.position, deduplicateInserts, initPageState)
                if (!landed) unknown()
            }

            is ReactiveEvent.Moved<T, ID> -> {
                val identityKey = source.identity(event.item)
                val isCached = cache.pages.any { page ->
                    cache.getStateOf(page)?.data?.any { source.identity(it) == identityKey } == true
                }
                if (!isCached) {
                    unknown()
                    return
                }
                transaction {
                    @Suppress("UNCHECKED_CAST")
                    val self = this as MutablePaginator<T>
                    self.removeElement { source.identity(it) == identityKey }
                    // The item was just removed, so dedup would never trigger here;
                    // pass false to skip the redundant cache scan.
                    val landed = self.insertAt(source, event.item, event.position, deduplicate = false, initPageState)
                    check(landed) {
                        "Moved: cannot resolve InsertPosition ${event.position} for identity $identityKey; " +
                                "transaction will be rolled back."
                    }
                }
            }

            // refreshAll takes the navigation lock: requested here, run after the critical section.
            ReactiveEvent.Invalidate -> refreshRequested = true

            is ReactiveEvent.Batch<T, ID> -> {
                if (event.events.isEmpty()) return
                // One atomic critical section: a failing child rolls back only this batch.
                // Refresh requests of its children are merged into one refreshAll afterwards.
                transaction {
                    @Suppress("UNCHECKED_CAST")
                    val self = this as MutablePaginator<T>
                    event.events.forEach { child -> self.applyEvent(child) }
                }
            }
        }
    }

    /** Applies [unknownItem] for an event whose target is not cached. */
    private fun unknown() {
        when (unknownItem) {
            UnknownItemPolicy.Drop -> Unit
            UnknownItemPolicy.RefreshAll -> refreshRequested = true
        }
    }

    private companion object {
        const val INITIAL_BACKOFF_MILLIS: Long = 500
        const val MAX_BACKOFF_MILLIS: Long = 30_000
    }
}

/**
 * Inserts [item] at the position described by [position]. Returns `false`
 * when the position cannot be resolved (e.g., the anchor identity is not in
 * the cache, or an explicit [InsertPosition.At] points to an uncached page).
 *
 * When [deduplicate] is `true` and an item with the same identity is already
 * cached, the existing entry is updated in place instead of inserting a
 * duplicate (returns `true` — the item "landed").
 *
 * [initPageState] is the overflow-page factory threaded down from [observe]:
 * it governs whether an insert that overflows the **last** cached page creates
 * a trailing page (non-null factory) or drops the overflow (`null`). It is
 * applied uniformly to every position so the reactive bridge behaves the same
 * wherever the insert lands. It does **not** materialise an out-of-window
 * [InsertPosition.At] target — that always resolves to `false`.
 */
private fun <T, ID : Any> MutablePaginator<T>.insertAt(
    source: PaginatorReactiveCache<T, ID>,
    item: T,
    position: InsertPosition<ID>,
    deduplicate: Boolean,
    initPageState: ((page: Int, data: List<T>) -> OffsetPageState<T>)?,
): Boolean {
    if (deduplicate) {
        val identityKey = source.identity(item)
        val alreadyCached = cache.pages.any { page ->
            cache.getStateOf(page)?.data?.any { source.identity(it) == identityKey } == true
        }
        if (alreadyCached) {
            updateWhere(
                predicate = { source.identity(it) == identityKey },
                transform = { item },
            )
            return true
        }
    }
    return when (position) {
        InsertPosition.Head -> prependElement(item, initSuccessPageState = initPageState)
        InsertPosition.Tail -> addElement(item, initSuccessPageState = initPageState)
        is InsertPosition.At -> {
            // An At position pointing outside the cached window is "out of window": we refuse to
            // materialise an arbitrary far page (that would create a non-contiguous island) and defer
            // to UnknownItemPolicy via false. initPageState governs OVERFLOW past the end during the
            // cascade, not target-page materialisation — so the guard is independent of the factory.
            if (cache.getStateOf(position.page) == null) return false
            addAllElements(
                elements = listOf(item),
                targetPage = position.page,
                index = position.index,
                initPageState = initPageState,
            )
            true
        }

        // insertAfter / insertBefore now thread the overflow factory themselves, so the reactive
        // bridge simply delegates (they return false when the anchor identity is not cached).
        is InsertPosition.AfterIdentity<ID> ->
            insertAfter(item, initPageState = initPageState) { source.identity(it) == position.identity }

        is InsertPosition.BeforeIdentity<ID> ->
            insertBefore(item, initPageState = initPageState) { source.identity(it) == position.identity }
    }
}

// ──────────────────────────────────────────────────────────────────────────
//  Single-subscription guard
// ──────────────────────────────────────────────────────────────────────────
//
// The active-Job slot lives outside MutablePaginator (extension-level state)
// so the paginator core stays unaware of the reactive subsystem. Two
// paginators never collide — the map is keyed by paginator identity. Entries
// are removed on Job completion via invokeOnCompletion, so cancelled
// subscriptions release their map slot and the paginator becomes eligible
// for GC once the caller drops it.
//
// observe() and observePages() share the slot: both drive the cached pages from
// a reactive source, so running them together would apply every change twice.

private val activeReactiveSubscriptions: AtomicRef<Map<MutablePaginator<*>, Job>> =
    atomic(emptyMap())

/**
 * Installs [job] as this paginator's single active reactive subscription, failing fast — after
 * cancelling [job] — when another one ([observe] or [observePages]) is still active. The slot
 * clears itself when [job] completes. [job] must not have done any work yet, so that cancelling it
 * on a refused install has no side effects.
 */
internal fun MutablePaginator<*>.claimReactiveSlot(job: Job, caller: String) {
    val paginator: MutablePaginator<*> = this
    while (true) {
        val current = activeReactiveSubscriptions.value
        val existing = current[paginator]
        if (existing != null && existing.isActive) {
            job.cancel()
            error(
                "$caller: another reactive subscription (observe() or observePages()) is already " +
                        "active on this MutablePaginator. Cancel the previous Job before subscribing again."
            )
        }
        val next = current + (paginator to job)
        if (activeReactiveSubscriptions.compareAndSet(current, next)) break
    }
    job.invokeOnCompletion {
        while (true) {
            val current = activeReactiveSubscriptions.value
            if (current[paginator] !== job) return@invokeOnCompletion
            val next = current - paginator
            if (activeReactiveSubscriptions.compareAndSet(current, next)) return@invokeOnCompletion
        }
    }
}
