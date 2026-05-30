package com.jamal_aliev.paginator.cursor.cache.eviction

import com.jamal_aliev.paginator.core.cache.eviction.CacheEvictionListener
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.cache.CursorInMemoryPagingCache
import com.jamal_aliev.paginator.cursor.cache.CursorPagingCache
import com.jamal_aliev.paginator.cursor.extension.withLeaf
import com.jamal_aliev.paginator.core.logger.LogComponent
import com.jamal_aliev.paginator.core.logger.debug
import com.jamal_aliev.paginator.cursor.page.CursorPageState

/**
 * A [CursorPagingCache] decorator that enforces an **LRU (Least Recently Used)**
 * eviction policy, keyed by [CursorBookmark<K>.self].
 *
 * When the number of cached pages exceeds [maxSize], the page that has been
 * accessed least recently (either written via [setState] or read via [getStateOf] /
 * [getElement]) is evicted.
 *
 * Pages whose bookmarks sit on the chain from [startContextCursor] to
 * [endContextCursor] (walking `next`) are protected from eviction when
 * [protectContextWindow] is `true`.
 */
class CursorMostRecentPagingCache<K : Any, T>(
    private val cache: CursorPagingCache<K, T> = CursorInMemoryPagingCache<K, T>(),
    val maxSize: Int,
    val protectContextWindow: Boolean = true,
    var evictionListener: CacheEvictionListener<T>? = null,
) : CursorPagingCache<K, T> by cache, CursorChainablePagingCache<K, T> {

    override fun replaceLeaf(newLeaf: CursorPagingCache<K, T>): CursorMostRecentPagingCache<K, T> =
        CursorMostRecentPagingCache(
            cache = cache.withLeaf(newLeaf),
            maxSize = maxSize,
            protectContextWindow = protectContextWindow,
            evictionListener = evictionListener,
        )

    init {
        require(maxSize > 0) { "maxSize must be greater than 0, was $maxSize" }
    }

    /** Access order: head = least recent, tail = most recent. Stores `self` keys. */
    private val accessOrder = mutableListOf<K>()

    override fun setState(
        cursor: CursorBookmark<K>,
        state: CursorPageState<K, T>,
        silently: Boolean
    ) {
        cache.setState(cursor, state, silently)
        touch(cursor.self)
        performEviction(justAdded = cursor.self)
    }

    override fun getStateOf(self: K): CursorPageState<K, T>? {
        val result = cache.getStateOf(self)
        if (result != null) touch(self)
        return result
    }

    override fun getElement(self: K, index: Int): T? {
        val result = cache.getElement(self, index)
        if (result != null) touch(self)
        return result
    }

    override fun removeFromCache(self: K): CursorPageState<K, T>? {
        val result = cache.removeFromCache(self)
        accessOrder.remove(self)
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

    private fun touch(self: K) {
        accessOrder.remove(self)
        accessOrder.add(self)
    }

    private fun performEviction(justAdded: K) {
        while (cache.size > maxSize) {
            val protectedSet: Set<K>? =
                if (protectContextWindow && cache.isStarted) protectedSelves() else null

            val victim = accessOrder.firstOrNull { self ->
                self != justAdded &&
                        cache.getStateOf(self) != null &&
                        (protectedSet == null || self !in protectedSet)
            } ?: break

            val evicted = cache.removeFromCache(victim)
            accessOrder.remove(victim)
            if (evicted != null) {
                cache.logger.debug(LogComponent.CACHE) {
                    "CursorMostRecentPagingCache evict: self=$victim"
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
