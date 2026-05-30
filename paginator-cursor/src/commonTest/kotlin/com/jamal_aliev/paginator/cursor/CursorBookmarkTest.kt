package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.cursor.page.CursorPageState

import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CursorBookmarkTest {

    @Test
    fun equals_uses_only_self() {
        val a = CursorBookmark<String>(prev = "x", self = "A", next = "y")
        val b = CursorBookmark<String>(prev = null, self = "A", next = null)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun unequal_when_self_differs_even_if_links_match() {
        val a = CursorBookmark<String>(prev = "p", self = "A", next = "n")
        val b = CursorBookmark<String>(prev = "p", self = "B", next = "n")
        assertNotEquals(a, b)
    }

    @Test
    fun reflexive_equality() {
        val a = CursorBookmark<Long>(prev = 1L, self = 2L, next = 3L)
        assertTrue(a == a)
    }

    @Test
    fun different_key_types_compare_only_on_self_equality() {
        // Int vs Long with the same numeric value — Int(1) != Long(1L) in Kotlin.
        val a = CursorBookmark<Int>(prev = null, self = 1, next = null)
        val b = CursorBookmark<Long>(prev = null, self = 1L, next = null)
        assertNotEquals<CursorBookmark<*>>(a, b)
    }

    @Test
    fun null_links_do_not_affect_identity_swap() {
        // The data class copy() only replaces named fields; equality goes by `self`.
        val original = CursorBookmark<String>(prev = null, self = "A", next = "B")
        val updated = original.copy(prev = "zero", next = null)
        assertEquals(original, updated)
        assertEquals("zero", updated.prev)
        assertEquals(null, updated.next)
    }

    @Test
    fun hashCode_matches_self_hashCode() {
        val a = CursorBookmark<String>(prev = "x", self = "A", next = "y")
        assertEquals("A".hashCode(), a.hashCode())
    }
}
