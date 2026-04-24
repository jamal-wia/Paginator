package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cache.DefaultCursorPagingCache
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultCursorPagingCacheTest {

    private fun successPage(id: Int, vararg items: String): SuccessPage<String> =
        SuccessPage(page = id, data = items.toMutableList())

    @Test
    fun empty_cache_basics() {
        val c = DefaultCursorPagingCache<String>()
        assertEquals(0, c.size)
        assertNull(c.head())
        assertNull(c.tail())
        assertNull(c.startContextCursor)
        assertNull(c.endContextCursor)
        assertTrue(c.cursors.isEmpty())
    }

    @Test
    fun set_get_remove_round_trip() {
        val c = DefaultCursorPagingCache<String>()
        val bookmark = CursorBookmark(prev = null, self = "A", next = "B")
        val state = successPage(1, "a1", "a2")
        c.setState(bookmark, state)

        assertEquals(1, c.size)
        assertEquals(state, c.getStateOf("A"))
        assertEquals(bookmark, c.getCursorOf("A"))
        assertEquals("a2", c.getElement("A", 1))

        val removed = c.removeFromCache("A")
        assertEquals(state, removed)
        assertNull(c.getStateOf("A"))
        assertNull(c.getCursorOf("A"))
        assertEquals(0, c.size)
    }

    @Test
    fun cursors_follows_prev_null_to_next_null_order() {
        val c = DefaultCursorPagingCache<String>()
        // Insert out of order to exercise the ordering logic.
        c.setState(CursorBookmark(prev = "B", self = "C", next = null), successPage(3, "c"))
        c.setState(CursorBookmark(prev = null, self = "A", next = "B"), successPage(1, "a"))
        c.setState(CursorBookmark(prev = "A", self = "B", next = "C"), successPage(2, "b"))

        val order = c.cursors.map { it.self }
        assertEquals(listOf("A", "B", "C"), order)
        assertEquals("A", c.head()?.self)
        assertEquals("C", c.tail()?.self)
    }

    @Test
    fun walkForward_walkBackward_follow_links() {
        val c = DefaultCursorPagingCache<String>()
        val a = CursorBookmark(prev = null, self = "A", next = "B")
        val b = CursorBookmark(prev = "A", self = "B", next = "C")
        val cc = CursorBookmark(prev = "B", self = "C", next = null)
        c.setState(a, successPage(1, "a"))
        c.setState(b, successPage(2, "b"))
        c.setState(cc, successPage(3, "c"))

        assertEquals("B", c.walkForward(a)?.self)
        assertEquals("C", c.walkForward(b)?.self)
        assertNull(c.walkForward(cc))
        assertEquals("B", c.walkBackward(cc)?.self)
        assertEquals("A", c.walkBackward(b)?.self)
        assertNull(c.walkBackward(a))
    }

    @Test
    fun walkForward_null_when_link_target_missing_from_cache() {
        val c = DefaultCursorPagingCache<String>()
        val orphan = CursorBookmark(prev = "missingPrev", self = "Orphan", next = "missingNext")
        c.setState(orphan, successPage(1, "o"))

        // The links don't resolve to anything in the cache, so the walk returns null
        // (even though `next` is non-null on the bookmark itself).
        assertNull(c.walkForward(orphan))
        assertNull(c.walkBackward(orphan))
    }

    @Test
    fun setState_with_same_self_replaces_bookmark_links() {
        val c = DefaultCursorPagingCache<String>()
        val original = CursorBookmark(prev = null, self = "A", next = null)
        c.setState(original, successPage(1, "a"))

        val updated = CursorBookmark(prev = "X", self = "A", next = "Y")
        c.setState(updated, successPage(1, "a"))

        val cursor = c.getCursorOf("A")
        assertNotNull(cursor)
        assertEquals("X", cursor.prev)
        assertEquals("Y", cursor.next)
        assertEquals(1, c.size, "replacing must not grow the cache")
    }

    @Test
    fun clear_wipes_everything_but_keeps_context_cursors() {
        // `clear` is documented as "Removes every page from the cache". The context
        // cursors are a separate concern — they are reset by `release`, not `clear`.
        val c = DefaultCursorPagingCache<String>()
        c.setState(CursorBookmark(prev = null, self = "A", next = null), successPage(1, "a"))
        c.startContextCursor = c.getCursorOf("A")
        c.endContextCursor = c.getCursorOf("A")
        c.clear()
        assertEquals(0, c.size)
        assertNotNull(c.startContextCursor)
    }

    @Test
    fun release_clears_cache_and_context_cursors() {
        val c = DefaultCursorPagingCache<String>()
        c.setState(CursorBookmark(prev = null, self = "A", next = null), successPage(1, "a"))
        c.startContextCursor = c.getCursorOf("A")
        c.endContextCursor = c.getCursorOf("A")
        c.release()
        assertEquals(0, c.size)
        assertNull(c.startContextCursor)
        assertNull(c.endContextCursor)
    }

    @Test
    fun cursors_handles_cycle_without_infinite_loop() {
        val c = DefaultCursorPagingCache<String>()
        // A -> B -> A  (broken cycle)
        c.setState(CursorBookmark(prev = null, self = "A", next = "B"), successPage(1, "a"))
        c.setState(CursorBookmark(prev = "A", self = "B", next = "A"), successPage(2, "b"))

        val order = c.cursors.map { it.self }
        // Must visit each self at most once.
        assertEquals(order.toSet(), order.toSet())
        assertEquals(2, order.size)
        assertTrue(order.contains("A"))
        assertTrue(order.contains("B"))
    }

    @Test
    fun cursors_mixed_key_types() {
        val c = DefaultCursorPagingCache<String>()
        // Use a mix of Long ids and String ids.
        c.setState(CursorBookmark(prev = null, self = 1L, next = "mid"), successPage(1, "a"))
        c.setState(CursorBookmark(prev = 1L, self = "mid", next = 3L), successPage(2, "b"))
        c.setState(CursorBookmark(prev = "mid", self = 3L, next = null), successPage(3, "c"))

        assertEquals(listOf<Any>(1L, "mid", 3L), c.cursors.map { it.self })
    }

    @Test
    fun head_and_tail_fall_back_when_no_prev_null_entry_exists() {
        val c = DefaultCursorPagingCache<String>()
        // No entry has prev == null; both entries reference a missing predecessor.
        c.setState(CursorBookmark(prev = "ghost", self = "A", next = "B"), successPage(1, "a"))
        c.setState(CursorBookmark(prev = "A", self = "B", next = null), successPage(2, "b"))

        val head = c.head()
        val tail = c.tail()
        assertNotNull(head)
        assertNotNull(tail)
        // Head should be the one whose `prev` is not in the cache (orphan head).
        assertEquals("A", head.self)
        assertEquals("B", tail.self)
    }
}
