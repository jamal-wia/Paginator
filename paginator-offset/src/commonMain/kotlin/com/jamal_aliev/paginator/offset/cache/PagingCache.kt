package com.jamal_aliev.paginator.offset.cache

import com.jamal_aliev.paginator.core.logger.PaginatorLogger
import com.jamal_aliev.paginator.offset.page.OffsetPageState

/**
 * A minimal interface exposing only the cache operations needed by eviction strategies.
 *
 * [InMemoryPagingCache] provides the standard sorted-list implementation.
 * Each eviction strategy also implements this interface via Kotlin `by` delegation,
 * enabling arbitrary composition:
 *
 * ```kotlin
 * val paginator = MutablePaginator(
 *     pagingCore = PagingCore(
 *         cache = MostRecentPagingCache(
 *             delegate = TimeLimitedPagingCache(ttl = 5.minutes),
 *             maxSize = 50
 *         )
 *     ),
 *     load = { ... }
 * )
 * ```
 *
 * @param T The type of elements contained in each page.
 */
interface PagingCache<T> {

    var logger: PaginatorLogger?

    /** The number of pages currently in the cache. */
    val size: Int

    /** All cached page numbers, sorted in ascending order. */
    val pages: List<Int>

    /** `true` if the context window has been initialized (both boundaries are non-zero). */
    val isStarted: Boolean

    /** The left (lowest) boundary of the current context window. `0` = not started. */
    var startContextPage: Int

    /** The right (highest) boundary of the current context window. `0` = not started. */
    var endContextPage: Int

    /** Stores a page state in the cache (replaces existing if present). */
    fun setState(state: OffsetPageState<T>, silently: Boolean = false)

    /** Retrieves the cached state for [page], or `null` if not cached. */
    fun getStateOf(page: Int): OffsetPageState<T>?

    /**
     * Replaces the state of a page **already in the cache** without counting the write as a use of
     * the page — for background updates that did not come from the user, such as a live database
     * query of `observePages` re-emitting.
     *
     * Eviction strategies that track usage override it so the write leaves their bookkeeping
     * untouched: [MostRecentPagingCache][com.jamal_aliev.paginator.offset.cache.eviction.MostRecentPagingCache]
     * keeps the page's place in its recency order and
     * [TimeLimitedPagingCache][com.jamal_aliev.paginator.offset.cache.eviction.TimeLimitedPagingCache]
     * keeps its TTL timer. Strategies delegating with `by` forward it to the next cache in the chain.
     * The default is a plain [setState], which is correct for any cache that tracks no usage.
     */
    fun setStateUntracked(state: OffsetPageState<T>, silently: Boolean = false) {
        setState(state, silently)
    }

    /**
     * Reads the state of [page] like [getStateOf] but without counting the read as a use of the
     * page (see [setStateUntracked]) — for internal checks that are not user access. The default is
     * a plain [getStateOf].
     */
    fun peekStateOf(page: Int): OffsetPageState<T>? = getStateOf(page)

    /** Returns a single element at [index] within [page], or `null` if not found. */
    fun getElement(page: Int, index: Int): T?

    /** Removes [page] from the cache and returns its state, or `null` if absent. */
    fun removeFromCache(page: Int): OffsetPageState<T>?

    /** Removes all pages from the cache. */
    fun clear()

    /** Resets the cache to its initial state with the given [capacity]. */
    fun release(capacity: Int = DEFAULT_CAPACITY, silently: Boolean = false)

    companion object {
        /** Default per-page capacity used by paging cores and caches across the suite. */
        const val DEFAULT_CAPACITY: Int = 20

        /** Sentinel value disabling capacity checks entirely. */
        const val UNLIMITED_CAPACITY: Int = 0
    }
}
