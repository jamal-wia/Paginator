package com.jamal_aliev.paginator.core.cache.reactive
/**
 * A change observed in a reactive data source, delivered to a
 * [com.jamal_aliev.paginator.MutablePaginator] via [PaginatorReactiveCache.changes].
 *
 * Each event names *what* changed in the source's coordinate system.
 * The paginator translates the event to the corresponding CRUD operation on
 * its L1 cache, using [PaginatorReactiveCache.identity] to locate items
 * already cached.
 *
 * **Variance:** the type is declared `out T, out ID` so that an adapter for a
 * base type can be passed to a paginator of the same type without extra
 * wrappers, and so that variants that do not carry an item ([Removed],
 * [Invalidate]) are assignable everywhere a typed event is expected.
 *
 * **Identity typing:** the [ID] type parameter binds the identity carried by
 * [Removed], [Moved], and the `*Identity` variants of [InsertPosition] to the
 * same type [PaginatorReactiveCache.identity] returns. This prevents the
 * common `Removed(42)` vs `Long` mismatch that silently fails to match a
 * cached row.
 *
 * @see PaginatorReactiveCache
 * @see InsertPosition
 */
sealed interface ReactiveEvent<out T, out ID : Any> {

    /**
     * An item with the same identity as [item] has changed in the source.
     *
     * The paginator looks up the cached item by
     * `cache.identity(cached) == cache.identity(item)` and replaces it in
     * place. The page index does not move — only the item content changes.
     *
     * If no cached item matches, the active `UnknownItemPolicy` decides
     * whether the event is dropped or escalated.
     */
    data class Updated<T>(val item: T) : ReactiveEvent<T, Nothing>

    /**
     * An item with the given [identity] has been removed from the source.
     *
     * The paginator removes the cached item with the matching identity. The
     * default `removeElement` rebalancing pulls items from the next page to
     * keep the previous page at capacity — so a deletion of a single item is
     * reflected accurately as long as the next page is cached.
     *
     * If no cached item matches, the active `UnknownItemPolicy` decides
     * whether the event is dropped or escalated.
     */
    data class Removed<ID : Any>(val identity: ID) : ReactiveEvent<Nothing, ID>

    /**
     * A new [item] has appeared in the source at the given [position].
     *
     * The paginator translates [position] into a `(page, index)` and inserts
     * the item there with overflow cascade. See [InsertPosition] for the
     * supported shapes and their tradeoffs.
     *
     * Adapters that cannot determine the position should emit [Invalidate]
     * instead of guessing.
     */
    data class Inserted<T, ID : Any>(
        val item: T,
        val position: InsertPosition<ID>,
    ) : ReactiveEvent<T, ID>

    /**
     * The item with the given identity stays in the source but moves to a
     * different position — typically a sort-key change, a drag-to-reorder, or
     * a server-driven re-ranking. [item] is the (possibly updated) value to
     * apply at the new location, so the same event can carry both a move and
     * a field update.
     *
     * Semantically equivalent to a transactional pair `[Removed(id),
     * Inserted(item, position)]`: the paginator opens a
     * [com.jamal_aliev.paginator.Paginator.transaction], removes the existing
     * item, and inserts [item] at [position]. If either step fails the L1
     * cache rolls back, so the UI never observes a half-applied move.
     *
     * If the item is not currently cached, the active `UnknownItemPolicy`
     * decides whether the event is dropped or escalated.
     */
    data class Moved<T, ID : Any>(
        val item: T,
        val position: InsertPosition<ID>,
    ) : ReactiveEvent<T, ID>

    /**
     * "Something structural happened in the source that I cannot describe."
     *
     * The paginator responds by running `refreshAll()` to re-fetch every
     * cached page. This is the safe but expensive fallback — use it when the
     * source signals a bulk change (e.g., truncation, foreign-key cascade
     * delete) that cannot be efficiently expressed as a series of point
     * updates.
     */
    object Invalidate : ReactiveEvent<Nothing, Nothing>

    /**
     * Multiple events that happened atomically in the source and should be
     * applied atomically to the paginator.
     *
     * The paginator wraps the application in
     * [com.jamal_aliev.paginator.Paginator.transaction], so either all events
     * land or the L1 cache is rolled back to its pre-batch state.
     *
     * Batches are **applied recursively inside the same transaction**: a
     * [Batch] element nested inside [events] is itself dispatched to the
     * usual per-event handler, which means its children join the surrounding
     * transaction without opening a new one. Effects are therefore identical
     * to flattening the tree of batches into a single event list — emit
     * whichever shape is more natural for your adapter. Empty batches
     * (recursively) are no-ops.
     */
    data class Batch<T, ID : Any>(val events: List<ReactiveEvent<T, ID>>) : ReactiveEvent<T, ID>
}
