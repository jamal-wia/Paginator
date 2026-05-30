package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.cursor.page.CursorPageState

import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.extension.addElement
import com.jamal_aliev.paginator.cursor.extension.distinctBy
import com.jamal_aliev.paginator.cursor.extension.insertAfter
import com.jamal_aliev.paginator.cursor.extension.insertBefore
import com.jamal_aliev.paginator.cursor.extension.moveElement
import com.jamal_aliev.paginator.cursor.extension.prependElement
import com.jamal_aliev.paginator.cursor.extension.removeAll
import com.jamal_aliev.paginator.cursor.extension.removeElement
import com.jamal_aliev.paginator.cursor.extension.retainAll
import com.jamal_aliev.paginator.cursor.extension.setElement
import com.jamal_aliev.paginator.cursor.extension.swapElements
import com.jamal_aliev.paginator.cursor.extension.updateAll
import com.jamal_aliev.paginator.cursor.extension.updateWhere
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CursorPaginatorCrudExtTest {

    /**
     * Builds a [MutableCursorPaginator] populated with [pageCount] consecutive pages
     * (`p0…p{pageCount-1}`), each holding [pageSize] items named `pN_itemK`.
     *
     * [capacity] defaults to [pageSize] (pages start full). Pass a larger capacity
     * when a test needs headroom for CRUD insertions before overflow cascades.
     */
    private suspend fun createPopulatedPaginator(
        pageCount: Int,
        capacity: Int,
        pageSize: Int = capacity,
    ): MutableCursorPaginator<String, String> {
        val backend = FakeCursorBackend(FakeCursorBackend.defaultPages(pageCount, pageSize))
        val paginator = mutableCursorPaginatorOf(backend, capacity = capacity)
        paginator.jump(
            bookmark = CursorBookmark<String>(prev = null, self = backend.head.self, next = null),
            silentlyLoading = true,
            silentlyResult = true,
        )
        repeat(pageCount - 1) {
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        }
        return paginator
    }

    // =========================================================================
    // addElement (tail)
    // =========================================================================

    @Test
    fun `addElement appends to the tail page when capacity has headroom`() = runTest {
        // pageSize=3 < capacity=10 → tail has room for the new element without cascading.
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 10, pageSize = 3)
        val ok = paginator.addElement("appended", silently = true)

        assertTrue(ok)
        val tail = paginator.core.tailCursor()!!
        val tailData = paginator.cache.getStateOf(tail.self)!!.data
        assertEquals("appended", tailData.last())
        assertEquals(4, tailData.size)
    }

    @Test
    fun `addElement returns false on empty cache`() {
        val paginator = mutableCursorPaginatorOf()
        val ok = paginator.addElement("x", silently = true)
        assertFalse(ok)
    }

    // =========================================================================
    // prependElement
    // =========================================================================

    @Test
    fun `prependElement inserts at the beginning of the head page`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 5)
        val ok = paginator.prependElement("new_first", silently = true)

        assertTrue(ok)
        assertEquals("new_first", paginator.cache.getElement("p0", 0))
    }

    @Test
    fun `prependElement returns false when cache is empty`() {
        val paginator = mutableCursorPaginatorOf()
        val ok = paginator.prependElement("x", silently = true)
        assertFalse(ok)
    }

    // =========================================================================
    // setElement (predicate)
    // =========================================================================

    @Test
    fun `setElement by predicate replaces first match`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        paginator.setElement(element = "REPLACED", silently = true) { it == "p1_item1" }

        assertEquals("REPLACED", paginator.cache.getElement("p1", 1))
        // Other elements untouched.
        assertEquals("p1_item0", paginator.cache.getElement("p1", 0))
        assertEquals("p0_item0", paginator.cache.getElement("p0", 0))
    }

    @Test
    fun `setElement by predicate is noop when no match`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        paginator.setElement(element = "REPLACED", silently = true) { it == "missing" }

        assertEquals("p0_item0", paginator.cache.getElement("p0", 0))
        assertEquals("p0_item1", paginator.cache.getElement("p0", 1))
        assertEquals("p0_item2", paginator.cache.getElement("p0", 2))
    }

    // =========================================================================
    // removeElement (predicate)
    // =========================================================================

    @Test
    fun `removeElement by predicate removes first match across pages`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        val removed = paginator.removeElement { it == "p1_item2" }

        assertEquals("p1_item2", removed)
        val p1Data = paginator.cache.getStateOf("p1")!!.data
        assertFalse(p1Data.contains("p1_item2"))
    }

    @Test
    fun `removeElement by predicate returns null when no match`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        val removed = paginator.removeElement { it == "missing" }
        assertNull(removed)
    }

    @Test
    fun `removeElement by self and predicate removes from given page only`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        val removed = paginator.removeElement(self = "p1") { it.endsWith("item1") }

        assertEquals("p1_item1", removed)
        // The matching element on p0 is left alone.
        assertEquals("p0_item1", paginator.cache.getElement("p0", 1))
    }

    @Test
    fun `removeElement by self returns null when self not in cache`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        val removed = paginator.removeElement(self = "p99") { true }
        assertNull(removed)
    }

    // =========================================================================
    // swapElements
    // =========================================================================

    @Test
    fun `swapElements swaps values within a single page`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        paginator.swapElements(
            aSelf = "p0", aIndex = 0,
            bSelf = "p0", bIndex = 2,
            silently = true,
        )

        assertEquals("p0_item2", paginator.cache.getElement("p0", 0))
        assertEquals("p0_item1", paginator.cache.getElement("p0", 1))
        assertEquals("p0_item0", paginator.cache.getElement("p0", 2))
    }

    @Test
    fun `swapElements swaps values across pages`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        paginator.swapElements(
            aSelf = "p0", aIndex = 0,
            bSelf = "p1", bIndex = 2,
            silently = true,
        )

        assertEquals("p1_item2", paginator.cache.getElement("p0", 0))
        assertEquals("p0_item0", paginator.cache.getElement("p1", 2))
    }

    @Test
    fun `swapElements is a noop when both coordinates are equal`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        paginator.swapElements(
            aSelf = "p0", aIndex = 1,
            bSelf = "p0", bIndex = 1,
            silently = true,
        )
        assertEquals("p0_item1", paginator.cache.getElement("p0", 1))
    }

    @Test
    fun `swapElements throws when self is missing`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        assertFailsWith<NoSuchElementException> {
            paginator.swapElements(
                aSelf = "p0", aIndex = 0,
                bSelf = "p99", bIndex = 0,
                silently = true,
            )
        }
    }

    // =========================================================================
    // moveElement
    // =========================================================================

    @Test
    fun `moveElement reorders within a single page forward`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        paginator.moveElement(
            fromSelf = "p0", fromIndex = 0,
            toSelf = "p0", toIndex = 2,
            silently = true,
        )

        // Element was removed at 0 (data: [item1, item2]) then re-inserted at index 2.
        // Final order: [item1, item2, item0]
        assertEquals("p0_item1", paginator.cache.getElement("p0", 0))
        assertEquals("p0_item2", paginator.cache.getElement("p0", 1))
        assertEquals("p0_item0", paginator.cache.getElement("p0", 2))
    }

    @Test
    fun `moveElement reorders within a single page backward`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        paginator.moveElement(
            fromSelf = "p0", fromIndex = 2,
            toSelf = "p0", toIndex = 0,
            silently = true,
        )

        assertEquals("p0_item2", paginator.cache.getElement("p0", 0))
        assertEquals("p0_item0", paginator.cache.getElement("p0", 1))
        assertEquals("p0_item1", paginator.cache.getElement("p0", 2))
    }

    @Test
    fun `moveElement is a noop when from equals to`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        paginator.moveElement(
            fromSelf = "p0", fromIndex = 1,
            toSelf = "p0", toIndex = 1,
            silently = true,
        )
        assertEquals("p0_item1", paginator.cache.getElement("p0", 1))
    }

    @Test
    fun `moveElement removes empty source page`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 1)
        // p0: [p0_item0], p1: [p1_item0]
        paginator.moveElement(
            fromSelf = "p0", fromIndex = 0,
            toSelf = "p1", toIndex = 0,
            silently = true,
        )

        // p0 emptied → removed from cache; p1 still present (relinked as new head).
        assertNull(paginator.cache.getStateOf("p0"))
        assertEquals("p0_item0", paginator.cache.getElement("p1", 0))
    }

    @Test
    fun `moveElement throws when source self is missing`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        assertFailsWith<NoSuchElementException> {
            paginator.moveElement(
                fromSelf = "p99", fromIndex = 0,
                toSelf = "p0", toIndex = 0,
                silently = true,
            )
        }
    }

    // =========================================================================
    // insertBefore / insertAfter
    // =========================================================================

    @Test
    fun `insertBefore puts element before first match`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 4)
        val inserted = paginator.insertBefore(element = "X", silently = true) {
            it == "p0_item1"
        }

        assertTrue(inserted)
        assertEquals("p0_item0", paginator.cache.getElement("p0", 0))
        assertEquals("X", paginator.cache.getElement("p0", 1))
        assertEquals("p0_item1", paginator.cache.getElement("p0", 2))
    }

    @Test
    fun `insertAfter puts element after first match`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 5)
        val inserted = paginator.insertAfter(element = "X", silently = true) {
            it == "p0_item1"
        }

        assertTrue(inserted)
        assertEquals("p0_item1", paginator.cache.getElement("p0", 1))
        assertEquals("X", paginator.cache.getElement("p0", 2))
        assertEquals("p0_item2", paginator.cache.getElement("p0", 3))
    }

    @Test
    fun `insertBefore returns false when no match found`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        val inserted = paginator.insertBefore(element = "X", silently = true) { it == "missing" }
        assertFalse(inserted)
    }

    @Test
    fun `insertAfter returns false when no match found`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        val inserted = paginator.insertAfter(element = "X", silently = true) { it == "missing" }
        assertFalse(inserted)
    }

    // =========================================================================
    // removeAll / retainAll
    // =========================================================================

    @Test
    fun `removeAll deletes matching elements across pages`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        val removed = paginator.removeAll(silently = true) { it.endsWith("item0") }

        assertEquals(2, removed)
        for (state in paginator.core.states) {
            for (e in state.data) {
                assertFalse(e.endsWith("item0"), "expected no item0 left, found $e")
            }
        }
    }

    @Test
    fun `removeAll returns zero when nothing matches`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        val removed = paginator.removeAll(silently = true) { it == "missing" }
        assertEquals(0, removed)
    }

    @Test
    fun `retainAll keeps only matching elements`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        val removed = paginator.retainAll(silently = true) { it.startsWith("p0_") }

        assertEquals(3, removed) // p1's three items removed
        for (state in paginator.core.states) {
            for (e in state.data) {
                assertTrue(e.startsWith("p0_"))
            }
        }
    }

    // =========================================================================
    // distinctBy
    // =========================================================================

    @Test
    fun `distinctBy removes duplicates by key`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        // Each page has item0/item1/item2; collapsing on suffix removes 3 dups.
        val removed = paginator.distinctBy(silently = true) { it.substringAfterLast("_") }

        assertEquals(3, removed)
    }

    @Test
    fun `distinctBy is a noop when all keys are unique`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        val removed = paginator.distinctBy(silently = true) { it }
        assertEquals(0, removed)
    }

    // =========================================================================
    // updateAll / updateWhere
    // =========================================================================

    @Test
    fun `updateAll transforms every element`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        paginator.updateAll(silently = true) { it.uppercase() }

        for (state in paginator.core.states) {
            for (e in state.data) {
                assertEquals(e, e.uppercase())
            }
        }
    }

    @Test
    fun `updateWhere transforms only matching elements and reports count`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        val updated = paginator.updateWhere(
            silently = true,
            predicate = { it.endsWith("item0") },
            transform = { "${it}_NEW" },
        )

        assertEquals(2, updated)
        assertEquals("p0_item0_NEW", paginator.cache.getElement("p0", 0))
        assertEquals("p1_item0_NEW", paginator.cache.getElement("p1", 0))
        assertEquals("p0_item1", paginator.cache.getElement("p0", 1))
    }

    @Test
    fun `updateWhere returns zero when no match`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        val updated = paginator.updateWhere(
            silently = true,
            predicate = { it == "missing" },
            transform = { "${it}_NEW" },
        )
        assertEquals(0, updated)
    }
}
