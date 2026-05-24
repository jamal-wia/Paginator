package com.jamal_aliev.paginator.cursor.cache.reactive

import kotlinx.coroutines.flow.Flow

/**
 * Cursor-paginator counterpart of
 * [com.jamal_aliev.paginator.core.cache.reactive.PaginatorReactiveCache].
 *
 * Adapter that exposes a reactive data source (Room DAO, SQLDelight query,
 * Realm collection, WebSocket stream, …) as a stream of [CursorReactiveEvent]s
 * the cursor paginator can consume.
 *
 * Implementations sit between the source-of-truth (the database, the server,
 * the in-memory cache) and the paginator. The paginator subscribes via
 * `MutableCursorPaginator.observe(scope, source)`; every emission flows through
 * the matching CRUD operation on the paginator's L1 cache without re-entering
 * the `load` lambda. Subscribing to the source from inside `load` would
 * deadlock the non-reentrant navigation mutex on the first emission.
 *
 * ## Required guarantees
 *
 * 1. **Stable identity.** [identity] must return a value that uniquely
 *    identifies an item across mutations — typically a primary key. Returning
 *    `equals`-based identity for items whose fields change (and therefore
 *    whose `equals` result changes) makes [CursorReactiveEvent.Updated] and
 *    [CursorReactiveEvent.Removed] silently fail to locate the cached item.
 *
 * 2. **Hot subscription.** [changes] is collected by the paginator from the
 *    moment `observe()` is called until the returned `Job` is cancelled.
 *    Returning a cold flow that emits a finite set and completes is allowed
 *    but unusual — once it completes, no further updates flow to the
 *    paginator.
 *
 * 3. **Position correctness for inserts.** When emitting
 *    [CursorReactiveEvent.Inserted], the [CursorInsertPosition] must reflect
 *    the source's actual ordering. If the position cannot be computed (e.g.,
 *    a complex `ORDER BY` that the adapter cannot mirror), prefer
 *    [CursorReactiveEvent.Invalidate] over guessing.
 *
 * ## Identity type [ID]
 *
 * The [ID] type parameter pins the identity type at compile time so that the
 * type system catches mismatches like `Removed(42)` against a `Long` primary
 * key — a class of silent bugs in earlier untyped designs where `identity`
 * returned `Any`. Pick `Long`/`String`/your domain id `data class` and let
 * `equals`/`hashCode` carry the contract.
 *
 * ## Initial sync
 *
 * Between the first `load(cursor)` returning and the paginator subscribing to
 * [changes], events may be lost. The `observe` extension offers an
 * [com.jamal_aliev.paginator.core.cache.reactive.InitialSyncPolicy] that
 * issues a `refreshAll()` after subscribe to reconcile.
 *
 * ## Persistent cache interaction
 *
 * Wiring a `CursorPaginatorReactiveCache` to a paginator whose `load` already
 * reads from the same database (and that database is therefore the source of
 * truth) makes
 * [com.jamal_aliev.paginator.cursor.cache.persistent.CursorPersistentPagingCache]
 * (L2) redundant — the database *is* L2. The `observe` extension logs a
 * warning in this configuration; remove the persistent cache to keep the data
 * layers coherent.
 *
 * @param T The item type produced by the source. Must match the paginator's
 *   item type.
 * @param ID The identity type returned by [identity]. Must have meaningful
 *   `equals`/`hashCode` — typically `Long`, `String`, or a dedicated id
 *   `data class`.
 */
interface CursorPaginatorReactiveCache<T, ID : Any> {

    /**
     * Returns a stable domain identity for [item].
     *
     * Used by the paginator to locate cached items when applying
     * [CursorReactiveEvent.Updated], [CursorReactiveEvent.Removed],
     * [CursorReactiveEvent.Moved], and the `*Identity` variants of
     * [CursorInsertPosition]. The returned value must:
     * - have a meaningful `equals`/`hashCode` implementation (typically a
     *   primary key — `Int`, `Long`, `String`, or a dedicated id `data class`);
     * - remain the same for a given logical entity across edits — changing a
     *   user's email must not change `identity(user)`.
     *
     * Returning the item itself works only when the item is immutable and
     * structural equality coincides with identity. Almost always wrong for
     * mutable domain types.
     */
    fun identity(item: T): ID

    /**
     * Returns the hot stream of changes the source observes.
     *
     * The paginator's `observe` extension calls this once per subscribe and
     * collects until its `Job` is cancelled. Implementations are free to
     * share a single underlying flow across multiple subscribers — the
     * paginator imposes no ownership requirement on the returned flow.
     *
     * Emissions are applied to the paginator in arrival order. The paginator
     * serialises application internally, so adapters do not need to provide
     * their own backpressure beyond what is appropriate for their source.
     */
    fun changes(): Flow<CursorReactiveEvent<T, ID>>
}
