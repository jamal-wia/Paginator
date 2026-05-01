package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.extension.distinctBy
import com.jamal_aliev.paginator.extension.insertAfter
import com.jamal_aliev.paginator.extension.insertBefore
import com.jamal_aliev.paginator.extension.moveElement
import com.jamal_aliev.paginator.extension.prependElement
import com.jamal_aliev.paginator.extension.removeAll
import com.jamal_aliev.paginator.extension.retainAll
import com.jamal_aliev.paginator.extension.swapElements
import com.jamal_aliev.paginator.extension.updateAll
import com.jamal_aliev.paginator.extension.updateWhere
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaginatorCrudExtTest {

    // =========================================================================
    // prependElement
    // =========================================================================

    @Test
    fun `prependElement inserts at the beginning of the first cached page`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        val inserted = paginator.prependElement("new_first", silently = true)

        assertTrue(inserted)
        assertEquals("new_first", paginator.cache.getElement(1, 0))
        // Capacity overflow cascades: original last item of page 1 → page 2 head
        assertEquals(3, paginator.cache.getStateOf(1)!!.data.size)
    }

    @Test
    fun `prependElement returns false when cache is empty`() {
        val paginator = MutablePaginator<String> { LoadResultEmpty() }
        val inserted = paginator.prependElement("x", silently = true)
        assertFalse(inserted)
    }

    // =========================================================================
    // swapElements
    // =========================================================================

    @Test
    fun `swapElements swaps values within a single page`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        paginator.swapElements(aPage = 1, aIndex = 0, bPage = 1, bIndex = 2, silently = true)

        assertEquals("p1_item2", paginator.cache.getElement(1, 0))
        assertEquals("p1_item1", paginator.cache.getElement(1, 1))
        assertEquals("p1_item0", paginator.cache.getElement(1, 2))
    }

    @Test
    fun `swapElements swaps values across pages`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        paginator.swapElements(aPage = 1, aIndex = 0, bPage = 2, bIndex = 2, silently = true)

        assertEquals("p2_item2", paginator.cache.getElement(1, 0))
        assertEquals("p1_item0", paginator.cache.getElement(2, 2))
    }

    @Test
    fun `swapElements is a noop when both coordinates are equal`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        paginator.swapElements(aPage = 1, aIndex = 1, bPage = 1, bIndex = 1, silently = true)
        assertEquals("p1_item1", paginator.cache.getElement(1, 1))
    }

    @Test
    fun `swapElements throws when page is missing`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        assertFailsWith<NoSuchElementException> {
            paginator.swapElements(aPage = 1, aIndex = 0, bPage = 9, bIndex = 0, silently = true)
        }
    }

    // =========================================================================
    // moveElement
    // =========================================================================

    @Test
    fun `moveElement reorders within a single page forward`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        paginator.moveElement(fromPage = 1, fromIndex = 0, toPage = 1, toIndex = 2, silently = true)

        assertEquals("p1_item1", paginator.cache.getElement(1, 0))
        assertEquals("p1_item2", paginator.cache.getElement(1, 1))
        assertEquals("p1_item0", paginator.cache.getElement(1, 2))
    }

    @Test
    fun `moveElement reorders within a single page backward`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        paginator.moveElement(fromPage = 1, fromIndex = 2, toPage = 1, toIndex = 0, silently = true)

        assertEquals("p1_item2", paginator.cache.getElement(1, 0))
        assertEquals("p1_item0", paginator.cache.getElement(1, 1))
        assertEquals("p1_item1", paginator.cache.getElement(1, 2))
    }

    @Test
    fun `moveElement is a noop when from equals to`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        paginator.moveElement(fromPage = 1, fromIndex = 1, toPage = 1, toIndex = 1, silently = true)
        assertEquals("p1_item1", paginator.cache.getElement(1, 1))
    }

    @Test
    fun `moveElement removes empty source page and shifts target down`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 1)
        // page 1: [p1_item0], page 2: [p2_item0]
        paginator.moveElement(fromPage = 1, fromIndex = 0, toPage = 2, toIndex = 0, silently = true)
        // Page 1 became empty and was removed; page 2 collapsed down to page 1.
        // p1_item0 was inserted at the (collapsed) page 1, index 0 → pushing the original
        // p2_item0 to overflow into a fresh page 2.
        assertEquals(1, paginator.cache.pages.size)
        assertEquals("p1_item0", paginator.cache.getElement(1, 0))
    }

    @Test
    fun `moveElement throws when source page is missing`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        assertFailsWith<NoSuchElementException> {
            paginator.moveElement(
                fromPage = 99, fromIndex = 0,
                toPage = 1, toIndex = 0,
                silently = true,
            )
        }
    }

    // =========================================================================
    // insertBefore / insertAfter
    // =========================================================================

    @Test
    fun `insertBefore puts element before first match`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        val inserted = paginator.insertBefore(element = "X", silently = true) {
            it == "p1_item1"
        }

        assertTrue(inserted)
        assertEquals("p1_item0", paginator.cache.getElement(1, 0))
        assertEquals("X", paginator.cache.getElement(1, 1))
        assertEquals("p1_item1", paginator.cache.getElement(1, 2))
    }

    @Test
    fun `insertAfter puts element after first match`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 4)
        val inserted = paginator.insertAfter(element = "X", silently = true) {
            it == "p1_item1"
        }

        assertTrue(inserted)
        assertEquals("p1_item1", paginator.cache.getElement(1, 1))
        assertEquals("X", paginator.cache.getElement(1, 2))
        assertEquals("p1_item2", paginator.cache.getElement(1, 3))
    }

    @Test
    fun `insertBefore returns false when no match found`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        val inserted = paginator.insertBefore(element = "X", silently = true) { it == "missing" }
        assertFalse(inserted)
    }

    // =========================================================================
    // removeAll / retainAll
    // =========================================================================

    @Test
    fun `removeAll deletes matching elements across pages`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        // Match the same suffix across pages.
        val removed = paginator.removeAll(silently = true) { it.endsWith("item0") }

        assertEquals(2, removed)
        // No remaining element ends with item0.
        for (page in paginator.cache.pages) {
            for (e in paginator.cache.getStateOf(page)!!.data) {
                assertFalse(e.endsWith("item0"), "expected no item0 left, found $e")
            }
        }
    }

    @Test
    fun `retainAll keeps only matching elements`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        val removed = paginator.retainAll(silently = true) { it.startsWith("p1_") }

        assertEquals(3, removed) // page 2 had three items, all removed
        for (page in paginator.cache.pages) {
            for (e in paginator.cache.getStateOf(page)!!.data) {
                assertTrue(e.startsWith("p1_"))
            }
        }
    }

    // =========================================================================
    // distinctBy
    // =========================================================================

    @Test
    fun `distinctBy removes duplicates by key`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        // Use a key that collapses every "pX_itemY" to just "Y".
        val removed = paginator.distinctBy(silently = true) { it.substringAfterLast("_") }

        // page1 had item0/item1/item2, page2 had item0/item1/item2 → 3 dups removed
        assertEquals(3, removed)
    }

    @Test
    fun `distinctBy is a noop when all keys are unique`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        val removed = paginator.distinctBy(silently = true) { it } // all values are unique
        assertEquals(0, removed)
    }

    // =========================================================================
    // updateAll / updateWhere
    // =========================================================================

    @Test
    fun `updateAll transforms every element`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        paginator.updateAll(silently = true) { it.uppercase() }

        for (page in paginator.cache.pages) {
            for (e in paginator.cache.getStateOf(page)!!.data) {
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
        assertEquals("p1_item0_NEW", paginator.cache.getElement(1, 0))
        assertEquals("p2_item0_NEW", paginator.cache.getElement(2, 0))
        // Untouched neighbour
        assertEquals("p1_item1", paginator.cache.getElement(1, 1))
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun <T> LoadResultEmpty(): com.jamal_aliev.paginator.load.LoadResult<T> =
        com.jamal_aliev.paginator.load.LoadResult(emptyList())
}
