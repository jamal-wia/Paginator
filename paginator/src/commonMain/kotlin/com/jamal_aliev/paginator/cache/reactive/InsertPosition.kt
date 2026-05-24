package com.jamal_aliev.paginator.cache.reactive

/**
 * Describes where an inserted item should land in the paginator's L1 cache.
 *
 * Inserts are fundamentally ambiguous without ordering information — the
 * paginator stores its data as opaque pages and has no knowledge of the
 * underlying domain's sort key. The author of a [PaginatorReactiveCache]
 * implementation must therefore decide, per event, where the new item belongs
 * and encode that decision in the [InsertPosition].
 *
 * If a meaningful position cannot be computed (for example, when the
 * underlying ordering is too complex to express as one of these shapes), prefer
 * emitting [ReactiveEvent.Invalidate] over guessing — the paginator will then
 * re-fetch the affected pages and the source-of-truth ordering wins.
 *
 * The [ID] type parameter is carried so that [AfterIdentity] / [BeforeIdentity]
 * anchors are checked against the same identity type the
 * [PaginatorReactiveCache] uses, eliminating a class of silent typing bugs
 * (e.g. `AfterIdentity(42)` against a `Long` PK).
 */
sealed interface InsertPosition<out ID : Any> {

    /**
     * Prepend the new item to the front of the first cached page.
     *
     * Useful for "newest first" feeds where every insert lands at the top.
     */
    object Head : InsertPosition<Nothing>

    /**
     * Append the new item to the end of the last cached page.
     *
     * Useful for "oldest first" feeds where every insert lands at the bottom.
     */
    object Tail : InsertPosition<Nothing>

    /**
     * Insert the new item at an explicit `(page, index)` coordinate within the
     * paginator's cache.
     *
     * Use when the source can compute the absolute position deterministically
     * (rare). Most adapters should prefer [AfterIdentity]/[BeforeIdentity], which
     * survive page rebalancing.
     */
    data class At(val page: Int, val index: Int) : InsertPosition<Nothing>

    /**
     * Insert the new item immediately **after** the cached item whose
     * [PaginatorReactiveCache.identity] equals [identity].
     *
     * If the anchor is not present in any cached page, the event is dropped or
     * escalated to a refresh according to the active `UnknownItemPolicy`.
     */
    data class AfterIdentity<out ID : Any>(val identity: ID) : InsertPosition<ID>

    /**
     * Insert the new item immediately **before** the cached item whose
     * [PaginatorReactiveCache.identity] equals [identity].
     *
     * If the anchor is not present in any cached page, the event is dropped or
     * escalated to a refresh according to the active `UnknownItemPolicy`.
     */
    data class BeforeIdentity<out ID : Any>(val identity: ID) : InsertPosition<ID>
}
