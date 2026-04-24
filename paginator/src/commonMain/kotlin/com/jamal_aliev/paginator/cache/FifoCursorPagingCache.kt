package com.jamal_aliev.paginator.cache

import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.extension.withLeaf
import com.jamal_aliev.paginator.logger.LogComponent
import com.jamal_aliev.paginator.logger.debug
import com.jamal_aliev.paginator.page.PageState

/**
 * A [CursorPagingCache] decorator enforcing **FIFO (First In, First Out)** eviction.
 *
 * When the number of cached pages exceeds [maxSize], the page that was added
 * first is evicted. Unlike [LruCursorPagingCache], reads do not affect the
 * eviction priority.
 */
class FifoCursorPagingCache<T>(
    private val cache: CursorPagingCache<T> = DefaultCursorPagingCache(),
    val maxSize: Int,
    val protectContextWindow: Boolean = true,
    var evictionListener: CacheEvictionListener<T>? = null,
) : CursorPagingCache<T> by cache, CursorWrappablePagingCache<T> {

    override fun replaceLeaf(newLeaf: CursorPagingCache<T>): FifoCursorPagingCache<T> =
        FifoCursorPagingCache(
            cache = cache.withLeaf(newLeaf),
            maxSize = maxSize,
            protectContextWindow = protectContextWindow,
            evictionListener = evictionListener,
        )

    init {
        require(maxSize > 0) { "maxSize must be greater than 0, was $maxSize" }
    }

    private val insertionOrder = mutableListOf<Any>()

    override fun setState(cursor: CursorBookmark, state: PageState<T>, silently: Boolean) {
        val isNew = cache.getStateOf(cursor.self) == null
        cache.setState(cursor, state, silently)
        if (isNew) insertionOrder.add(cursor.self)
        performEviction(justAdded = cursor.self)
    }

    override fun removeFromCache(self: Any): PageState<T>? {
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

    private fun performEviction(justAdded: Any) {
        while (cache.size > maxSize) {
            val protectedSet: Set<Any>? =
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
                    "FifoCursorPagingCache evict: self=$victim"
                }
                evictionListener?.onEvicted(evicted)
            }
        }
    }

    private fun protectedSelves(): Set<Any> {
        val result = HashSet<Any>()
        var current = cache.startContextCursor
        val end = cache.endContextCursor
        val visited = HashSet<Any>()
        while (current != null && visited.add(current.self)) {
            result.add(current.self)
            if (end != null && current.self == end.self) break
            current = cache.walkForward(current)
        }
        return result
    }
}
