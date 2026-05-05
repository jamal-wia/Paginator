package com.jamal_aliev.paginator.cache.eviction

import com.jamal_aliev.paginator.cache.InMemoryPagingCache
import com.jamal_aliev.paginator.cache.PagingCache

import com.jamal_aliev.paginator.extension.withLeaf
import com.jamal_aliev.paginator.logger.LogComponent
import com.jamal_aliev.paginator.logger.debug
import com.jamal_aliev.paginator.page.PageState

/**
 * A [PagingCache] decorator that enforces a **FIFO (First In, First Out)** eviction policy.
 *
 * When the number of cached pages exceeds [maxSize], the oldest page (first inserted)
 * is evicted. Unlike [MostRecentPagingCache], reading a page does **not** change its eviction priority —
 * only the original insertion order matters.
 *
 * Pages within the current context window can optionally be protected from eviction
 * via [protectContextWindow].
 *
 * ## Usage
 * ```kotlin
 * val paginator = MutablePaginator(
 *     pagingCore = PagingCore(
 *         cache = QueuedPagingCache(maxSize = 30),
 *         initialCapacity = 20
 *     ),
 *     load = { page -> api.loadPage(page) }
 * )
 * ```
 *
 * @param cache The inner [PagingCache] to delegate to. Defaults to [InMemoryPagingCache].
 * @param maxSize Maximum number of pages to keep in cache. Must be > 0.
 * @param protectContextWindow If `true` (default), pages within the visible context window
 *   are never evicted.
 * @param evictionListener Optional listener notified when a page is evicted.
 */
class QueuedPagingCache<T>(
    private val cache: PagingCache<T> = InMemoryPagingCache(),
    val maxSize: Int,
    val protectContextWindow: Boolean = true,
    var evictionListener: CacheEvictionListener<T>? = null,
) : PagingCache<T> by cache, ChainablePagingCache<T> {

    override fun replaceLeaf(newLeaf: PagingCache<T>): QueuedPagingCache<T> =
        QueuedPagingCache(
            cache = cache.withLeaf(newLeaf), maxSize = maxSize,
            protectContextWindow = protectContextWindow, evictionListener = evictionListener
        )

    init {
        require(maxSize > 0) { "maxSize must be greater than 0, was $maxSize" }
    }

    /** Tracks page insertion order: head = oldest, tail = newest. */
    private val insertionOrder = mutableListOf<Int>()

    override fun setState(state: PageState<T>, silently: Boolean) {
        val isNew = cache.getStateOf(state.page) == null
        cache.setState(state, silently)
        if (isNew) {
            insertionOrder.add(state.page)
        }
        performEviction(justAddedPage = state.page)
    }

    override fun removeFromCache(page: Int): PageState<T>? {
        val result = cache.removeFromCache(page)
        if (result != null) insertionOrder.remove(page)
        return result
    }

    override fun clear() {
        cache.clear()
        insertionOrder.clear()
    }

    override fun release(capacity: Int, silently: Boolean) {
        cache.release(capacity, silently)
        insertionOrder.clear()
    }

    /**
     * Evicts oldest-inserted pages until [size] <= [maxSize].
     * Pages in the context window are skipped when [protectContextWindow] is `true`.
     *
     * @param justAddedPage The page that was just added — never evicted in the same call.
     */
    private fun performEviction(justAddedPage: Int = -1) {
        while (cache.size > maxSize) {
            val protectedRange = if (protectContextWindow && cache.isStarted)
                cache.startContextPage..cache.endContextPage else null

            val victim = insertionOrder.firstOrNull { page ->
                page != justAddedPage &&
                        cache.getStateOf(page) != null &&
                        (protectedRange == null || page !in protectedRange)
            } ?: break

            val evicted = cache.removeFromCache(victim)
            insertionOrder.remove(victim)
            if (evicted != null) {
                cache.logger.debug(LogComponent.CACHE) { "QueuedPagingCache evict: page=${evicted.page}" }
                evictionListener?.onEvicted(evicted)
            }
        }
    }
}
