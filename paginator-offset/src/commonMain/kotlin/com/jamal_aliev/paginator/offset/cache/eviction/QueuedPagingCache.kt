package com.jamal_aliev.paginator.offset.cache.eviction

import com.jamal_aliev.paginator.core.cache.eviction.CacheEvictionListener
import com.jamal_aliev.paginator.core.logger.LogComponent
import com.jamal_aliev.paginator.core.logger.debug
import com.jamal_aliev.paginator.offset.cache.InMemoryPagingCache
import com.jamal_aliev.paginator.offset.cache.PagingCache
import com.jamal_aliev.paginator.offset.extension.withLeaf
import com.jamal_aliev.paginator.offset.page.OffsetPageState

/**
 * Slack added to `2 * cache.size` before [QueuedPagingCache] compacts its insertion order, so tiny
 * caches are not compacted on every insert.
 */
private const val STALE_ENTRY_SLACK = 16

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
 * When composed over a strategy that removes pages itself (for example [ContextWindowPagingCache]),
 * a page removed by the inner strategy and inserted again counts as a new insertion and goes to the
 * tail of the queue; the queue never evicts a page it no longer holds and does not keep a history
 * of removed pages.
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
    private val cache: PagingCache<T> = InMemoryPagingCache<T>(),
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

    /**
     * Tracks page insertion order: head = oldest, tail = newest.
     *
     * A set, so a page has at most one entry. An inner strategy can remove pages without telling
     * this decorator; such stale entries are skipped and dropped lazily by [performEviction] and
     * compacted in [setState], so they never decide which page is evicted.
     */
    private val insertionOrder = LinkedHashSet<Int>()

    /** Number of entries in the insertion order, stale ones included. For tests, not API. */
    internal val trackedEntryCount: Int get() = insertionOrder.size

    override fun setState(state: OffsetPageState<T>, silently: Boolean) {
        val isNew = cache.getStateOf(state.page) == null
        cache.setState(state, silently)
        if (isNew) {
            // A page re-added after an inner strategy removed it is a fresh insertion: one entry, at the tail.
            insertionOrder.remove(state.page)
            insertionOrder.add(state.page)
        }
        // Amortised O(1): the pass runs only after the order has outgrown the cache by a wide margin.
        if (insertionOrder.size > 2 * cache.size + STALE_ENTRY_SLACK) {
            insertionOrder.retainAll(cache.pages.toHashSet())
        }
        performEviction(justAddedPage = state.page)
    }

    override fun removeFromCache(page: Int): OffsetPageState<T>? {
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

            // Oldest live, unprotected page. Entries of pages that are no longer cached are stale
            // (removed by an inner strategy): they are dropped on the way, never chosen.
            var victim: Int? = null
            val iterator = insertionOrder.iterator()
            while (iterator.hasNext()) {
                val page = iterator.next()
                if (cache.peekStateOf(page) == null) {
                    iterator.remove()
                } else if (page != justAddedPage && (protectedRange == null || page !in protectedRange)) {
                    victim = page
                    break
                }
            }
            if (victim == null) break

            val evicted = cache.removeFromCache(victim)
            insertionOrder.remove(victim)
            if (evicted != null) {
                cache.logger.debug(LogComponent.CACHE) { "QueuedPagingCache evict: page=${evicted.page}" }
                evictionListener?.onEvicted(evicted)
            }
        }
    }
}
