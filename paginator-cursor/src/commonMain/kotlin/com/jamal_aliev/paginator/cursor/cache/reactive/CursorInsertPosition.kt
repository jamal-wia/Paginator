package com.jamal_aliev.paginator.cursor.cache.reactive

/**
 * Cursor-paginator counterpart of
 * [com.jamal_aliev.paginator.core.cache.reactive.InsertPosition].
 *
 * Describes where an inserted item should land in the cursor paginator's L1
 * cache. Inserts are fundamentally ambiguous without ordering information —
 * the paginator stores its data as opaque pages keyed by an `Any` `self` key
 * and has no knowledge of the underlying domain's sort key. The author of a
 * [CursorPaginatorReactiveCache] implementation therefore decides, per event,
 * where the new item belongs and encodes that decision in the
 * [CursorInsertPosition].
 *
 * Differs from the offset variant only in [At], which addresses a page by its
 * cursor `self` key instead of a numeric page index — there is no numeric page
 * index in the cursor-based layout.
 *
 * If a meaningful position cannot be computed (for example, when the
 * underlying ordering is too complex to express as one of these shapes), prefer
 * emitting [CursorReactiveEvent.Invalidate] over guessing — the paginator will
 * then re-fetch the affected pages and the source-of-truth ordering wins.
 *
 * The [ID] type parameter is carried so that [AfterIdentity] / [BeforeIdentity]
 * anchors are checked against the same identity type the
 * [CursorPaginatorReactiveCache] uses, eliminating a class of silent typing
 * bugs (e.g. `AfterIdentity(42)` against a `Long` PK).
 */
sealed interface CursorInsertPosition<out ID : Any> {

    /**
     * Prepend the new item to the front of the head cached page.
     *
     * Useful for "newest first" feeds where every insert lands at the top.
     */
    object Head : CursorInsertPosition<Nothing>

    /**
     * Append the new item to the end of the tail cached page.
     *
     * Useful for "oldest first" feeds where every insert lands at the bottom.
     */
    object Tail : CursorInsertPosition<Nothing>

    /**
     * Insert the new item at an explicit `(self, index)` coordinate within the
     * paginator's cache.
     *
     * Use when the source can compute the absolute position deterministically.
     * Most adapters should prefer [AfterIdentity] / [BeforeIdentity], which
     * survive page rebalancing better.
     *
     * If [self] is not in the cache the event cannot land and falls through to
     * the active `UnknownItemPolicy` — the cursor paginator never synthesises
     * a missing page on insert.
     */
    data class At(val self: Any, val index: Int) : CursorInsertPosition<Nothing>

    /**
     * Insert the new item immediately **after** the cached item whose
     * [CursorPaginatorReactiveCache.identity] equals [identity].
     *
     * If the anchor is not present in any cached page, the event is dropped or
     * escalated to a refresh according to the active `UnknownItemPolicy`.
     */
    data class AfterIdentity<out ID : Any>(val identity: ID) : CursorInsertPosition<ID>

    /**
     * Insert the new item immediately **before** the cached item whose
     * [CursorPaginatorReactiveCache.identity] equals [identity].
     *
     * If the anchor is not present in any cached page, the event is dropped or
     * escalated to a refresh according to the active `UnknownItemPolicy`.
     */
    data class BeforeIdentity<out ID : Any>(val identity: ID) : CursorInsertPosition<ID>
}
