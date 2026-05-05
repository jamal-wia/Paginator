package com.jamal_aliev.paginator.cache

import com.jamal_aliev.paginator.CursorPagingCore.Companion.DEFAULT_CAPACITY
import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.logger.PaginatorLogger
import com.jamal_aliev.paginator.page.PageState

/**
 * L1 cache interface for [com.jamal_aliev.paginator.CursorPaginator].
 *
 * The cache is a **doubly-linked collection** keyed by
 * [CursorBookmark.self]: each entry stores the [PageState] and the full
 * [CursorBookmark] (with `prev`/`next` links). This mirrors how the
 * offset-based [PagingCache] keys entries by `page: Int`, but ordering is
 * obtained by walking `next`/`prev` links rather than by numeric comparison.
 *
 * @param T The type of elements contained in each page.
 * @see CursorChainablePagingCache
 * @see CursorInMemoryPagingCache
 */
interface CursorPagingCache<T> {

    var logger: PaginatorLogger?

    /** The number of pages currently in the cache. */
    val size: Int

    /**
     * All cached bookmarks in head-to-tail order — i.e. following `next` links from
     * the current [head] / the earliest reachable entry.
     *
     * Entries that cannot be reached from [head] (orphaned sub-chains produced by
     * server-side link changes) still appear in the list, appended after the main
     * chain in undefined order.
     */
    val cursors: List<CursorBookmark>

    /** `true` if the context window has been initialised. */
    val isStarted: Boolean

    /** The left (head-side) boundary of the current context window. `null` = not started. */
    var startContextCursor: CursorBookmark?

    /** The right (tail-side) boundary of the current context window. `null` = not started. */
    var endContextCursor: CursorBookmark?

    /** Stores a page state under the [cursor]'s `self` key (replacing existing if present). */
    fun setState(cursor: CursorBookmark, state: PageState<T>, silently: Boolean = false)

    /** Retrieves the cached state for the page identified by [self], or `null`. */
    fun getStateOf(self: Any): PageState<T>?

    /** Retrieves the cached [CursorBookmark] for the page identified by [self], or `null`. */
    fun getCursorOf(self: Any): CursorBookmark?

    /** Returns a single element at [index] within the page identified by [self], or `null`. */
    fun getElement(self: Any, index: Int): T?

    /** Removes the page identified by [self] from the cache and returns its state, or `null`. */
    fun removeFromCache(self: Any): PageState<T>?

    /** Removes every page from the cache. */
    fun clear()

    /** Resets the cache to its initial state with the given [capacity]. */
    fun release(capacity: Int = DEFAULT_CAPACITY, silently: Boolean = false)

    /** The first cached bookmark in head-to-tail order, or `null` if empty. */
    fun head(): CursorBookmark?

    /** The last cached bookmark in head-to-tail order, or `null` if empty. */
    fun tail(): CursorBookmark?

    /**
     * Returns the cached cursor that [from] links to via `from.next`, or `null` if
     * `from.next` is missing from the cache (or `from.next == null`).
     */
    fun walkForward(from: CursorBookmark): CursorBookmark?

    /**
     * Returns the cached cursor that [from] links to via `from.prev`, or `null` if
     * `from.prev` is missing from the cache (or `from.prev == null`).
     */
    fun walkBackward(from: CursorBookmark): CursorBookmark?
}
