package com.jamal_aliev.paginator.cache

import com.jamal_aliev.paginator.PagingCore.Companion.DEFAULT_CAPACITY
import com.jamal_aliev.paginator.logger.PaginatorLogger
import com.jamal_aliev.paginator.page.PageState

/**
 * A minimal interface exposing only the cache operations needed by eviction strategies.
 *
 * [DefaultPagingCache] provides the standard sorted-list implementation.
 * Each eviction strategy also implements this interface via Kotlin `by` delegation,
 * enabling arbitrary composition:
 *
 * ```kotlin
 * val paginator = MutablePaginator(
 *     pagingCore = PagingCore(
 *         cache = LruPagingCache(
 *             delegate = TtlPagingCache(ttl = 5.minutes),
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
    fun setState(state: PageState<T>, silently: Boolean = false)

    /** Retrieves the cached state for [page], or `null` if not cached. */
    fun getStateOf(page: Int): PageState<T>?

    /** Returns a single element at [index] within [page], or `null` if not found. */
    fun getElement(page: Int, index: Int): T?

    /** Removes [page] from the cache and returns its state, or `null` if absent. */
    fun removeFromCache(page: Int): PageState<T>?

    /** Removes all pages from the cache. */
    fun clear()

    /** Resets the cache to its initial state with the given [capacity]. */
    fun release(capacity: Int = DEFAULT_CAPACITY, silently: Boolean = false)
}
