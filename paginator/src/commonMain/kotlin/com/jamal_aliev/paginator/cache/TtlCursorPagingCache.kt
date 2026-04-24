package com.jamal_aliev.paginator.cache

import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.extension.withLeaf
import com.jamal_aliev.paginator.logger.LogComponent
import com.jamal_aliev.paginator.logger.debug
import com.jamal_aliev.paginator.page.PageState
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * A [CursorPagingCache] decorator that enforces a **TTL (Time To Live)** eviction policy.
 *
 * Each page records a timestamp when added to the cache. When a new page is added
 * via [setState], any pages older than [ttl] are evicted.
 */
class TtlCursorPagingCache<T>(
    private val cache: CursorPagingCache<T> = DefaultCursorPagingCache(),
    val ttl: Duration,
    val refreshOnAccess: Boolean = false,
    val protectContextWindow: Boolean = true,
    val timeSource: TimeSource = TimeSource.Monotonic,
    var evictionListener: CacheEvictionListener<T>? = null,
) : CursorPagingCache<T> by cache, CursorWrappablePagingCache<T> {

    override fun replaceLeaf(newLeaf: CursorPagingCache<T>): TtlCursorPagingCache<T> =
        TtlCursorPagingCache(
            cache = cache.withLeaf(newLeaf),
            ttl = ttl,
            refreshOnAccess = refreshOnAccess,
            protectContextWindow = protectContextWindow,
            timeSource = timeSource,
            evictionListener = evictionListener,
        )

    init {
        require(ttl.isPositive()) { "ttl must be positive, was $ttl" }
    }

    private val timestamps = hashMapOf<Any, TimeMark>()

    override fun setState(cursor: CursorBookmark, state: PageState<T>, silently: Boolean) {
        cache.setState(cursor, state, silently)
        timestamps[cursor.self] = timeSource.markNow()
        performEviction()
    }

    override fun getStateOf(self: Any): PageState<T>? {
        val result = cache.getStateOf(self)
        if (result != null && refreshOnAccess) timestamps[self] = timeSource.markNow()
        return result
    }

    override fun getElement(self: Any, index: Int): T? {
        val result = cache.getElement(self, index)
        if (result != null && refreshOnAccess) timestamps[self] = timeSource.markNow()
        return result
    }

    override fun removeFromCache(self: Any): PageState<T>? {
        val result = cache.removeFromCache(self)
        if (result != null) timestamps.remove(self)
        return result
    }

    override fun clear() {
        cache.clear()
        timestamps.clear()
    }

    override fun release(capacity: Int, silently: Boolean) {
        cache.release(capacity, silently)
        timestamps.clear()
    }

    private fun performEviction() {
        val protectedSet: Set<Any>? =
            if (protectContextWindow && cache.isStarted) protectedSelves() else null

        val expired = timestamps.entries
            .filter { (_, mark) -> mark.elapsedNow() > ttl }
            .map { (self, _) -> self }
            .filter { self ->
                cache.getStateOf(self) != null &&
                        (protectedSet == null || self !in protectedSet)
            }

        for (self in expired) {
            val evicted = cache.removeFromCache(self)
            timestamps.remove(self)
            if (evicted != null) {
                cache.logger.debug(LogComponent.CACHE) {
                    "TtlCursorPagingCache evict: self=$self (ttl expired)"
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
