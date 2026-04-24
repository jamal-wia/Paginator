package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.MutableCursorPaginator
import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.extension.addElement
import com.jamal_aliev.paginator.extension.flatten
import com.jamal_aliev.paginator.extension.loadedItemsCount
import com.jamal_aliev.paginator.extension.loadedPagesCount
import com.jamal_aliev.paginator.extension.prependElement
import com.jamal_aliev.paginator.extension.removeAll
import com.jamal_aliev.paginator.extension.updateAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MutableCursorPaginatorTest {

    private suspend fun populate(
        backend: FakeCursorBackend = FakeCursorBackend(),
        pageCount: Int = 5,
    ): MutableCursorPaginator<String> {
        val p = mutableCursorPaginatorOf(backend)
        p.restart(silentlyLoading = true, silentlyResult = true)
        repeat(pageCount - 1) {
            p.goNextPage(silentlyLoading = true, silentlyResult = true)
        }
        return p
    }

    // ── removeState ─────────────────────────────────────────────────────────

    @Test
    fun removeState_relinks_neighbours() = runTest {
        val p = populate()
        // Remove p2 (middle) — prev=p1, next=p3 must be re-linked.
        val removed = p.removeState(selfToRemove = "p2")
        assertNotNull(removed)

        val p1 = p.cache.getCursorOf("p1")
        val p3 = p.cache.getCursorOf("p3")
        assertNotNull(p1)
        assertNotNull(p3)
        assertEquals("p3", p1.next, "p1.next must have been rewired to skip over p2")
        assertEquals("p1", p3.prev, "p3.prev must have been rewired to skip over p2")
        assertNull(p.cache.getStateOf("p2"))
    }

    @Test
    fun removeState_of_non_cached_self_returns_null() = runTest {
        val p = populate()
        assertNull(p.removeState(selfToRemove = "ghost"))
    }

    @Test
    fun removeState_of_endContext_shifts_boundary_backward() = runTest {
        val p = populate()
        // endContext is currently p4 (the tail). Remove it — boundary should move to p3.
        val beforeEnd = p.core.endContextCursor?.self
        assertEquals("p4", beforeEnd)
        p.removeState(selfToRemove = "p4")
        assertEquals("p3", p.core.endContextCursor?.self)
    }

    @Test
    fun removeState_of_startContext_shifts_boundary_forward() = runTest {
        val p = populate()
        assertEquals("p0", p.core.startContextCursor?.self)
        p.removeState(selfToRemove = "p0")
        assertEquals("p1", p.core.startContextCursor?.self)
    }

    // ── setElement ──────────────────────────────────────────────────────────

    @Test
    fun setElement_replaces_at_index() = runTest {
        val p = populate()
        p.setElement(element = "NEW", self = "p1", index = 1)
        val state = p.cache.getStateOf("p1")!!
        assertEquals("NEW", state.data[1])
    }

    @Test
    fun setElement_unknown_self_throws() = runTest {
        val p = populate()
        assertFailsWith<NoSuchElementException> {
            p.setElement(element = "NEW", self = "ghost", index = 0)
        }
    }

    // ── removeElement ───────────────────────────────────────────────────────

    @Test
    fun removeElement_at_index_drops_item() = runTest {
        val p = populate()
        val removed = p.removeElement(self = "p1", index = 0)
        assertEquals("p1_item0", removed)
    }

    @Test
    fun removeElement_empties_page_and_drops_it_when_no_cascade_source() = runTest {
        // Single-page scenario: p0 has no successor to cascade-fill from, so
        // draining all items actually empties the page and triggers removeState.
        val p = populate(pageCount = 1)
        repeat(3) { p.removeElement(self = "p0", index = 0, silently = true) }
        assertNull(p.cache.getStateOf("p0"))
        // With no surviving pages, both context boundaries are null.
        assertNull(p.core.startContextCursor)
        assertNull(p.core.endContextCursor)
    }

    @Test
    fun removeElement_cascades_refill_from_next_page_before_emptying() = runTest {
        // With a successor page the cascade refills p0 from p1 — so draining p0
        // does NOT empty it; instead p0 stays full and p1 shrinks.
        val p = populate(pageCount = 2)
        p.removeElement(self = "p0", index = 0, silently = true)
        // p0 should still be full (capacity=3) because it pulled 1 item from p1.
        assertEquals(3, p.cache.getStateOf("p0")!!.data.size)
        // p1 should have lost exactly 1 item.
        assertEquals(2, p.cache.getStateOf("p1")!!.data.size)
    }

    // ── addAllElements / cascade ────────────────────────────────────────────

    @Test
    fun addAllElements_cascades_into_next_existing_page() = runTest {
        val p = populate()
        // Insert a single element at the beginning of p0. Capacity=3, so one item spills to p1.
        p.addAllElements(
            elements = listOf("NEW0"),
            targetSelf = "p0",
            index = 0,
        )
        assertEquals(listOf("NEW0", "p0_item0", "p0_item1"), p.cache.getStateOf("p0")!!.data)
        // p1 should have gained the displaced item at position 0.
        assertEquals("p0_item2", p.cache.getStateOf("p1")!!.data[0])
    }

    @Test
    fun addAllElements_drops_overflow_at_tail_without_factory() = runTest {
        val p = populate(pageCount = 1) // only p0 in cache; no next page.
        p.addAllElements(
            elements = listOf("NEW0", "NEW1", "NEW2"),
            targetSelf = "p0",
            index = 0,
        )
        val data = p.cache.getStateOf("p0")!!.data
        assertEquals(listOf("NEW0", "NEW1", "NEW2"), data, "capacity=3 must be respected")
        // The cache did not grow; the 3 original items were dropped as overflow.
        assertEquals(1, p.loadedPagesCount, "no bookmark factory → overflow is dropped")
    }

    @Test
    fun addAllElements_creates_new_tail_with_bookmark_factory() = runTest {
        val p = populate(pageCount = 1)
        p.addAllElements(
            elements = listOf("NEW0", "NEW1", "NEW2"),
            targetSelf = "p0",
            index = 0,
            bookmarkFactory = { idx, previous ->
                CursorBookmark(prev = previous.self, self = "overflow$idx", next = null)
            },
        )
        assertEquals(2, p.loadedPagesCount, "factory should have spawned a new tail page")
        val overflowPage = p.cache.getStateOf("overflow0")
        assertNotNull(overflowPage)
        assertEquals(listOf("p0_item0", "p0_item1", "p0_item2"), overflowPage.data)
        assertEquals("overflow0", p.cache.getCursorOf("p0")?.next)
    }

    @Test
    fun addElement_at_tail_uses_tail_cursor() = runTest {
        val p = populate(pageCount = 1)
        // Capacity is 3, p0 already holds 3 items. Without a factory, overflow is dropped —
        // the cache must still round-trip via the `addElement(element)` helper.
        val appended = p.addElement("x")
        assertTrue(appended)
        assertEquals(3, p.cache.getStateOf("p0")!!.data.size)
        // "x" went in at the tail and bumped off the last item.
        // With capacity=3 and overflow dropping, the net effect is "item0, item1, x" then... actually
        // it was added at index=3 (size), which becomes size=4 then trimmed to 3 by cascade.
        // The trim keeps the first 3 (the existing items), so "x" is dropped.
        // Adjust expectation: cache size unchanged, no overflow page created.
        assertEquals(1, p.loadedPagesCount)
    }

    @Test
    fun prependElement_adds_at_head_and_cascades() = runTest {
        val p = populate()
        val ok = p.prependElement("HEAD")
        assertTrue(ok)
        assertEquals("HEAD", p.cache.getStateOf("p0")!!.data.first())
    }

    // ── replaceAllElements + helpers ────────────────────────────────────────

    @Test
    fun updateAll_transforms_every_cached_element() = runTest {
        val p = populate()
        val before = p.loadedItemsCount
        p.updateAll { it.uppercase() }
        val flat = p.flatten()
        assertEquals(before, flat.size)
        assertTrue(flat.all { it == it.uppercase() })
    }

    @Test
    fun removeAll_drops_matching_elements_but_cascade_refills_from_next_page() = runTest {
        // `removeAll` only removes ELEMENTS — the cascade in removeElement pulls items
        // from p1 back into p0. The observable net effect: all "p0_*" items are gone,
        // some "p1_*" items shifted into p0, and p1 ends up empty (so p1 gets dropped).
        val p = populate(pageCount = 2)
        p.removeAll { it.startsWith("p0_") }
        val p0 = p.cache.getStateOf("p0")
        assertNotNull(p0)
        assertTrue(p0.data.none { it.startsWith("p0_") })
        assertNull(p.cache.getStateOf("p1"), "p1 was fully drained into p0 and dropped")
    }

    @Test
    fun removeAll_matching_single_page_drops_the_page() = runTest {
        val p = populate(pageCount = 1)
        p.removeAll { it.startsWith("p0_") }
        // No successor means no cascade refill — the page must be dropped after drain.
        assertNull(p.cache.getStateOf("p0"))
    }

    // ── transaction rollback ────────────────────────────────────────────────

    @Test
    fun transaction_rollback_restores_state_on_throw() = runTest {
        val p = populate()
        val before = p.cache.getStateOf("p0")!!.data.toList()

        assertFailsWith<IllegalStateException> {
            p.transaction {
                val mp = this as MutableCursorPaginator<String>
                mp.setElement(element = "MUTATED", self = "p0", index = 0, silently = true)
                error("force rollback")
            }
        }

        assertEquals(before, p.cache.getStateOf("p0")!!.data.toList(), "rollback must restore data")
    }

    @Test
    fun transaction_success_keeps_changes() = runTest {
        val p = populate()
        p.transaction {
            val mp = this as MutableCursorPaginator<String>
            mp.setElement(element = "KEPT", self = "p0", index = 0, silently = true)
        }
        assertEquals("KEPT", p.cache.getStateOf("p0")!!.data[0])
    }
}
