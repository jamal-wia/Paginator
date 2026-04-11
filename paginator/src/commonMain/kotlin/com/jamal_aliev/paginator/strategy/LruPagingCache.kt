package com.jamal_aliev.paginator.strategy

import com.jamal_aliev.paginator.page.PageState

/**
 * A [PagingCache] decorator that enforces an **LRU (Least Recently Used)** eviction policy.
 *
 * When the number of cached pages exceeds [maxSize], the least recently accessed page
 * is evicted. "Access" includes both writes ([setState]) and reads ([getStateOf], [getElement]).
 *
 * Pages within the current context window (`startContextPage..endContextPage`) can optionally
 * be protected from eviction via [protectContextWindow].
 *
 * ## Usage
 * ```kotlin
 * val paginator = MutablePaginator(
 *     pagingCore = PagingCore(
 *         cache = LruPagingCache(maxSize = 50),
 *         initialCapacity = 20
 *     ),
 *     source = { page -> api.loadPage(page) }
 * )
 * ```
 *
 * ## Composition
 * ```kotlin
 * val paginator = MutablePaginator(
 *     pagingCore = PagingCore(
 *         cache = LruPagingCache(
 *             delegate = TtlPagingCache(ttl = 5.minutes),
 *             maxSize = 50
 *         )
 *     ),
 *     source = { page -> api.loadPage(page) }
 * )
 * ```
 *
 * @param cache The inner [PagingCache] to delegate to. Defaults to [DefaultPagingCache].
 * @param maxSize Maximum number of pages to keep in cache. Must be > 0.
 * @param protectContextWindow If `true` (default), pages within the visible context window
 *   are never evicted, even if they are the least recently used.
 * @param evictionListener Optional listener notified when a page is evicted.
 */
class LruPagingCache<T>(
    private val cache: PagingCache<T> = DefaultPagingCache(),
    val maxSize: Int,
    val protectContextWindow: Boolean = true,
    var evictionListener: CacheEvictionListener<T>? = null,
) : PagingCache<T> by cache, WrappablePagingCache<T> {

    override fun replaceLeaf(newLeaf: PagingCache<T>): LruPagingCache<T> =
        LruPagingCache(cache = cache.withLeaf(newLeaf), maxSize = maxSize,
            protectContextWindow = protectContextWindow, evictionListener = evictionListener)

    init {
        require(maxSize > 0) { "maxSize must be greater than 0, was $maxSize" }
    }

    /** Tracks page access order: head = least recent, tail = most recent. */
    private val accessOrder = mutableListOf<Int>()

    override fun setState(state: PageState<T>, silently: Boolean) {
        cache.setState(state, silently)
        touchPage(state.page)
        performEviction(justAddedPage = state.page)
    }

    override fun getStateOf(page: Int): PageState<T>? {
        val result = cache.getStateOf(page)
        if (result != null) touchPage(page)
        return result
    }

    override fun getElement(page: Int, index: Int): T? {
        val result = cache.getElement(page, index)
        if (result != null) touchPage(page)
        return result
    }

    override fun removeFromCache(page: Int): PageState<T>? {
        val result = cache.removeFromCache(page)
        accessOrder.remove(page)
        return result
    }

    override fun clear() {
        cache.clear()
        accessOrder.clear()
    }

    override fun release(capacity: Int, silently: Boolean) {
        cache.release(capacity, silently)
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
        while (cache.size > maxSize) {
            val protectedRange = if (protectContextWindow && cache.isStarted)
                cache.startContextPage..cache.endContextPage else null

            val victim = accessOrder.firstOrNull { page ->
                page != justAddedPage &&
                    cache.getStateOf(page) != null &&
                    (protectedRange == null || page !in protectedRange)
            } ?: break // all pages are protected — cannot evict

            val evicted = cache.removeFromCache(victim)
            accessOrder.remove(victim)
            if (evicted != null) {
                cache.logger?.log("LruPagingCache", "evict: page=${evicted.page}")
                evictionListener?.onEvicted(evicted)
            }
        }
    }
}
