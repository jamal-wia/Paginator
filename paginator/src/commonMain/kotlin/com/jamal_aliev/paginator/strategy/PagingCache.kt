package com.jamal_aliev.paginator.strategy

import com.jamal_aliev.paginator.PagingCore
import com.jamal_aliev.paginator.PagingCore.Companion.DEFAULT_CAPACITY
import com.jamal_aliev.paginator.logger.PaginatorLogger
import com.jamal_aliev.paginator.page.PageState

/**
 * A minimal interface exposing only the cache operations needed by eviction strategies.
 *
 * [com.jamal_aliev.paginator.PagingCore] implements this interface, allowing it to be
 * passed as a delegate to eviction strategies. Each strategy also implements this
 * interface via Kotlin `by` delegation, enabling arbitrary composition:
 *
 * ```kotlin
 * val paginator = MutablePaginator(
 *     core = LruPagingCore(
 *         delegate = TtlPagingCore(delegate = PagingCore(20), ttl = 5.minutes),
 *         maxSize = 50
 *     ),
 *     source = { ... }
 * )
 * ```
 *
 * @param T The type of elements contained in each page.
 */
interface PagingCache<T> {

    /**
     * The underlying [PagingCore] at the bottom of the delegation chain.
     *
     * Strategies delegate this automatically via `by delegate`.
     * [PagingCore] returns `this`.
     */
    val pagingCore: PagingCore<T>

    var logger: PaginatorLogger?

    /** The number of pages currently in the cache. */
    val size: Int

    /** All cached page numbers, sorted in ascending order. */
    val pages: List<Int>

    /** `true` if the context window has been initialized (both boundaries are non-zero). */
    val isStarted: Boolean

    /** The left (lowest) boundary of the current context window. `0` = not started. */
    val startContextPage: Int

    /** The right (highest) boundary of the current context window. `0` = not started. */
    val endContextPage: Int

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
