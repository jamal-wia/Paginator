package com.jamal_aliev.paginator.cache

import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.extension.withLeaf
import com.jamal_aliev.paginator.logger.LogComponent
import com.jamal_aliev.paginator.logger.debug
import com.jamal_aliev.paginator.page.PageState

/**
 * A [CursorPagingCache] decorator that enforces an **LRU (Least Recently Used)**
 * eviction policy, keyed by [CursorBookmark.self].
 *
 * When the number of cached pages exceeds [maxSize], the page that has been
 * accessed least recently (either written via [setState] or read via [getStateOf] /
 * [getElement]) is evicted.
 *
 * Pages whose bookmarks sit on the chain from [startContextCursor] to
 * [endContextCursor] (walking `next`) are protected from eviction when
 * [protectContextWindow] is `true`.
 */
class LruCursorPagingCache<T>(
    private val cache: CursorPagingCache<T> = DefaultCursorPagingCache(),
    val maxSize: Int,
    val protectContextWindow: Boolean = true,
    var evictionListener: CacheEvictionListener<T>? = null,
) : CursorPagingCache<T> by cache, CursorWrappablePagingCache<T> {

    override fun replaceLeaf(newLeaf: CursorPagingCache<T>): LruCursorPagingCache<T> =
        LruCursorPagingCache(
            cache = cache.withLeaf(newLeaf),
            maxSize = maxSize,
            protectContextWindow = protectContextWindow,
            evictionListener = evictionListener,
        )

    init {
        require(maxSize > 0) { "maxSize must be greater than 0, was $maxSize" }
    }

    /** Access order: head = least recent, tail = most recent. Stores `self` keys. */
    private val accessOrder = mutableListOf<Any>()

    override fun setState(cursor: CursorBookmark, state: PageState<T>, silently: Boolean) {
        cache.setState(cursor, state, silently)
        touch(cursor.self)
        performEviction(justAdded = cursor.self)
    }

    override fun getStateOf(self: Any): PageState<T>? {
        val result = cache.getStateOf(self)
        if (result != null) touch(self)
        return result
    }

    override fun getElement(self: Any, index: Int): T? {
        val result = cache.getElement(self, index)
        if (result != null) touch(self)
        return result
    }

    override fun removeFromCache(self: Any): PageState<T>? {
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

    private fun touch(self: Any) {
        accessOrder.remove(self)
        accessOrder.add(self)
    }

    private fun performEviction(justAdded: Any) {
        while (cache.size > maxSize) {
            val protectedSet: Set<Any>? =
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
                    "LruCursorPagingCache evict: self=$victim"
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
