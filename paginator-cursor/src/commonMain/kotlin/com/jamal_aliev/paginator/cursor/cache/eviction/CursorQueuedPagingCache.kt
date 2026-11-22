package com.jamal_aliev.paginator.cursor.cache.eviction

import com.jamal_aliev.paginator.core.cache.eviction.CacheEvictionListener
import com.jamal_aliev.paginator.core.logger.LogComponent
import com.jamal_aliev.paginator.core.logger.debug
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.cache.CursorInMemoryPagingCache
import com.jamal_aliev.paginator.cursor.cache.CursorPagingCache
import com.jamal_aliev.paginator.cursor.extension.withLeaf
import com.jamal_aliev.paginator.cursor.page.CursorPageState

/**
 * Slack added to `2 * cache.size` before [CursorQueuedPagingCache] compacts its insertion order, so
 * tiny caches are not compacted on every insert.
 */
private const val STALE_ENTRY_SLACK = 16

/**
 * A [CursorPagingCache] decorator enforcing **FIFO (First In, First Out)** eviction.
 *
 * When the number of cached pages exceeds [maxSize], the page that was added
 * first is evicted. Unlike [CursorMostRecentPagingCache], reads do not affect the
 * eviction priority.
 *
 * When composed over a strategy that removes pages itself (for example
 * [CursorContextWindowPagingCache]), a page removed by the inner strategy and inserted again counts
 * as a new insertion and goes to the tail of the queue; the queue never evicts a page it no longer
 * holds and does not keep a history of removed pages.
 */
class CursorQueuedPagingCache<K : Any, T>(
    private val cache: CursorPagingCache<K, T> = CursorInMemoryPagingCache<K, T>(),
    val maxSize: Int,
    val protectContextWindow: Boolean = true,
    var evictionListener: CacheEvictionListener<T>? = null,
) : CursorPagingCache<K, T> by cache, CursorChainablePagingCache<K, T> {

    override fun replaceLeaf(newLeaf: CursorPagingCache<K, T>): CursorQueuedPagingCache<K, T> =
        CursorQueuedPagingCache(
            cache = cache.withLeaf(newLeaf),
            maxSize = maxSize,
            protectContextWindow = protectContextWindow,
            evictionListener = evictionListener,
        )

    init {
        require(maxSize > 0) { "maxSize must be greater than 0, was $maxSize" }
    }

    /**
     * Tracks insertion order of `self` keys: head = oldest, tail = newest.
     *
     * A set, so a page has at most one entry. An inner strategy can remove pages without telling
     * this decorator; such stale entries are skipped and dropped lazily by [performEviction] and
     * compacted in [setState], so they never decide which page is evicted.
     */
    private val insertionOrder = LinkedHashSet<K>()

    /** Number of entries in the insertion order, stale ones included. For tests, not API. */
    internal val trackedEntryCount: Int get() = insertionOrder.size

    override fun setState(
        cursor: CursorBookmark<K>,
        state: CursorPageState<K, T>,
        silently: Boolean
    ) {
        val isNew = cache.getStateOf(cursor.self) == null
        cache.setState(cursor, state, silently)
        if (isNew) {
            // A page re-added after an inner strategy removed it is a fresh insertion: one entry, at the tail.
            insertionOrder.remove(cursor.self)
            insertionOrder.add(cursor.self)
        }
        // Amortised O(1): the pass runs only after the order has outgrown the cache by a wide margin.
        if (insertionOrder.size > 2 * cache.size + STALE_ENTRY_SLACK) {
            insertionOrder.retainAll(cache.cursors.mapTo(HashSet()) { it.self })
        }
        performEviction(justAdded = cursor.self)
    }

    override fun removeFromCache(self: K): CursorPageState<K, T>? {
        val result = cache.removeFromCache(self)
        if (result != null) insertionOrder.remove(self)
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

    private fun performEviction(justAdded: K) {
        while (cache.size > maxSize) {
            val protectedSet: Set<K>? =
                if (protectContextWindow && cache.isStarted) protectedSelves() else null

            // Oldest live, unprotected page. Entries of pages that are no longer cached are stale
            // (removed by an inner strategy): they are dropped on the way, never chosen.
            var victim: K? = null
            val iterator = insertionOrder.iterator()
            while (iterator.hasNext()) {
                val self = iterator.next()
                if (cache.peekStateOf(self) == null) {
                    iterator.remove()
                } else if (self != justAdded && (protectedSet == null || self !in protectedSet)) {
                    victim = self
                    break
                }
            }
            if (victim == null) break

            val evicted = cache.removeFromCache(victim)
            insertionOrder.remove(victim)
            if (evicted != null) {
                cache.logger.debug(LogComponent.CACHE) {
                    "CursorQueuedPagingCache evict: self=$victim"
                }
                evictionListener?.onEvicted(evicted)
            }
        }
    }

    private fun protectedSelves(): Set<K> {
        val result = HashSet<K>()
        var current = cache.startContextCursor
        val end = cache.endContextCursor
        val visited = HashSet<K>()
        while (current != null && visited.add(current.self)) {
            result.add(current.self)
            if (end != null && current.self == end.self) break
            current = cache.walkForward(current)
        }
        return result
    }
}
