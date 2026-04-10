package com.jamal_aliev.paginator.strategy

import com.jamal_aliev.paginator.PagingCore
import com.jamal_aliev.paginator.page.PageState

/**
 * A [PagingCore] decorator that enforces an **LRU (Least Recently Used)** eviction policy.
 *
 * When the number of cached pages exceeds [maxSize], the least recently accessed page
 * is evicted. "Access" includes both writes ([setState]) and reads ([getStateOf], [getElement]).
 *
 * Pages within the current context window (`startContextPage..endContextPage`) can optionally
 * be protected from eviction via [protectContextWindow].
 *
 * ## Usage
 * ```kotlin
 * val paginator = MutablePaginator<Item>(
 *     core = LruPagingCore(maxSize = 50),
 *     source = { page -> api.loadPage(page) }
 * )
 * ```
 *
 * @param initialCapacity Expected items per page (passed to [PagingCore]).
 * @param maxSize Maximum number of pages to keep in cache. Must be > 0.
 * @param protectContextWindow If `true` (default), pages within the visible context window
 *   are never evicted, even if they are the least recently used.
 * @param evictionListener Optional listener notified when a page is evicted.
 */
class LruPagingCore<T>(
    initialCapacity: Int = DEFAULT_CAPACITY,
    val maxSize: Int,
    val protectContextWindow: Boolean = true,
    var evictionListener: CacheEvictionListener<T>? = null,
) : PagingCore<T>(initialCapacity) {

    init {
        require(maxSize > 0) { "maxSize must be greater than 0, was $maxSize" }
    }

    /** Tracks page access order: head = least recent, tail = most recent. */
    private val accessOrder = mutableListOf<Int>()

    override fun setState(state: PageState<T>, silently: Boolean) {
        super.setState(state, silently)
        touchPage(state.page)
        performEviction(justAddedPage = state.page)
    }

    override fun getStateOf(page: Int): PageState<T>? {
        val result = super.getStateOf(page)
        if (result != null) touchPage(page)
        return result
    }

    override fun getElement(page: Int, index: Int): T? {
        val result = super.getElement(page, index)
        if (result != null) touchPage(page)
        return result
    }

    override fun removeFromCache(page: Int): PageState<T>? {
        val result = super.removeFromCache(page)
        if (result != null) accessOrder.remove(page)
        return result
    }

    override fun clear() {
        super.clear()
        accessOrder.clear()
    }

    override fun release(capacity: Int, silently: Boolean) {
        super.release(capacity, silently)
        accessOrder.clear()
    }

    /** Moves [page] to the tail of [accessOrder] (most recently accessed). */
    private fun touchPage(page: Int) {
        accessOrder.remove(page)
        accessOrder.add(page)
    }

    /**
     * Evicts least-recently-used pages until [size] <= [maxSize].
     * Pages in the context window are skipped when [protectContextWindow] is `true`.
     *
     * @param justAddedPage The page that was just added — never evicted in the same call
     *   to avoid immediately evicting a freshly inserted page.
     */
    private fun performEviction(justAddedPage: Int = -1) {
        while (size > maxSize) {
            val protectedRange = if (protectContextWindow && isStarted)
                startContextPage..endContextPage else null

            val victim = accessOrder.firstOrNull { page ->
                page != justAddedPage &&
                    (protectedRange == null || page !in protectedRange)
            } ?: break // all pages are protected — cannot evict

            val evicted = super.removeFromCache(victim)
            accessOrder.remove(victim)
            if (evicted != null) {
                logger?.log("LruPagingCore", "evict: page=${evicted.page}")
                evictionListener?.onEvicted(evicted)
            }
        }
    }
}
