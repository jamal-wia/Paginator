// The deprecated L2 persistent cache is still wired through here until it is removed.
@file:Suppress("DEPRECATION")

package com.jamal_aliev.paginator.cursor.extension

import com.jamal_aliev.paginator.core.cache.reactive.InitialSyncPolicy
import com.jamal_aliev.paginator.core.cache.reactive.UnknownItemPolicy
import com.jamal_aliev.paginator.core.extension.isProgressState
import com.jamal_aliev.paginator.core.logger.LogComponent
import com.jamal_aliev.paginator.core.logger.warn
import com.jamal_aliev.paginator.cursor.MutableCursorPaginator
import com.jamal_aliev.paginator.cursor.MutableCursorPaginator.CursorBookmarkFactory
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.cache.reactive.CursorInsertPosition
import com.jamal_aliev.paginator.cursor.cache.reactive.CursorPaginatorReactiveCache
import com.jamal_aliev.paginator.cursor.cache.reactive.CursorReactiveEvent
import com.jamal_aliev.paginator.cursor.defaultCursorOverflowPageFactory
import com.jamal_aliev.paginator.cursor.page.CursorPageState
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
 * Cursor-paginator counterpart of
 * [com.jamal_aliev.paginator.offset.extension.observe].
 *
 * Subscribes [source] to this paginator so that changes in the underlying
 * reactive data source flow into the L1 cache as point CRUD operations
 * instead of triggering fresh `load()` calls.
 *
 * This is the recommended way to bridge Room/SQLDelight/Realm DAOs (or any
 * other reactive source: WebSocket, Server-Sent Events, in-app event bus,
 * Realm sync, …) to a cursor paginator. Subscribing to a reactive source from
 * *inside* the `load` lambda is an antipattern — the first emission triggers
 * a navigation call (e.g. `refresh`) that deadlocks on the non-reentrant
 * navigation mutex already held by the outer load.
 *
 * ## What this function does, step by step
 *
 * 1. Subscribes to [source].changes(), buffering its events.
 * 2. Optionally issues `refreshAll()` to reconcile the L1 cache with any
 *    source mutations that landed between the most recent page load and the
 *    subscribe point (controlled by [initialSync]). When `cache.cursors` is
 *    empty at subscribe time the reconciliation is skipped — there is
 *    nothing to refresh and the upcoming first `load()` will already see the
 *    latest source state. Events arriving meanwhile are applied after it.
 * 3. Translates each [CursorReactiveEvent] to the equivalent CRUD call on this
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
 * completion.
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
 * If the paginator has a [com.jamal_aliev.paginator.cursor.cache.persistent.CursorPersistentPagingCache]
 * (L2) attached, this function logs a warning — the reactive source is
 * almost certainly the same database that L2 would cache, so L2 becomes
 * redundant. L2 is also deprecated since 10.3.0 and not supported together
 * with reactive sources — remove it in this configuration.
 *
 * @param scope The coroutine scope that owns the subscription's lifetime.
 * @param source The reactive cache adapter for the underlying data source.
 * @param initialSync Whether to reconcile with `refreshAll()` after subscribe.
 *   Defaults to [InitialSyncPolicy.RefreshAll]. No-op when `cache.cursors` is
 *   empty at subscribe time.
 * @param unknownItem What to do with events whose target identity is not in
 *   the cache. Defaults to [UnknownItemPolicy.Drop].
 * @param deduplicateInserts When `true` (default), a [CursorReactiveEvent.Inserted]
 *   whose identity is **already** present in the cache is applied as an in-place
 *   update instead of inserting a duplicate. This makes the hybrid model safe —
 *   where the `load` lambda writes the fetched page into the same source the
 *   adapter observes, so paginating re-delivers those rows as inserts. Set to
 *   `false` to keep the literal "always insert" behaviour. Duplicate-by-identity
 *   inserts are never correct, so the default is on.
 * @param bookmarkFactory Factory that mints the `self` cursor for a brand-new tail page when a
 *   reactive insert overflows the **last** cached page. **Defaults to `null`** — unlike the offset
 *   paginator, cursors are server-issued and cannot be synthesised client-side, so tail-page creation
 *   is opt-in; while `null`, an insert that overruns the tail drops the overflow (the documented
 *   cursor behaviour — see `docs/15. reactive-sources.md` → "Tail-insert overflow caveat"). Supply a
 *   factory to opt in. This is the cursor analogue of the offset `observe(initPageState = …)`.
 * @param initPageState Factory for the **state** of a created tail page. **Defaults** to the
 *   same-class factory ([defaultCursorOverflowPageFactory]); only takes effect when [bookmarkFactory]
 *   is supplied. Pass `null` to fall back to copying the source page state.
 * @param onError Invoked for errors of the source and of event application
 *   (see "Error handling"). The default logs at warn level via the paginator's
 *   [com.jamal_aliev.paginator.cursor.CursorPaginator.logger].
 * @return The Job that owns the subscription. Cancel it to stop observing.
 * @throws IllegalStateException if another observe() Job is still active on
 *   this paginator.
 */
