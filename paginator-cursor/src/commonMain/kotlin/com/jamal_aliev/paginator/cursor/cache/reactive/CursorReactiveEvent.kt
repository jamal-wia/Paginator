package com.jamal_aliev.paginator.cursor.cache.reactive

/**
 * Cursor-paginator counterpart of
 * [com.jamal_aliev.paginator.core.cache.reactive.ReactiveEvent].
 *
 * A change observed in a reactive data source, delivered to a
 * [com.jamal_aliev.paginator.cursor.MutableCursorPaginator] via
 * [CursorPaginatorReactiveCache.changes].
 *
 * Each event names *what* changed in the source's coordinate system. The
 * paginator translates the event to the corresponding CRUD operation on its L1
 * cache, using [CursorPaginatorReactiveCache.identity] to locate items already
 * cached.
 *
 * **Variance:** the type is declared `out T, out ID` so that an adapter for a
 * base type can be passed to a paginator of the same type without extra
 * wrappers, and so that variants that do not carry an item ([Removed],
 * [Invalidate]) are assignable everywhere a typed event is expected.
 *
 * **Identity typing:** the [ID] type parameter binds the identity carried by
 * [Removed], [Moved], and the `*Identity` variants of [CursorInsertPosition] to
 * the same type [CursorPaginatorReactiveCache.identity] returns. This prevents
 * the common `Removed(42)` vs `Long` mismatch that silently fails to match a
 * cached row.
 *
 * @see CursorPaginatorReactiveCache
 * @see CursorInsertPosition
 */
sealed interface CursorReactiveEvent<out T, out ID : Any> {

    /**
     * An item with the same identity as [item] has changed in the source.
     *
     * The paginator looks up the cached item by
     * `cache.identity(cached) == cache.identity(item)` and replaces it in
     * place. The page does not move — only the item content changes.
     *
     * If no cached item matches, the active `UnknownItemPolicy` decides
     * whether the event is dropped or escalated.
     */
    data class Updated<T>(val item: T) : CursorReactiveEvent<T, Nothing>

    /**
     * Some fields of the item with the given [identity] have changed in the source, and the
     * adapter knows the change but not the whole new item (a like counter, a read flag, a
     * presence dot).
     *
     * The paginator finds every cached item with `cache.identity(cached) == identity` and
     * replaces it in place with `transform(cached)`. The page does not move. Unlike [Updated],
     * the new value is built from the item as it is cached at the moment of application.
     * Events are applied one at a time under the paginator's navigation lock, so concurrent
     * patches compose and no update is lost — the reactive counterpart of
     * `MutableStateFlow.update`.
     *
     * **[transform] must be pure, fast and non-suspending.** It runs inside the paginator's
     * critical section. It may run more than once if several cached items share the identity,
     * and its result is thrown away if a surrounding [Batch] rolls back. It must not change the
     * item's identity: a transform that does is reported to `onError` and the item is left
     * unchanged. An [Exception] thrown by [transform] is reported to `onError` too and leaves
     * the item unchanged; inside a [Batch] it rolls back the whole batch. An [Error] (for
     * example `NotImplementedError` from `TODO()`) is not caught and ends the subscription.
     * The one exception to "left unchanged": when the same identity is cached more than once
     * (possible only with `deduplicateInserts = false`), copies written before the failing one
     * stay patched, and no snapshot is emitted until the next change.
     *
     * If no cached item matches, the active `UnknownItemPolicy` decides whether the event is
     * dropped or escalated. Works inside [Batch] like any other event.
     *
     * **Variance:** unlike the parent interface, `Patched` is invariant in [T] because
     * [transform] reads an item. Emit it typed with exactly the paginator's item type: a
     * `Patched<Dog, ID>` upcast into a `CursorReactiveEvent<Animal, ID>` flow would call
     * [transform] on items that are not `Dog`s. On JVM, Android, Native and Wasm that fails
     * with a `ClassCastException` reported to `onError`; on Kotlin/JS there is no cast, so the
     * outcome is undefined (a silently wrong item, or an uncatchable `TypeError`).
     *
     * Being a data class, two `Patched` events are equal only if they hold the same lambda
     * instance.
     */
    data class Patched<T, ID : Any>(
        val identity: ID,
        val transform: (T) -> T,
    ) : CursorReactiveEvent<T, ID>

    /**
     * An item with the given [identity] has been removed from the source.
     *
     * The paginator removes the cached item with the matching identity. The
     * default `removeElement` rebalancing pulls items from the next page to
     * keep the current page at capacity — so a deletion of a single item is
     * reflected accurately as long as the next page is cached.
     *
     * If no cached item matches, the active `UnknownItemPolicy` decides
     * whether the event is dropped or escalated.
     */
    data class Removed<ID : Any>(val identity: ID) : CursorReactiveEvent<Nothing, ID>

    /**
     * A new [item] has appeared in the source at the given [position].
     *
     * The paginator translates [position] into a `(self, index)` and inserts
     * the item there with overflow cascade. See [CursorInsertPosition] for the
     * supported shapes and their tradeoffs.
     *
     * Adapters that cannot determine the position should emit [Invalidate]
     * instead of guessing.
     */
    data class Inserted<T, ID : Any>(
        val item: T,
        val position: CursorInsertPosition<ID>,
    ) : CursorReactiveEvent<T, ID>

    /**
     * The item with the given identity stays in the source but moves to a
     * different position — typically a sort-key change, a drag-to-reorder, or
     * a server-driven re-ranking. [item] is the (possibly updated) value to
     * apply at the new location, so the same event can carry both a move and
     * a field update.
     *
     * Semantically equivalent to a transactional pair `[Removed(id),
     * Inserted(item, position)]`: the paginator opens a
     * [com.jamal_aliev.paginator.cursor.CursorPaginator.transaction], removes
     * the existing item, and inserts [item] at [position]. If either step
     * fails the L1 cache rolls back, so the UI never observes a half-applied
     * move.
     *
     * If the item is not currently cached, the active `UnknownItemPolicy`
     * decides whether the event is dropped or escalated.
     */
    data class Moved<T, ID : Any>(
        val item: T,
        val position: CursorInsertPosition<ID>,
    ) : CursorReactiveEvent<T, ID>

    /**
     * "Something structural happened in the source that I cannot describe."
     *
     * The paginator responds by running `refreshAll()` to re-fetch every
     * cached cursor. This is the safe but expensive fallback — use it when the
     * source signals a bulk change (e.g., truncation, foreign-key cascade
     * delete) that cannot be efficiently expressed as a series of point
     * updates.
     */
    object Invalidate : CursorReactiveEvent<Nothing, Nothing>

    /**
     * Multiple events that happened atomically in the source and should be
     * applied atomically to the paginator.
     *
     * The paginator wraps the application in
     * [com.jamal_aliev.paginator.cursor.CursorPaginator.transaction], so either
     * all events land or the L1 cache is rolled back to its pre-batch state.
     *
     * Batches are **applied recursively inside the same transaction**: a
     * [Batch] element nested inside [events] is itself dispatched to the
     * usual per-event handler, which means its children join the surrounding
     * transaction without opening a new one. Effects are therefore identical
     * to flattening the tree of batches into a single event list — emit
     * whichever shape is more natural for your adapter. Empty batches
     * (recursively) are no-ops.
     */
    data class Batch<T, ID : Any>(
        val events: List<CursorReactiveEvent<T, ID>>,
    ) : CursorReactiveEvent<T, ID>
}
