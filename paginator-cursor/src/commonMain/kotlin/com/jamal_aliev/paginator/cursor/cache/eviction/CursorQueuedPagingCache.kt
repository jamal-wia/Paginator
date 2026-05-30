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
 * A [CursorPagingCache] decorator enforcing **FIFO (First In, First Out)** eviction.
 *
 * When the number of cached pages exceeds [maxSize], the page that was added
 * first is evicted. Unlike [CursorMostRecentPagingCache], reads do not affect the
 * eviction priority.
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

    private val insertionOrder = mutableListOf<K>()

    override fun setState(
        cursor: CursorBookmark<K>,
        state: CursorPageState<K, T>,
        silently: Boolean
    ) {
        val isNew = cache.getStateOf(cursor.self) == null
        cache.setState(cursor, state, silently)
        if (isNew) insertionOrder.add(cursor.self)
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

            val victim = insertionOrder.firstOrNull { self ->
                self != justAdded &&
                        cache.getStateOf(self) != null &&
                        (protectedSet == null || self !in protectedSet)
            } ?: break

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
