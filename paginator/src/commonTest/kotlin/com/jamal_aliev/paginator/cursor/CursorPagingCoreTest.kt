package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.CursorPagingCore
import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.page.PageState.EmptyPage
import com.jamal_aliev.paginator.page.PageState.ErrorPage
import com.jamal_aliev.paginator.page.PageState.ProgressPage
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CursorPagingCoreTest {

    private fun bookmarkAt(i: Int, total: Int = 5): CursorBookmark =
        CursorBookmark(
            prev = if (i == 0) null else "p${i - 1}",
            self = "p$i",
            next = if (i == total - 1) null else "p${i + 1}",
        )

    private fun full(id: Int, capacity: Int = 3): SuccessPage<String> =
        SuccessPage(page = id, data = MutableList(capacity) { "v$id-$it" })

    private fun populate(core: CursorPagingCore<String>, total: Int = 5) {
        repeat(total) {
            core.cache.setState(bookmarkAt(it, total), full(it + 1, core.capacity), silently = true)
        }
    }

    @Test
    fun expandEndContextCursor_walks_to_last_filled_success_page() {
        val core = CursorPagingCore<String>(initialCapacity = 3)
        populate(core)

        val first = core.cache.getCursorOf("p0")!!
        val firstState = core.cache.getStateOf("p0")!!
        core.startContextCursor = first
        core.endContextCursor = first

        core.expandEndContextCursor(firstState, first)
        assertEquals("p4", core.endContextCursor?.self)
    }

    @Test
    fun expandEndContextCursor_stops_at_gap() {
        val core = CursorPagingCore<String>(initialCapacity = 3)
        populate(core)
        // Drop p2 from the cache — creates a hole. walkForward on p1 will return p2's
        // bookmark from cache but cache.getStateOf("p2") returns null → walk stops.
        core.cache.removeFromCache("p2")

        val p0 = core.cache.getCursorOf("p0")!!
        core.startContextCursor = p0
        core.endContextCursor = p0
        core.expandEndContextCursor(core.cache.getStateOf("p0"), p0)
        // Should stop at p1 — p2 is missing so the chain breaks there.
        assertEquals("p1", core.endContextCursor?.self)
    }

    @Test
    fun expandEndContextCursor_stops_at_non_filled_success() {
        val core = CursorPagingCore<String>(initialCapacity = 3)
        // p0 filled, p1 is a ProgressPage (non-success boundary), p2 filled.
        val p0 = bookmarkAt(0, 3)
        val p1 = bookmarkAt(1, 3)
        val p2 = bookmarkAt(2, 3)
        core.cache.setState(p0, full(1, 3), silently = true)
        core.cache.setState(
            p1,
            ProgressPage(page = 2, data = mutableListOf()),
            silently = true,
        )
        core.cache.setState(p2, full(3, 3), silently = true)

        core.startContextCursor = p0
        core.endContextCursor = p0
        core.expandEndContextCursor(core.cache.getStateOf("p0"), p0)
        // p1 is not a filled success — walk must stop at p0.
        assertEquals("p0", core.endContextCursor?.self)
    }

    @Test
    fun expandEndContextCursor_noops_on_null_pivot() {
        val core = CursorPagingCore<String>(initialCapacity = 3)
        populate(core)
        core.expandEndContextCursor(null, null)
        assertNull(core.endContextCursor)
    }

    @Test
    fun snapshot_emits_chain_of_filled_successes_reachable_from_context() = runTest {
        // snapshot() auto-expands through adjacent filled-success pages, mirroring
        // offset PagingCore. When every cached page is a filled success, the entire
        // linked chain is visible.
        val core = CursorPagingCore<String>(initialCapacity = 3)
        populate(core)
        core.startContextCursor = core.cache.getCursorOf("p1")
        core.endContextCursor = core.cache.getCursorOf("p3")
        core.snapshot()

        val first = core.snapshot.first()
        assertEquals(listOf("p0", "p1", "p2", "p3", "p4"), core.snapshotSelves())
        assertEquals(5, first.size)
        assertTrue(first.all { it is SuccessPage })
    }

    @Test
    fun snapshot_extends_by_one_boundary_page_on_each_side() = runTest {
        // p0 = EmptyPage boundary, p1..p3 filled, p4 = ProgressPage boundary.
        val core = CursorPagingCore<String>(initialCapacity = 3)
        val p0 = bookmarkAt(0, 5)
        val p4 = bookmarkAt(4, 5)
        core.cache.setState(
            p0,
            com.jamal_aliev.paginator.page.PageState.EmptyPage<String>(1, emptyList()),
            silently = true,
        )
        (1..3).forEach {
            core.cache.setState(bookmarkAt(it, 5), full(it + 1, 3), silently = true)
        }
        core.cache.setState(
            p4,
            com.jamal_aliev.paginator.page.PageState.ProgressPage<String>(5, mutableListOf()),
            silently = true,
        )
        core.startContextCursor = core.cache.getCursorOf("p1")
        core.endContextCursor = core.cache.getCursorOf("p3")
        core.snapshot()

        val range = core.snapshotCursorRange()
        assertNotNull(range)
        // Range includes one boundary page on each side: p0 (Empty) and p4 (Progress).
        assertEquals("p0", range.first.self)
        assertEquals("p4", range.second.self)
    }

    @Test
    fun snapshot_emits_empty_when_range_not_started() = runTest {
        val core = CursorPagingCore<String>(initialCapacity = 3)
        // No context cursors set → snapshot() is a no-op.
        core.snapshot()
        val range = core.snapshotCursorRange()
        assertNull(range)
    }

    @Test
    fun dirty_tracking_round_trip() {
        val core = CursorPagingCore<String>()
        core.markDirty("a")
        core.markDirty(listOf("b", "c"))
        assertTrue(core.isDirty("a") && core.isDirty("b") && core.isDirty("c"))
        core.clearDirty("b")
        assertTrue(!core.isDirty("b"))
        core.clearAllDirty()
        assertTrue(core.isDirtyCursorsEmpty())
    }

    @Test
    fun drainDirtyCursorsInRange_returns_only_in_range_and_clears_them() {
        val core = CursorPagingCore<String>(initialCapacity = 3)
        populate(core)
        core.markDirty(listOf("p0", "p2", "p4"))

        val start = core.cache.getCursorOf("p1")!!
        val end = core.cache.getCursorOf("p3")!!
        val drained = core.drainDirtyCursorsInRange(start, end)
        assertNotNull(drained)
        assertEquals(setOf<Any>("p2"), drained.toSet())
        assertTrue(core.isDirty("p0"), "p0 was outside the range → must stay dirty")
        assertTrue(core.isDirty("p4"))
        assertTrue(!core.isDirty("p2"), "p2 should have been drained")
    }

    @Test
    fun drainDirtyCursorsInRange_returns_null_when_nothing_in_range() {
        val core = CursorPagingCore<String>(initialCapacity = 3)
        populate(core)
        core.markDirty("p0")
        val start = core.cache.getCursorOf("p2")!!
        val end = core.cache.getCursorOf("p4")!!
        assertNull(core.drainDirtyCursorsInRange(start, end))
    }

    @Test
    fun resize_is_not_supported() {
        val core = CursorPagingCore<String>(initialCapacity = 3)
        assertFailsWith<UnsupportedOperationException> { core.resize(42) }
    }

    @Test
    fun release_resets_context_and_dirty() {
        val core = CursorPagingCore<String>(initialCapacity = 3)
        populate(core)
        core.startContextCursor = core.cache.getCursorOf("p0")
        core.endContextCursor = core.cache.getCursorOf("p2")
        core.markDirty("p0")
        core.release(capacity = 7)
        assertEquals(0, core.size)
        assertNull(core.startContextCursor)
        assertNull(core.endContextCursor)
        assertTrue(core.isDirtyCursorsEmpty())
        assertEquals(7, core.capacity)
    }

    @Test
    fun scan_inclusive_of_both_ends() {
        val core = CursorPagingCore<String>(initialCapacity = 3)
        populate(core)
        val list = core.scan(
            core.cache.getCursorOf("p1")!! to core.cache.getCursorOf("p3")!!,
        )
        assertEquals(3, list.size)
    }

    @Test
    fun scan_single_page_window() {
        val core = CursorPagingCore<String>(initialCapacity = 3)
        populate(core)
        val cursor = core.cache.getCursorOf("p2")!!
        val list = core.scan(cursor to cursor)
        assertEquals(1, list.size)
    }

    @Test
    fun isFilledSuccessState_rejects_non_success_states() {
        val core = CursorPagingCore<String>(initialCapacity = 3)
        assertTrue(core.isFilledSuccessState(full(1, 3)))
        assertTrue(!core.isFilledSuccessState(EmptyPage<String>(page = 1, data = emptyList())))
        assertTrue(
            !core.isFilledSuccessState(
                ProgressPage<String>(page = 1, data = mutableListOf("x"))
            )
        )
        assertTrue(
            !core.isFilledSuccessState(
                ErrorPage<String>(
                    exception = RuntimeException("boom"),
                    page = 1,
                    data = mutableListOf(),
                )
            )
        )
        assertTrue(!core.isFilledSuccessState(null))
    }

    @Test
    fun isFilledSuccessState_returns_false_for_partial_page_under_limited_capacity() {
        val core = CursorPagingCore<String>(initialCapacity = 3)
        val partial = SuccessPage(page = 1, data = mutableListOf("only-one"))
        assertTrue(!core.isFilledSuccessState(partial))
    }

    @Test
    fun isFilledSuccessState_returns_true_for_any_size_under_unlimited_capacity() {
        val core = CursorPagingCore<String>(initialCapacity = CursorPagingCore.UNLIMITED_CAPACITY)
        val partial = SuccessPage(page = 1, data = mutableListOf("a"))
        assertTrue(core.isFilledSuccessState(partial))
    }
}