fun <K : Any, T, ID : Any> MutableCursorPaginator<K, T>.observe(
    scope: CoroutineScope,
    source: CursorPaginatorReactiveCache<T, ID>,
    initialSync: InitialSyncPolicy = InitialSyncPolicy.RefreshAll,
    unknownItem: UnknownItemPolicy = UnknownItemPolicy.Drop,
    deduplicateInserts: Boolean = true,
    bookmarkFactory: CursorBookmarkFactory<K>? = null,
    initPageState: ((previous: CursorBookmark<K>, data: List<T>) -> CursorPageState<K, T>)? =
        defaultCursorOverflowPageFactory(),
    onError: ((Throwable) -> Unit)? = null,
): Job {
    val paginator = this
    val errorHandler: (Throwable) -> Unit = onError ?: { throwable ->
        paginator.logger.warn(LogComponent.LIFECYCLE) {
            "observe: reactive source emitted an error: $throwable"
        }
    }
    val job = scope.launch(start = CoroutineStart.LAZY) {
        val bridge = CursorReactiveBridge(
            paginator = paginator,
            source = source,
            unknownItem = unknownItem,
            deduplicateInserts = deduplicateInserts,
            bookmarkFactory = bookmarkFactory,
            initPageState = initPageState,
            onError = errorHandler,
        )
        bridge.run(initialSync)
    }
    // CAS the new Job into the per-paginator slot, refusing to install when a
    // previous subscription is still active. The launched job is LAZY so we
    // can cancel it without side effects if the slot is taken.
    while (true) {
        val current = activeReactiveSubscriptions.value
        val existing = current[paginator]
        if (existing != null && existing.isActive) {
            job.cancel()
            error(
                "observe(): another reactive subscription is already active on this " +
                        "MutableCursorPaginator. Cancel the previous Job before subscribing again."
            )
        }
        val next = current + (paginator to job)
        if (activeReactiveSubscriptions.compareAndSet(current, next)) break
    }
    if (paginator.core.persistentCache != null) {
        paginator.logger.warn(LogComponent.LIFECYCLE) {
            "observe: this paginator has a CursorPersistentPagingCache (L2) attached. " +
                    "When the reactive source is the same database L2 would cache, " +
                    "L2 is redundant here, deprecated since 10.3.0 and not supported together with " +
                    "reactive sources. Remove it."
        }
    }
    job.invokeOnCompletion {
        while (true) {
            val current = activeReactiveSubscriptions.value
            if (current[paginator] !== job) return@invokeOnCompletion
            val next = current - paginator
            if (activeReactiveSubscriptions.compareAndSet(current, next)) return@invokeOnCompletion
        }
    }
    job.start()
    return job
}

