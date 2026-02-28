package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.extension.addElement
import com.jamal_aliev.paginator.extension.forEach
import com.jamal_aliev.paginator.extension.getElement
import com.jamal_aliev.paginator.extension.indexOfFirst
import com.jamal_aliev.paginator.extension.indexOfLast
import com.jamal_aliev.paginator.extension.removeElement
import com.jamal_aliev.paginator.extension.setElement
import com.jamal_aliev.paginator.extension.walkBackwardWhile
import com.jamal_aliev.paginator.extension.walkForwardWhile
import com.jamal_aliev.paginator.page.PageState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionFunctionsTest {

    // =========================================================================
    // indexOfFirst / indexOfLast
    // =========================================================================

    @Test
    fun `indexOfFirst finds element across pages`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val result = paginator.indexOfFirst { it == "p2_item1" }
        assertNotNull(result)
        assertEquals(2 to 1, result)
    }

    @Test
    fun `indexOfFirst returns null when not found`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        val result = paginator.indexOfFirst { it == "nonexistent" }
        assertNull(result)
    }

    @Test
    fun `indexOfFirst on specific page`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val result = paginator.indexOfFirst(2) { it == "p2_item2" }
        assertNotNull(result)
        assertEquals(2 to 2, result)
    }

    @Test
    fun `indexOfFirst on specific page returns null when not found`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val result = paginator.indexOfFirst(1) { it == "p2_item0" }
        assertNull(result)
    }

    @Test
    fun `indexOfLast finds last element across pages`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        // All pages have "pX_item0", last occurrence should be on page 3
        val result = paginator.indexOfLast { it.endsWith("item0") }
        assertNotNull(result)
        assertEquals(3 to 0, result)
    }

    @Test
    fun `indexOfLast returns null when not found`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        val result = paginator.indexOfLast { it == "nonexistent" }
        assertNull(result)
    }

    @Test
    fun `indexOfLast on specific page`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        val result = paginator.indexOfLast(1) { it.startsWith("p1_") }
        assertNotNull(result)
        assertEquals(1 to 2, result) // last p1_ element is at index 2
    }

    // =========================================================================
    // getElement (predicate)
    // =========================================================================

    @Test
    fun `getElement finds element by predicate`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val element = paginator.getElement { it == "p2_item1" }
        assertEquals("p2_item1", element)
    }

    @Test
    fun `getElement returns null when not found`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        val element = paginator.getElement { it == "nonexistent" }
        assertNull(element)
    }

    // =========================================================================
    // setElement (predicate extension)
    // =========================================================================

    @Test
    fun `setElement extension replaces first match`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        paginator.setElement(element = "replaced", silently = true) { it == "p2_item1" }

        assertEquals("replaced", paginator.core.getElement(2, 1))
        // Other items on same page unchanged
        assertEquals("p2_item0", paginator.core.getElement(2, 0))
    }

    // =========================================================================
    // removeElement (predicate extension)
    // =========================================================================

    @Test
    fun `removeElement by predicate finds and removes across pages`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val removed = paginator.removeElement { it == "p2_item1" }
        assertEquals("p2_item1", removed)
    }

    @Test
    fun `removeElement by predicate returns null when not found`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        val removed = paginator.removeElement { it == "nonexistent" }
        assertNull(removed)
    }

    @Test
    fun `removeElement by predicate on specific page`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val removed = paginator.removeElement(2) { it == "p2_item0" }
        assertEquals("p2_item0", removed)
    }

    // =========================================================================
    // addElement (extension)
    // =========================================================================

    @Test
    fun `addElement appends to last page at end`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 5)
        // page2 has 5 items (filled at capacity). Increase capacity so there's room.
        paginator.core.resize(capacity = 10, resize = false, silently = true)

        val result = paginator.addElement(element = "new_element", silently = true)
        assertTrue(result)

        val lastPageData = paginator.core.getStateOf(2)!!.data
        assertEquals("new_element", lastPageData.last())
        assertEquals(6, lastPageData.size)
    }

    @Test
    fun `addElement returns false when no pages exist`() {
        val paginator = MutablePaginator<String> { emptyList() }
        val result = paginator.addElement(element = "x", silently = true)
        assertFalse(result)
    }

    @Test
    fun `addElement at specific position`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 5)
        paginator.addElement(
            element = "inserted",
            page = 1,
            index = 0,
            silently = true
        )

        val data = paginator.core.getStateOf(1)!!.data
        assertEquals("inserted", data[0])
        assertEquals("p1_item0", data[1])
    }

    // =========================================================================
    // walkForwardWhile / walkBackwardWhile
    // =========================================================================

    @Test
    fun `walkForwardWhile traverses consecutive pages`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 5, capacity = 3)
        val start = paginator.core.getStateOf(1)
        val last = paginator.walkForwardWhile(start)
        assertNotNull(last)
        assertEquals(5, last!!.page) // walks all the way to page 5
    }

    @Test
    fun `walkForwardWhile stops at gap`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        // Add a non-consecutive page
        paginator.core.setState(
            state = PageState.SuccessPage(
                page = 10,
                data = mutableListOf("x", "y", "z")
            ),
            silently = true
        )
        val start = paginator.core.getStateOf(1)
        val last = paginator.walkForwardWhile(start)
        assertEquals(3, last!!.page) // stops at 3, doesn't reach 10
    }

    @Test
    fun `walkBackwardWhile traverses consecutive pages`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 5, capacity = 3)
        val start = paginator.core.getStateOf(5)
        val first = paginator.walkBackwardWhile(start)
        assertNotNull(first)
        assertEquals(1, first!!.page)
    }

    @Test
    fun `walkForwardWhile returns null for null pivot`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        val result = paginator.walkForwardWhile(null)
        assertNull(result)
    }

    @Test
    fun `walkWhile with custom predicate stops at non-matching`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 5, capacity = 3)
        // Walk forward but stop at page 3 (predicate returns false for page > 3)
        val start = paginator.core.getStateOf(1)
        val result = paginator.walkForwardWhile(start) { it.page <= 3 }
        assertEquals(3, result!!.page)
    }

    @Test
    fun `forEach iterates all page states`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val visited = mutableListOf<Int>()
        paginator.forEach { visited.add(it.page) }
        assertEquals(listOf(1, 2, 3), visited)
    }
}
