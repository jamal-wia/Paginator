package com.jamal_aliev.paginator.offset.extension

import com.jamal_aliev.paginator.core.cache.reactive.InitialSyncPolicy
import com.jamal_aliev.paginator.core.cache.reactive.InsertPosition
import com.jamal_aliev.paginator.core.cache.reactive.PaginatorReactiveCache
import com.jamal_aliev.paginator.core.cache.reactive.ReactiveEvent
import com.jamal_aliev.paginator.core.cache.reactive.UnknownItemPolicy
import com.jamal_aliev.paginator.core.logger.LogComponent
import com.jamal_aliev.paginator.core.logger.warn
import com.jamal_aliev.paginator.offset.MutablePaginator
import com.jamal_aliev.paginator.offset.defaultOverflowPageFactory
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * 1. Optionally issues `refreshAll()` to reconcile the L1 cache with any
 *    source mutations that landed between the most recent page load and the
 *    subscribe point (controlled by [initialSync]). When `cache.pages` is
 *    empty at subscribe time the reconciliation is skipped — there is
 *    nothing to refresh and the upcoming first `load()` will already see the
 *    latest source state.
 * 2. Collects [source].changes() until the returned [Job] is cancelled.
 * 3. Translates each [ReactiveEvent] to the equivalent CRUD call on this
 *    paginator. Emissions are applied serially in arrival order — neither
 *    the source nor the caller need to add their own synchronisation.
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
 * If the paginator has a [com.jamal_aliev.paginator.core.cache.persistent.PersistentPagingCache]
 * (L2) attached, this function logs a warning — the reactive source is
 * almost certainly the same database that L2 would cache, so L2 becomes
 * redundant overhead. Remove L2 in this configuration.
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
 * @param onError Invoked for non-cancellation errors that escape during
 *   event application. The default logs at warn level via the paginator's
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
    val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
        if (initialSync == InitialSyncPolicy.RefreshAll && paginator.cache.pages.isNotEmpty()) {
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
                    paginator.applyEvent(source, event, unknownItem, deduplicateInserts, initPageState)
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
                        "MutablePaginator. Cancel the previous Job before subscribing again."
            )
        }
        val next = current + (paginator to job)
        if (activeReactiveSubscriptions.compareAndSet(current, next)) break
    }
    if (paginator.core.persistentCache != null) {
        paginator.logger.warn(LogComponent.LIFECYCLE) {
            "observe: this paginator has a PersistentPagingCache (L2) attached. " +
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
 * Translates a single [ReactiveEvent] into the equivalent CRUD call(s) on
 * the paginator.
 *
 * Centralised so that [ReactiveEvent.Batch] can recurse through here
 * (wrapped in a transaction) without duplicating the dispatch table.
 */
private suspend fun <T, ID : Any> MutablePaginator<T>.applyEvent(
    source: PaginatorReactiveCache<T, ID>,
    event: ReactiveEvent<T, ID>,
    unknownItem: UnknownItemPolicy,
    deduplicateInserts: Boolean,
    initPageState: ((page: Int, data: List<T>) -> OffsetPageState<T>)?,
) {
    when (event) {
        is ReactiveEvent.Updated<T> -> {
            val replaced = updateWhere(
                predicate = { source.identity(it) == source.identity(event.item) },
                transform = { event.item },
            )
            if (replaced == 0) handleUnknown(unknownItem)
        }

        is ReactiveEvent.Removed<ID> -> {
            val removed = removeElement { source.identity(it) == event.identity }
            if (removed == null) handleUnknown(unknownItem)
        }

        is ReactiveEvent.Inserted<T, ID> -> {
            val landed = insertAt(source, event.item, event.position, deduplicateInserts, initPageState)
            if (!landed) handleUnknown(unknownItem)
        }

        is ReactiveEvent.Moved<T, ID> -> {
            val identityKey = source.identity(event.item)
            val isCached = cache.pages.any { page ->
                cache.getStateOf(page)?.data?.any { source.identity(it) == identityKey } == true
            }
            if (!isCached) {
                handleUnknown(unknownItem)
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

        ReactiveEvent.Invalidate -> {
            // refreshAll bails out cleanly on its own if no pages are cached.
            refreshAll()
        }

        is ReactiveEvent.Batch<T, ID> -> {
            if (event.events.isEmpty()) return
            transaction {
                @Suppress("UNCHECKED_CAST")
                val self = this as MutablePaginator<T>
                event.events.forEach { child ->
                    self.applyEvent(source, child, unknownItem, deduplicateInserts, initPageState)
                }
            }
        }
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

/**
 * Applies [UnknownItemPolicy] when an event references an item not present
 * in the cache.
 */
private suspend fun <T> MutablePaginator<T>.handleUnknown(policy: UnknownItemPolicy) {
    when (policy) {
        UnknownItemPolicy.Drop -> Unit
        UnknownItemPolicy.RefreshAll -> refreshAll()
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

private val activeReactiveSubscriptions: AtomicRef<Map<MutablePaginator<*>, Job>> =
    atomic(emptyMap())
