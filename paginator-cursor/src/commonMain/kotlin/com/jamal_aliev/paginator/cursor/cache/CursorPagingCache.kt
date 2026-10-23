package com.jamal_aliev.paginator.cursor.cache

import com.jamal_aliev.paginator.core.logger.PaginatorLogger
import com.jamal_aliev.paginator.cursor.CursorPagingCore.Companion.DEFAULT_CAPACITY
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.page.CursorPageState

/**
 * L1 cache interface for [com.jamal_aliev.paginator.cursor.CursorPaginator].
 *
 * The cache is a **doubly-linked collection** keyed by
 * [CursorBookmark<K>.self]: each entry stores the [PageState] and the full
 * [CursorBookmark<K>] (with `prev`/`next` links). This mirrors how the
 * offset-based [PagingCache] keys entries by `page: Int`, but ordering is
 * obtained by walking `next`/`prev` links rather than by numeric comparison.
 *
 * @param T The type of elements contained in each page.
 * @see CursorChainablePagingCache
 * @see CursorInMemoryPagingCache
 */
interface CursorPagingCache<K : Any, T> {

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
    val cursors: List<CursorBookmark<K>>

    /** `true` if the context window has been initialised. */
    val isStarted: Boolean

    /** The left (head-side) boundary of the current context window. `null` = not started. */
    var startContextCursor: CursorBookmark<K>?

    /** The right (tail-side) boundary of the current context window. `null` = not started. */
    var endContextCursor: CursorBookmark<K>?

    /** Stores a page state under the [cursor]'s `self` key (replacing existing if present). */
    fun setState(cursor: CursorBookmark<K>, state: CursorPageState<K, T>, silently: Boolean = false)

    /** Retrieves the cached state for the page identified by [self], or `null`. */
    fun getStateOf(self: K): CursorPageState<K, T>?

    /**
     * Replaces the state of a page **already in the cache** without counting the write as a use of
     * the page — for background updates that did not come from the user, such as a reactive
     * source's events applied by `observe`.
     *
     * Eviction strategies that track usage override it so the write leaves their bookkeeping
     * untouched: [CursorMostRecentPagingCache][com.jamal_aliev.paginator.cursor.cache.eviction.CursorMostRecentPagingCache]
     * keeps the page's place in its recency order and
     * [CursorTimeLimitedPagingCache][com.jamal_aliev.paginator.cursor.cache.eviction.CursorTimeLimitedPagingCache]
     * keeps its TTL timer. Strategies delegating with `by` forward it to the next cache in the chain.
     * The default is a plain [setState], which is correct for any cache that tracks no usage.
     */
    fun setStateUntracked(cursor: CursorBookmark<K>, state: CursorPageState<K, T>, silently: Boolean = false) {
        setState(cursor, state, silently)
    }

    /**
     * Reads the state of the page [self] like [getStateOf] but without counting the read as a use of
     * the page (see [setStateUntracked]) — for internal checks that are not user access. The default
     * is a plain [getStateOf].
     */
    fun peekStateOf(self: K): CursorPageState<K, T>? = getStateOf(self)

    /** Retrieves the cached [CursorBookmark<K>] for the page identified by [self], or `null`. */
    fun getCursorOf(self: K): CursorBookmark<K>?

    /** Returns a single element at [index] within the page identified by [self], or `null`. */
    fun getElement(self: K, index: Int): T?

    /** Removes the page identified by [self] from the cache and returns its state, or `null`. */
    fun removeFromCache(self: K): CursorPageState<K, T>?

    /** Removes every page from the cache. */
    fun clear()

    /** Resets the cache to its initial state with the given [capacity]. */
    fun release(capacity: Int = DEFAULT_CAPACITY, silently: Boolean = false)

    /** The first cached bookmark in head-to-tail order, or `null` if empty. */
    fun head(): CursorBookmark<K>?

    /** The last cached bookmark in head-to-tail order, or `null` if empty. */
    fun tail(): CursorBookmark<K>?

    /**
     * Returns the cached cursor that [from] links to via `from.next`, or `null` if
     * `from.next` is missing from the cache (or `from.next == null`).
     */
    fun walkForward(from: CursorBookmark<K>): CursorBookmark<K>?

    /**
     * Returns the cached cursor that [from] links to via `from.prev`, or `null` if
     * `from.prev` is missing from the cache (or `from.prev == null`).
     */
    fun walkBackward(from: CursorBookmark<K>): CursorBookmark<K>?
}