/**
 * The running subscription behind [observe]; the cursor twin of the offset `ReactiveBridge`.
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
private class CursorReactiveBridge<K : Any, T, ID : Any>(
    private val paginator: MutableCursorPaginator<K, T>,
    private val source: CursorPaginatorReactiveCache<T, ID>,
    private val unknownItem: UnknownItemPolicy,
    private val deduplicateInserts: Boolean,
    private val bookmarkFactory: CursorBookmarkFactory<K>?,
    private val initPageState: ((previous: CursorBookmark<K>, data: List<T>) -> CursorPageState<K, T>)?,
    private val onError: (Throwable) -> Unit,
) {

    /** What the applier works through, in order. */
    private sealed interface Command<out T, out ID : Any> {
        class Event<T, ID : Any>(val event: CursorReactiveEvent<T, ID>) : Command<T, ID>

        /** The source was re-subscribed after a failure: changes may have been missed. */
        object Resubscribed : Command<Nothing, Nothing>

        /** [CursorPagingCore.cacheReplacements] moved: the cache may have been replaced wholesale. */
        object Replaced : Command<Nothing, Nothing>
    }

    private val commands = Channel<Command<T, ID>>(Channel.UNLIMITED)

    /** [CursorPagingCore.cacheReplacements] as of the end of the last critical section of this bridge. */
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
                        // batch) is already accounted for.
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
        if (paginator.cache.cursors.isEmpty()) return
        try {
            paginator.refreshAll()
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            onError(e)
        } catch (e: Exception) {
            onError(e)
        }
    }

    private suspend fun applyReported(event: CursorReactiveEvent<T, ID>) {
        refreshRequested = false
        try {
            paginator.withNavigationLock {
                paginator.core.withUntrackedReads {
                    val inFlight: Map<K, CursorPageState<K, T>> = progressPages()
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
    private fun progressPages(): Map<K, CursorPageState<K, T>> {
        val cache = paginator.cache
        val result = HashMap<K, CursorPageState<K, T>>()
        for (cursor in cache.cursors) {
            val state = cache.peekStateOf(cursor.self)
            if (state.isProgressState()) result[cursor.self] = state
        }
        return result
    }

    /**
     * An event that changed a page while a `refresh` was loading it kept the page's id, so the
     * refresh would still commit its older result over the change. Give such a page a fresh
     * Success state (the refresh then drops its result) and mark it dirty so the refreshed data
     * still comes on the next navigation.
     */
    private fun keepOverInFlightRefresh(inFlight: Map<K, CursorPageState<K, T>>) {
        if (inFlight.isEmpty()) return
        val core = paginator.core
        val cache = paginator.cache
        for ((self, before) in inFlight) {
            val current: CursorPageState<K, T> = cache.peekStateOf(self) ?: continue
            if (current === before || current.id != before.id || !current.isProgressState()) continue
            val cursor: CursorBookmark<K> = cache.getCursorOf(self) ?: continue
            cache.setState(cursor, core.initializerSuccessPage(cursor, current.data, current.metadata), silently = true)
            core.markDirty(self)
        }
    }

    /**
     * Translates a single [CursorReactiveEvent] into the equivalent CRUD call(s). Runs under the
     * navigation lock without suspending, except for the transaction around `Batch` / `Moved`.
     */
    private suspend fun MutableCursorPaginator<K, T>.applyEvent(event: CursorReactiveEvent<T, ID>) {
        when (event) {
            is CursorReactiveEvent.Updated<T> -> {
                val replaced = updateWhere(
                    predicate = { source.identity(it) == source.identity(event.item) },
                    transform = { event.item },
                )
                if (replaced == 0) unknown()
            }

            is CursorReactiveEvent.Removed<ID> -> {
                val removed = removeElement { source.identity(it) == event.identity }
                if (removed == null) unknown()
            }

            is CursorReactiveEvent.Inserted<T, ID> -> {
                val landed = insertAt(
                    source, event.item, event.position, deduplicateInserts, bookmarkFactory, initPageState,
                )
                if (!landed) unknown()
            }

            is CursorReactiveEvent.Moved<T, ID> -> {
                val identityKey = source.identity(event.item)
                val isCached = cache.cursors.any { cursor ->
                    cache.getStateOf(cursor.self)?.data?.any { source.identity(it) == identityKey } == true
                }
                if (!isCached) {
                    unknown()
                    return
                }
                transaction {
                    @Suppress("UNCHECKED_CAST")
                    val self = this as MutableCursorPaginator<K, T>
                    self.removeElement { source.identity(it) == identityKey }
                    // The item was just removed, so dedup would never trigger here;
                    // pass false to skip the redundant cache scan.
                    val landed = self.insertAt(
                        source, event.item, event.position, deduplicate = false, bookmarkFactory, initPageState,
                    )
                    check(landed) {
                        "Moved: cannot resolve CursorInsertPosition ${event.position} for identity $identityKey; " +
                                "transaction will be rolled back."
                    }
                }
            }

            // refreshAll takes the navigation lock: requested here, run after the critical section.
            CursorReactiveEvent.Invalidate -> refreshRequested = true

            is CursorReactiveEvent.Batch<T, ID> -> {
                if (event.events.isEmpty()) return
                // One atomic critical section: a failing child rolls back only this batch.
                // Refresh requests of its children are merged into one refreshAll afterwards.
                transaction {
                    @Suppress("UNCHECKED_CAST")
                    val self = this as MutableCursorPaginator<K, T>
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
 * the cache, an explicit [CursorInsertPosition.At] points to an uncached
 * `self`, or the cache is empty for [CursorInsertPosition.Head] /
 * [CursorInsertPosition.Tail]).
 *
 * When [deduplicate] is `true` and an item with the same identity is already
 * cached, the existing entry is updated in place instead of inserting a
 * duplicate (returns `true` — the item "landed").
 */
private fun <K : Any, T, ID : Any> MutableCursorPaginator<K, T>.insertAt(
    source: CursorPaginatorReactiveCache<T, ID>,
    item: T,
    position: CursorInsertPosition<ID>,
    deduplicate: Boolean,
    bookmarkFactory: CursorBookmarkFactory<K>?,
    initPageState: ((previous: CursorBookmark<K>, data: List<T>) -> CursorPageState<K, T>)?,
): Boolean {
    if (deduplicate) {
        val identityKey = source.identity(item)
        val alreadyCached = cache.cursors.any { cursor ->
            cache.getStateOf(cursor.self)?.data?.any { source.identity(it) == identityKey } == true
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
        CursorInsertPosition.Head ->
            prependElement(item, bookmarkFactory = bookmarkFactory, initPageState = initPageState)

        CursorInsertPosition.Tail ->
            addElement(item, bookmarkFactory = bookmarkFactory, initPageState = initPageState)

        is CursorInsertPosition.At -> {
            // Unlike the offset paginator, cursor pages cannot be synthesised by
            // index — the `self` key is server-provided. Treat a missing target
            // page as "out of window" → false, so the UnknownItemPolicy decides.
            // The reactive insert-position key is opaque (`Any`); cast it to the
            // paginator's key type — safe because `K` is erased at runtime.
            @Suppress("UNCHECKED_CAST")
            val targetSelf: K = position.self as K
            if (cache.getStateOf(targetSelf) == null) return false
            addAllElements(
                elements = listOf(item),
                targetSelf = targetSelf,
                index = position.index,
                bookmarkFactory = bookmarkFactory,
                initPageState = initPageState,
            )
            true
        }

        is CursorInsertPosition.AfterIdentity<ID> ->
            insertAfter(item, bookmarkFactory = bookmarkFactory, initPageState = initPageState) {
                source.identity(it) == position.identity
            }

        is CursorInsertPosition.BeforeIdentity<ID> ->
            insertBefore(item, bookmarkFactory = bookmarkFactory, initPageState = initPageState) {
                source.identity(it) == position.identity
            }
    }
}

// ──────────────────────────────────────────────────────────────────────────
//  Single-subscription guard
// ──────────────────────────────────────────────────────────────────────────
//
// The active-Job slot lives outside MutableCursorPaginator (extension-level
// state) so the paginator core stays unaware of the reactive subsystem. Two
// paginators never collide — the map is keyed by paginator identity. Entries
// are removed on Job completion via invokeOnCompletion, so cancelled
// subscriptions release their map slot and the paginator becomes eligible
// for GC once the caller drops it.

private val activeReactiveSubscriptions: AtomicRef<Map<MutableCursorPaginator<*, *>, Job>> =
    atomic(emptyMap())
