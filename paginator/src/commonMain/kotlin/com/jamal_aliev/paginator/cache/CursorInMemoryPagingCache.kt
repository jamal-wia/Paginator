package com.jamal_aliev.paginator.cache

import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.logger.PaginatorLogger
import com.jamal_aliev.paginator.page.PageState

/**
 * A default [CursorPagingCache] implementation providing key-based storage
 * with no eviction logic. Backed by two `HashMap`s (states and bookmarks).
 *
 * This is the leaf node of any cursor cache strategy chain. Eviction strategies
 * ([CursorQueuedPagingCache], [CursorMostRecentPagingCache], [CursorTimeLimitedPagingCache],
 * [CursorContextWindowPagingCache]) delegate to this class (or to each other,
 * with [CursorInMemoryPagingCache] at the bottom).
 *
 * @param T The type of elements contained in each page.
 */
class CursorInMemoryPagingCache<T> : CursorPagingCache<T> {

    override var logger: PaginatorLogger? = null

    private val states = hashMapOf<Any, PageState<T>>()
    private val bookmarks = hashMapOf<Any, CursorBookmark>()

    override val size: Int get() = states.size

    override val cursors: List<CursorBookmark>
        get() {
            if (bookmarks.isEmpty()) return emptyList()
            val result = ArrayList<CursorBookmark>(bookmarks.size)
            val visited = HashSet<Any>(bookmarks.size)

            // Start from the canonical head (prev == null) to get a deterministic order.
            // Fall back to any entry whose `prev` is missing from the cache (orphan chain head).
            val startFromPrevNull: CursorBookmark? =
                bookmarks.values.firstOrNull { it.prev == null }
            val startFromMissingPrev: CursorBookmark? = bookmarks.values.firstOrNull { b ->
                b.prev != null && !bookmarks.containsKey(b.prev)
            }
            val primaryStart: CursorBookmark? = startFromPrevNull ?: startFromMissingPrev

            if (primaryStart != null) {
                var cursor: CursorBookmark? = primaryStart
                while (cursor != null && visited.add(cursor.self)) {
                    result.add(cursor)
                    val nextSelf = cursor.next ?: break
                    cursor = bookmarks[nextSelf]
                }
            }

            // Append any remaining orphaned entries (self-looped or otherwise unreachable)
            // in insertion-independent order but deterministically limited to unique keys.
            for (entry in bookmarks.values) {
                if (visited.add(entry.self)) result.add(entry)
            }
            return result
        }

    override var startContextCursor: CursorBookmark? = null

    override var endContextCursor: CursorBookmark? = null

    override val isStarted: Boolean
        get() = startContextCursor != null && endContextCursor != null

    override fun setState(cursor: CursorBookmark, state: PageState<T>, silently: Boolean) {
        states[cursor.self] = state
        bookmarks[cursor.self] = cursor
    }

    override fun getStateOf(self: Any): PageState<T>? = states[self]

    override fun getCursorOf(self: Any): CursorBookmark? = bookmarks[self]

    override fun getElement(self: Any, index: Int): T? = states[self]?.data?.get(index)

    override fun removeFromCache(self: Any): PageState<T>? {
        bookmarks.remove(self)
        return states.remove(self)
    }

    override fun clear() {
        states.clear()
        bookmarks.clear()
    }

    override fun release(capacity: Int, silently: Boolean) {
        clear()
        startContextCursor = null
        endContextCursor = null
    }

    override fun head(): CursorBookmark? = cursors.firstOrNull()

    override fun tail(): CursorBookmark? {
        // Walking tail is cheaper than materialising `cursors` when the chain is contiguous:
        // follow `next` from the head until we hit a sentinel or a missing link.
        val headCursor: CursorBookmark = bookmarks.values.firstOrNull { it.prev == null }
            ?: return cursors.lastOrNull()
        var current: CursorBookmark = headCursor
        val visited = HashSet<Any>()
        visited.add(current.self)
        while (true) {
            val nextSelf = current.next ?: return current
            val nextCursor = bookmarks[nextSelf] ?: return current
            if (!visited.add(nextCursor.self)) return current
            current = nextCursor
        }
    }

    override fun walkForward(from: CursorBookmark): CursorBookmark? {
        val nextSelf: Any = from.next ?: return null
        return bookmarks[nextSelf]
    }

    override fun walkBackward(from: CursorBookmark): CursorBookmark? {
        val prevSelf: Any = from.prev ?: return null
        return bookmarks[prevSelf]
    }

    override fun toString(): String = "CursorInMemoryPagingCache(size=$size)"

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = super.hashCode()
}
