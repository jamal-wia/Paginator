package com.jamal_aliev.paginator.cache

import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.extension.withLeaf
import com.jamal_aliev.paginator.logger.LogComponent
import com.jamal_aliev.paginator.logger.debug
import com.jamal_aliev.paginator.page.PageState

/**
 * A [CursorPagingCache] decorator that keeps **only** pages within the current
 * context window (walking `next` from [startContextCursor] to [endContextCursor]).
 *
 * Optionally, a [margin] can be set to retain pages slightly outside the
 * context window on either side.
 *
 * @param margin Number of linked pages to keep beyond each edge of the context
 *   window. Default is `0` (strict window).
 */
class SlidingWindowCursorPagingCache<T>(
    private val cache: CursorPagingCache<T> = DefaultCursorPagingCache(),
    val margin: Int = 0,
    var evictionListener: CacheEvictionListener<T>? = null,
) : CursorPagingCache<T> by cache, CursorWrappablePagingCache<T> {

    override fun replaceLeaf(newLeaf: CursorPagingCache<T>): SlidingWindowCursorPagingCache<T> =
        SlidingWindowCursorPagingCache(
            cache = cache.withLeaf(newLeaf),
            margin = margin,
            evictionListener = evictionListener,
        )

    init {
        require(margin >= 0) { "margin must be >= 0, was $margin" }
    }

    override fun setState(cursor: CursorBookmark, state: PageState<T>, silently: Boolean) {
        cache.setState(cursor, state, silently)
        performEviction(justAdded = cursor.self)
    }

    private fun performEviction(justAdded: Any) {
        if (!cache.isStarted) return

        val keep: Set<Any> = keepSelves()

        val toEvict = cache.cursors.asSequence()
            .map { it.self }
            .filter { self -> self != justAdded && self !in keep }
            .toList()

        for (self in toEvict) {
            val evicted = cache.removeFromCache(self)
            if (evicted != null) {
                cache.logger.debug(LogComponent.CACHE) {
                    "SlidingWindowCursorPagingCache evict: self=$self"
                }
                evictionListener?.onEvicted(evicted)
            }
        }
    }

    private fun keepSelves(): Set<Any> {
        val result = HashSet<Any>()
        val start = cache.startContextCursor ?: return result
        val end = cache.endContextCursor ?: return result
        val visited = HashSet<Any>()

        // Walk inside the window from start → end
        var current: CursorBookmark? = start
        while (current != null && visited.add(current.self)) {
            result.add(current.self)
            if (current.self == end.self) break
            current = cache.walkForward(current)
        }

        // Extend `margin` steps past each edge
        var leftwardMargin = margin
        var leftEdge: CursorBookmark? = cache.walkBackward(start)
        while (leftEdge != null && leftwardMargin > 0) {
            if (!result.add(leftEdge.self)) break
            leftwardMargin--
            leftEdge = cache.walkBackward(leftEdge)
        }

        var rightwardMargin = margin
        var rightEdge: CursorBookmark? = cache.walkForward(end)
        while (rightEdge != null && rightwardMargin > 0) {
            if (!result.add(rightEdge.self)) break
            rightwardMargin--
            rightEdge = cache.walkForward(rightEdge)
        }

        return result
    }
}
