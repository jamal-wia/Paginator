package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.page.CursorPageState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Mirrors `paginator-offset`'s `CoerceToCapacityTest` for the cursor strategy — same trimming
 * and mutability guarantees are expected from both [CursorPagingCore.coerceToCapacity] overloads.
 */
class CoerceToCapacityTest {

    private fun core(capacity: Int): CursorPagingCore<String, String> =
        CursorPagingCore(initialCapacity = capacity)

    private fun unlimitedCore(): CursorPagingCore<String, String> =
        core(CursorPagingCore.UNLIMITED_CAPACITY)

    private fun bookmark(self: String = "s") = CursorBookmark<String>(prev = null, self = self, next = null)

    // ══════════════════════════════════════════════════════════════════════
    //  coerceToCapacity(data: List<T>)
    // ══════════════════════════════════════════════════════════════════════

    // ── Basic trimming ───────────────────────────────────────────────────

    @Test
    fun `list - trims data exceeding capacity`() {
        val c = core(3)
        val result = c.coerceToCapacity(listOf("a", "b", "c", "d", "e"))
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `list - keeps first N elements when trimming`() {
        val c = core(2)
        val result = c.coerceToCapacity(listOf("first", "second", "third"))
        assertEquals(listOf("first", "second"), result)
    }

    // ── No trimming needed ───────────────────────────────────────────────

    @Test
    fun `list - returns same data when size equals capacity`() {
        val c = core(3)
        val result = c.coerceToCapacity(listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `list - returns same data when size is less than capacity`() {
        val c = core(5)
        val result = c.coerceToCapacity(listOf("a", "b"))
        assertEquals(listOf("a", "b"), result)
    }

    // ── Empty list ───────────────────────────────────────────────────────

    @Test
    fun `list - empty list stays empty`() {
        val c = core(3)
        val result = c.coerceToCapacity(emptyList())
        assertTrue(result.isEmpty())
    }

    // ── Unlimited capacity ───────────────────────────────────────────────

    @Test
    fun `list - unlimited capacity never trims`() {
        val c = unlimitedCore()
        val big = List(1000) { "item_$it" }
        val result = c.coerceToCapacity(big)
        assertEquals(1000, result.size)
    }

    @Test
    fun `list - unlimited capacity with empty list`() {
        val c = unlimitedCore()
        val result = c.coerceToCapacity(emptyList())
        assertTrue(result.isEmpty())
    }

    // ── MutableList guarantee ────────────────────────────────────────────
    //
    // Regression coverage: a restart() of the very first (unanchored) page used to seed its
    // transient Progress/Error state with a literal emptyList(), which does not implement
    // MutableList. Any later CRUD mutation on that page (setElement/addAllElements/...) casts
    // `data as MutableList`, so an immutable list crashed with an IllegalArgumentException /
    // ClassCastException the moment a caller tried to insert into a page still loading.

    @Test
    fun `list - result is always MutableList when trimmed`() {
        val c = core(2)
        val result = c.coerceToCapacity(listOf("a", "b", "c"))
        assertIs<MutableList<String>>(result)
    }

    @Test
    fun `list - result is MutableList when not trimmed and input is immutable`() {
        val c = core(5)
        val immutable = listOf("a", "b")
        val result = c.coerceToCapacity(immutable)
        assertIs<MutableList<String>>(result)
    }

    @Test
    fun `list - result is MutableList for an immutable empty list`() {
        val c = core(5)
        val result = c.coerceToCapacity(emptyList())
        assertIs<MutableList<String>>(result)
    }

    @Test
    fun `list - MutableList input is preserved as-is when not trimmed`() {
        val c = core(5)
        val mutable = mutableListOf("a", "b")
        val result = c.coerceToCapacity(mutable)
        assertSame(result, mutable)
    }

    // ── Capacity = 1 ─────────────────────────────────────────────────────

    @Test
    fun `list - capacity 1 trims to single element`() {
        val c = core(1)
        val result = c.coerceToCapacity(listOf("a", "b", "c"))
        assertEquals(listOf("a"), result)
    }

    @Test
    fun `list - capacity 1 keeps single element`() {
        val c = core(1)
        val result = c.coerceToCapacity(listOf("only"))
        assertEquals(listOf("only"), result)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  coerceToCapacity(state: CursorPageState<K, T>)
    // ══════════════════════════════════════════════════════════════════════

    // ── CursorPageState.Success ──────────────────────────────────────────

    @Test
    fun `state - Success trimmed when data exceeds capacity`() {
        val c = core(2)
        val state = CursorPageState.Success(bookmark = bookmark(), data = listOf("a", "b", "c", "d"))
        val result = c.coerceToCapacity(state)
        assertEquals(listOf("a", "b"), result.data)
        assertEquals("s", result.bookmark.self)
    }

    @Test
    fun `state - Success returned as-is when within capacity`() {
        val c = core(5)
        val state = CursorPageState.Success(bookmark = bookmark(), data = listOf("a", "b"))
        val result = c.coerceToCapacity(state)
        assertSame(result, state)
    }

    @Test
    fun `state - Success returned as-is when size equals capacity`() {
        val c = core(3)
        val state = CursorPageState.Success(bookmark = bookmark(), data = listOf("a", "b", "c"))
        val result = c.coerceToCapacity(state)
        assertSame(result, state)
    }

    // ── empty CursorPageState.Success ────────────────────────────────────

    @Test
    fun `state - empty Success returned as-is`() {
        val c = core(3)
        val state = CursorPageState.Success<String, String>(bookmark = bookmark(), data = emptyList())
        val result = c.coerceToCapacity(state)
        assertSame(result, state)
    }

    // ── CursorPageState.Progress ─────────────────────────────────────────

    @Test
    fun `state - Progress trimmed when data exceeds capacity`() {
        val c = core(2)
        val state = CursorPageState.Progress(bookmark = bookmark(), data = listOf("a", "b", "c"))
        val result = c.coerceToCapacity(state)
        assertEquals(listOf("a", "b"), result.data)
    }

    @Test
    fun `state - Progress returned as-is when within capacity`() {
        val c = core(5)
        val state = CursorPageState.Progress(bookmark = bookmark(), data = listOf("a"))
        val result = c.coerceToCapacity(state)
        assertSame(result, state)
    }

    @Test
    fun `state - Progress with empty data returned as-is`() {
        val c = core(3)
        val state = CursorPageState.Progress<String, String>(bookmark = bookmark(), data = emptyList())
        val result = c.coerceToCapacity(state)
        assertSame(result, state)
    }

    // ── CursorPageState.Error ────────────────────────────────────────────

    @Test
    fun `state - Error trimmed when data exceeds capacity`() {
        val c = core(2)
        val ex = RuntimeException("fail")
        val state = CursorPageState.Error(exception = ex, bookmark = bookmark(), data = listOf("a", "b", "c"))
        val result = c.coerceToCapacity(state)
        assertEquals(listOf("a", "b"), result.data)
    }

    @Test
    fun `state - Error returned as-is when within capacity`() {
        val c = core(5)
        val ex = RuntimeException("fail")
        val state = CursorPageState.Error(exception = ex, bookmark = bookmark(), data = listOf("a"))
        val result = c.coerceToCapacity(state)
        assertSame(result, state)
    }

    @Test
    fun `state - Error with empty data returned as-is`() {
        val c = core(3)
        val ex = RuntimeException("fail")
        val state = CursorPageState.Error<String, String>(exception = ex, bookmark = bookmark(), data = emptyList())
        val result = c.coerceToCapacity(state)
        assertSame(result, state)
    }

    // ── Unlimited capacity (state) ───────────────────────────────────────

    @Test
    fun `state - unlimited capacity never trims Success`() {
        val c = unlimitedCore()
        val state = CursorPageState.Success(bookmark = bookmark(), data = List(500) { "item_$it" })
        val result = c.coerceToCapacity(state)
        assertSame(result, state)
        assertEquals(500, result.data.size)
    }

    @Test
    fun `state - unlimited capacity never trims Progress`() {
        val c = unlimitedCore()
        val state = CursorPageState.Progress(bookmark = bookmark(), data = List(500) { "item_$it" })
        val result = c.coerceToCapacity(state)
        assertSame(result, state)
    }

    @Test
    fun `state - unlimited capacity never trims Error`() {
        val c = unlimitedCore()
        val ex = RuntimeException("fail")
        val state = CursorPageState.Error(exception = ex, bookmark = bookmark(), data = List(500) { "item_$it" })
        val result = c.coerceToCapacity(state)
        assertSame(result, state)
    }

    // ── Bookmark preservation ────────────────────────────────────────────

    @Test
    fun `state - bookmark is preserved after trimming`() {
        val c = core(1)
        val state = CursorPageState.Success(bookmark = bookmark("p42"), data = listOf("a", "b", "c"))
        val result = c.coerceToCapacity(state)
        assertEquals("p42", result.bookmark.self)
    }

    // ── Type preservation ────────────────────────────────────────────────

    @Test
    fun `state - Progress type preserved after trimming`() {
        val c = core(1)
        val state = CursorPageState.Progress(bookmark = bookmark(), data = listOf("a", "b"))
        val result = c.coerceToCapacity(state)
        assertIs<CursorPageState.Progress<String, String>>(result)
    }

    @Test
    fun `state - Error type preserved after trimming`() {
        val c = core(1)
        val ex = RuntimeException("fail")
        val state = CursorPageState.Error(exception = ex, bookmark = bookmark(), data = listOf("a", "b"))
        val result = c.coerceToCapacity(state)
        assertIs<CursorPageState.Error<String, String>>(result)
    }

    @Test
    fun `state - data exactly one over capacity is trimmed`() {
        val c = core(3)
        val state = CursorPageState.Success(bookmark = bookmark(), data = listOf("a", "b", "c", "d"))
        val result = c.coerceToCapacity(state)
        assertEquals(3, result.data.size)
    }
}
