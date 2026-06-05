package com.jamal_aliev.paginator.cursor.extension

import com.jamal_aliev.paginator.core.cache.reactive.InitialSyncPolicy
import com.jamal_aliev.paginator.core.cache.reactive.UnknownItemPolicy
import com.jamal_aliev.paginator.core.logger.LogComponent
import com.jamal_aliev.paginator.core.logger.warn
import com.jamal_aliev.paginator.cursor.MutableCursorPaginator
import com.jamal_aliev.paginator.cursor.cache.reactive.CursorInsertPosition
import com.jamal_aliev.paginator.cursor.cache.reactive.CursorPaginatorReactiveCache
import com.jamal_aliev.paginator.cursor.cache.reactive.CursorReactiveEvent
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
 * 1. Optionally issues `refreshAll()` to reconcile the L1 cache with any
 *    source mutations that landed between the most recent page load and the
 *    subscribe point (controlled by [initialSync]). When `cache.cursors` is
 *    empty at subscribe time the reconciliation is skipped — there is
 *    nothing to refresh and the upcoming first `load()` will already see the
 *    latest source state.
 * 2. Collects [source].changes() until the returned [Job] is cancelled.
 * 3. Translates each [CursorReactiveEvent] to the equivalent CRUD call on
 *    this paginator. Emissions are applied serially in arrival order —
 *    neither the source nor the caller need to add their own synchronisation.
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
 * Errors from [source].changes() and from individual CRUD applications are
 * routed to [onError]. The collector continues after non-cancellation
 * errors. Pass `onError = { throw it }` if you would rather have the Job
 * fail on the first source-side error.
 *
 * ## Persistent cache warning
 *
 * If the paginator has a [com.jamal_aliev.paginator.cursor.cache.persistent.CursorPersistentPagingCache]
 * (L2) attached, this function logs a warning — the reactive source is
 * almost certainly the same database that L2 would cache, so L2 becomes
 * redundant overhead. Remove L2 in this configuration.
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
 * @param onError Invoked for non-cancellation errors that escape during
 *   event application. The default logs at warn level via the paginator's
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
    onError: ((Throwable) -> Unit)? = null,
): Job {
    val paginator = this
    val errorHandler: (Throwable) -> Unit = onError ?: { throwable ->
        paginator.logger.warn(LogComponent.LIFECYCLE) {
            "observe: reactive source emitted an error: $throwable"
        }
    }
    val job = scope.launch(start = CoroutineStart.LAZY) {
        if (initialSync == InitialSyncPolicy.RefreshAll && paginator.cache.cursors.isNotEmpty()) {
            try {
                paginator.refreshAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errorHandler(e)
            }
        }
        try {
            source.changes().collect { event ->
                try {
                    paginator.applyEvent(source, event, unknownItem, deduplicateInserts)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    errorHandler(e)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            errorHandler(e)
        }
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
                    "L2 becomes redundant overhead. Consider removing it."
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
 * Translates a single [CursorReactiveEvent] into the equivalent CRUD call(s)
 * on the paginator.
 *
 * Centralised so that [CursorReactiveEvent.Batch] can recurse through here
 * (wrapped in a transaction) without duplicating the dispatch table.
 */
private suspend fun <K : Any, T, ID : Any> MutableCursorPaginator<K, T>.applyEvent(
    source: CursorPaginatorReactiveCache<T, ID>,
    event: CursorReactiveEvent<T, ID>,
    unknownItem: UnknownItemPolicy,
    deduplicateInserts: Boolean,
) {
    when (event) {
        is CursorReactiveEvent.Updated<T> -> {
            val replaced = updateWhere(
                predicate = { source.identity(it) == source.identity(event.item) },
                transform = { event.item },
            )
            if (replaced == 0) handleUnknown(unknownItem)
        }

        is CursorReactiveEvent.Removed<ID> -> {
            val removed = removeElement { source.identity(it) == event.identity }
            if (removed == null) handleUnknown(unknownItem)
        }

        is CursorReactiveEvent.Inserted<T, ID> -> {
            val landed = insertAt(source, event.item, event.position, deduplicateInserts)
            if (!landed) handleUnknown(unknownItem)
        }

        is CursorReactiveEvent.Moved<T, ID> -> {
            val identityKey = source.identity(event.item)
            val isCached = cache.cursors.any { cursor ->
                cache.getStateOf(cursor.self)?.data?.any { source.identity(it) == identityKey } == true
            }
            if (!isCached) {
                handleUnknown(unknownItem)
                return
            }
            transaction {
                @Suppress("UNCHECKED_CAST")
                val self = this as MutableCursorPaginator<K, T>
                self.removeElement { source.identity(it) == identityKey }
                // The item was just removed, so dedup would never trigger here;
                // pass false to skip the redundant cache scan.
                val landed = self.insertAt(source, event.item, event.position, deduplicate = false)
                check(landed) {
                    "Moved: cannot resolve CursorInsertPosition ${event.position} for identity $identityKey; " +
                            "transaction will be rolled back."
                }
            }
        }

        CursorReactiveEvent.Invalidate -> {
            // refreshAll bails out cleanly on its own if no cursors are cached.
            refreshAll()
        }

        is CursorReactiveEvent.Batch<T, ID> -> {
            if (event.events.isEmpty()) return
            transaction {
                @Suppress("UNCHECKED_CAST")
                val self = this as MutableCursorPaginator<K, T>
                event.events.forEach { child ->
                    self.applyEvent(source, child, unknownItem, deduplicateInserts)
                }
            }
        }
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
        CursorInsertPosition.Head -> prependElement(item)
        CursorInsertPosition.Tail -> addElement(item)
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
            )
            true
        }

        is CursorInsertPosition.AfterIdentity<ID> ->
            insertAfter(item) { source.identity(it) == position.identity }

        is CursorInsertPosition.BeforeIdentity<ID> ->
            insertBefore(item) { source.identity(it) == position.identity }
    }
}

/**
 * Applies [UnknownItemPolicy] when an event references an item not present
 * in the cache.
 */
private suspend fun <K : Any, T> MutableCursorPaginator<K, T>.handleUnknown(policy: UnknownItemPolicy) {
    when (policy) {
        UnknownItemPolicy.Drop -> Unit
        UnknownItemPolicy.RefreshAll -> refreshAll()
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
