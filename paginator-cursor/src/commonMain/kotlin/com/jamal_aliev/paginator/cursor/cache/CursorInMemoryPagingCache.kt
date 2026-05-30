package com.jamal_aliev.paginator.cursor.cache

import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.core.logger.PaginatorLogger
import com.jamal_aliev.paginator.cursor.page.CursorPageState

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
class CursorInMemoryPagingCache<K : Any, T> : CursorPagingCache<K, T> {

    override var logger: PaginatorLogger? = null

    private val states = hashMapOf<K, CursorPageState<K, T>>()
    private val bookmarks = hashMapOf<K, CursorBookmark<K>>()

    override val size: Int get() = states.size

    override val cursors: List<CursorBookmark<K>>
        get() {
            if (bookmarks.isEmpty()) return emptyList()
            val result = ArrayList<CursorBookmark<K>>(bookmarks.size)
            val visited = HashSet<K>(bookmarks.size)

            // Start from the canonical head (prev == null) to get a deterministic order.
            // Fall back to any entry whose `prev` is missing from the cache (orphan chain head).
            val startFromPrevNull: CursorBookmark<K>? =
                bookmarks.values.firstOrNull { it.prev == null }
            val startFromMissingPrev: CursorBookmark<K>? = bookmarks.values.firstOrNull { b ->
                b.prev != null && !bookmarks.containsKey(b.prev)
            }
            val primaryStart: CursorBookmark<K>? = startFromPrevNull ?: startFromMissingPrev

            if (primaryStart != null) {
                var cursor: CursorBookmark<K>? = primaryStart
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

    override var startContextCursor: CursorBookmark<K>? = null

    override var endContextCursor: CursorBookmark<K>? = null

    override val isStarted: Boolean
        get() = startContextCursor != null && endContextCursor != null

    override fun setState(
        cursor: CursorBookmark<K>,
        state: CursorPageState<K, T>,
        silently: Boolean
    ) {
        states[cursor.self] = state
        bookmarks[cursor.self] = cursor
    }

    override fun getStateOf(self: K): CursorPageState<K, T>? = states[self]

    override fun getCursorOf(self: K): CursorBookmark<K>? = bookmarks[self]

    override fun getElement(self: K, index: Int): T? = states[self]?.data?.get(index)

    override fun removeFromCache(self: K): CursorPageState<K, T>? {
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

    override fun head(): CursorBookmark<K>? = cursors.firstOrNull()

    override fun tail(): CursorBookmark<K>? {
        // Walking tail is cheaper than materialising `cursors` when the chain is contiguous:
        // follow `next` from the head until we hit a sentinel or a missing link.
        val headCursor: CursorBookmark<K> = bookmarks.values.firstOrNull { it.prev == null }
            ?: return cursors.lastOrNull()
        var current: CursorBookmark<K> = headCursor
        val visited = HashSet<K>()
        visited.add(current.self)
        while (true) {
            val nextSelf = current.next ?: return current
            val nextCursor = bookmarks[nextSelf] ?: return current
            if (!visited.add(nextCursor.self)) return current
            current = nextCursor
        }
    }

    override fun walkForward(from: CursorBookmark<K>): CursorBookmark<K>? {
        val nextSelf: K = from.next ?: return null
        return bookmarks[nextSelf]
    }

    override fun walkBackward(from: CursorBookmark<K>): CursorBookmark<K>? {
        val prevSelf: K = from.prev ?: return null
        return bookmarks[prevSelf]
    }

    override fun toString(): String = "CursorInMemoryPagingCache(size=$size)"

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = super.hashCode()
}
