package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.exception.EndOfCursorFeedException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Cursor analogs of the offset GoPreviousPagePartialAnchorTest. Covers the non-filled-boundary
 * navigation class ported from the offset fixes:
 *  - goPreviousPage must step to `prev` instead of reloading a non-filled start anchor (C1);
 *  - jump onto a non-filled page must absorb an adjacent cached filled neighbor, boundary-preserving (C2).
 */
class CursorPartialAnchorTest {

    /** p0,p1,p2 full (capacity 3); p3 is a partial tail (1 of 3). */
    private fun partialTailBackend() = FakeCursorBackend(
        pages = listOf(
            FakeCursorBackend.Page(self = "p0", prev = null, next = "p1", items = List(3) { "p0_$it" }),
            FakeCursorBackend.Page(self = "p1", prev = "p0", next = "p2", items = List(3) { "p1_$it" }),
            FakeCursorBackend.Page(self = "p2", prev = "p1", next = "p3", items = List(3) { "p2_$it" }),
            FakeCursorBackend.Page(self = "p3", prev = "p2", next = null, items = List(1) { "p3_$it" }),
        ),
    )

    private fun bookmark(self: String) = CursorBookmark<String>(prev = null, self = self, next = null)

    @Test
    fun goPreviousPage_from_partial_tail_anchor_advances_instead_of_reloading() = runTest {
        val p = cursorPaginatorOf(partialTailBackend(), capacity = 3)
        p.jump(bookmark("p3"), silentlyLoading = true, silentlyResult = true)
        assertEquals("p3", p.core.startContextCursor?.self)

        p.goPreviousPage(silentlyLoading = true, silentlyResult = true)

        // Reached p2 (the prev) — not stuck reloading the partial tail p3.
        assertEquals("p2", p.core.startContextCursor?.self)
        // The partial tail p3 stays as the end boundary.
        assertEquals("p3", p.core.endContextCursor?.self)
    }

    @Test
    fun repeated_goPreviousPage_from_partial_tail_walks_to_head_then_throws() = runTest {
        val p = cursorPaginatorOf(partialTailBackend(), capacity = 3)
        p.jump(bookmark("p3"), silentlyLoading = true, silentlyResult = true)

        var steps = 0
        while (p.core.startContextCursor?.self != "p0" && steps++ < 20) {
            p.goPreviousPage(silentlyLoading = true, silentlyResult = true)
        }
        assertEquals("p0", p.core.startContextCursor?.self)

        // At the head (prev == null) backward navigation hits the terminus.
        assertFailsWith<EndOfCursorFeedException> {
            p.goPreviousPage(silentlyLoading = true, silentlyResult = true)
        }
    }

    @Test
    fun jump_onto_partial_page_absorbs_adjacent_cached_filled_neighbor() = runTest {
        val p = cursorPaginatorOf(partialTailBackend(), capacity = 3)
        // Cache the filled page p2 (the prev neighbor of the partial tail).
        p.jump(bookmark("p2"), silentlyLoading = true, silentlyResult = true)
        // Jump onto the partial tail p3 — its cached prev p2 must be absorbed into the window.
        p.jump(bookmark("p3"), silentlyLoading = true, silentlyResult = true)

        assertEquals("p2", p.core.startContextCursor?.self)
        assertEquals("p3", p.core.endContextCursor?.self)
    }
}
