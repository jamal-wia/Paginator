package com.jamal_aliev.paginator.cache

import com.jamal_aliev.paginator.logger.PaginatorLogger
import com.jamal_aliev.paginator.page.PageState

/**
 * A default [PagingCache] implementation providing sorted, binary-search-backed
 * page storage with no eviction logic.
 *
 * This is the leaf node of any cache strategy chain. Eviction strategies
 * ([QueuedPagingCache], [MostRecentPagingCache], [TimeLimitedPagingCache], [ContextWindowPagingCache])
 * delegate to this class (or to each other, with [InMemoryPagingCache] at the bottom).
 *
 * ## Usage
 * ```kotlin
 * // Standalone (no eviction)
 * val paginator = MutablePaginator(
 *     pagingCore = PagingCore(cache = InMemoryPagingCache()),
 *     load = { page -> api.loadPage(page) }
 * )
 *
 * // With eviction strategy
 * val paginator = MutablePaginator(
 *     pagingCore = PagingCore(
 *         cache = MostRecentPagingCache(delegate = InMemoryPagingCache(), maxSize = 50)
 *     ),
 *     load = { page -> api.loadPage(page) }
 * )
 * ```
 *
 * @param T The type of elements contained in each page.
 */
class InMemoryPagingCache<T> : PagingCache<T> {

    override var logger: PaginatorLogger? = null

    /** Internal sorted cache of page states, ordered by page number. */
    private val cache = mutableListOf<PageState<T>>()

    /**
     * Finds the index of a page in [cache] via binary search.
     *
     * @return A non-negative index if found, or a negative insertion-point encoding
     *   `-(insertionPoint + 1)` if not found (same semantics as [List.binarySearch]).
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun searchIndexOfPage(page: Int): Int {
        return cache.binarySearch { it.page.compareTo(page) }
    }

    override val pages: List<Int> get() = cache.map { it.page }

    override val size: Int get() = cache.size

    override var startContextPage: Int = 0

    override var endContextPage: Int = 0

    override val isStarted: Boolean get() = startContextPage > 0 && endContextPage > 0

    override fun setState(state: PageState<T>, silently: Boolean) {
        val index = searchIndexOfPage(state.page)
        if (index >= 0) {
            cache[index] = state
        } else {
            cache.add(-(index + 1), state)
        }
    }

    override fun getStateOf(page: Int): PageState<T>? {
        val index = searchIndexOfPage(page)
        return if (index >= 0) cache[index] else null
    }

    override fun getElement(page: Int, index: Int): T? {
        return getStateOf(page)?.data?.get(index)
    }

    override fun removeFromCache(page: Int): PageState<T>? {
        val index = searchIndexOfPage(page)
        return if (index >= 0) cache.removeAt(index) else null
    }

    override fun clear() {
        cache.clear()
    }

    override fun release(capacity: Int, silently: Boolean) {
        cache.clear()
        startContextPage = 0
        endContextPage = 0
    }

    override fun toString(): String = "InMemoryPagingCache(pages=$cache)"

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = super.hashCode()
}
