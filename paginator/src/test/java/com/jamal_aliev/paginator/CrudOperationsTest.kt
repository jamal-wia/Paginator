package com.jamal_aliev.paginator

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrudOperationsTest {

    // =========================================================================
    // setElement
    // =========================================================================

    @Test
    fun `setElement replaces element at given position`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        assertEquals("p1_item0", paginator.getElement(1, 0))

        paginator.setElement(
            element = "replaced",
            page = 1,
            index = 0,
            silently = true
        )

        assertEquals("replaced", paginator.getElement(1, 0))
        assertEquals("p1_item1", paginator.getElement(1, 1))
        assertEquals("p1_item2", paginator.getElement(1, 2))
    }

    @Test(expected = NoSuchElementException::class)
    fun `setElement throws when page not found`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        paginator.setElement(element = "x", page = 99, index = 0, silently = true)
    }

    @Test
    fun `setElement with isDirty marks page dirty`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        paginator.setElement(
            element = "new",
            page = 1,
            index = 0,
            silently = true,
            isDirty = true
        )
        assertTrue(paginator.isDirty(1))
    }

    // =========================================================================
    // removeElement
    // =========================================================================

    @Test
    fun `removeElement removes element and returns it`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val removed = paginator.removeElement(page = 1, index = 1, silently = true)
        assertEquals("p1_item1", removed)
    }

    @Test
    fun `removeElement rebalances from next page`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        // page1: [p1_item0, p1_item1, p1_item2]
        // page2: [p2_item0, p2_item1, p2_item2]

        paginator.removeElement(page = 1, index = 0, silently = true)

        // After removing p1_item0, should pull p2_item0 from page 2
        val page1Data = paginator.getStateOf(1)!!.data
        assertEquals(3, page1Data.size) // rebalanced to capacity
        assertEquals("p1_item1", page1Data[0])
        assertEquals("p1_item2", page1Data[1])
        assertEquals("p2_item0", page1Data[2])

        // page2 should have lost its first element
        val page2Data = paginator.getStateOf(2)!!.data
        assertEquals(3, page2Data.size) // rebalanced from page 3
        assertEquals("p2_item1", page2Data[0])
        assertEquals("p2_item2", page2Data[1])
        assertEquals("p3_item0", page2Data[2])
    }

    @Test
    fun `removeElement empties page and removes it`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 1)
        paginator.removeElement(page = 1, index = 0, silently = true)
        assertNull(paginator.getStateOf(1))
        assertEquals(0, paginator.size)
    }

    @Test
    fun `removeElement with isDirty marks page dirty`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        paginator.removeElement(page = 1, index = 0, silently = true, isDirty = true)
        assertTrue(paginator.isDirty(1))
    }

    // =========================================================================
    // addAllElements
    // =========================================================================

    @Test
    fun `addAllElements inserts at given index`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 5)
        // page1: [p1_item0, ..., p1_item4]

        paginator.addAllElements(
            elements = listOf("new1", "new2"),
            targetPage = 1,
            index = 1,
            silently = true
        )

        val data = paginator.getStateOf(1)!!.data
        assertEquals(5, data.size) // capacity enforced
        assertEquals("p1_item0", data[0])
        assertEquals("new1", data[1])
        assertEquals("new2", data[2])
        assertEquals("p1_item1", data[3])
        assertEquals("p1_item2", data[4])
    }

    @Test
    fun `addAllElements overflow cascades to next page`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        // page1: [p1_item0, p1_item1, p1_item2]
        // page2: [p2_item0, p2_item1, p2_item2]

        paginator.addAllElements(
            elements = listOf("new1", "new2"),
            targetPage = 1,
            index = 0,
            silently = true
        )

        val page1 = paginator.getStateOf(1)!!.data
        assertEquals(3, page1.size) // capped at capacity
        assertEquals("new1", page1[0])
        assertEquals("new2", page1[1])
        assertEquals("p1_item0", page1[2])

        // Overflow went to page 2
        val page2 = paginator.getStateOf(2)!!.data
        assertEquals("p1_item1", page2[0])
        assertEquals("p1_item2", page2[1])
        assertEquals("p2_item0", page2[2])
    }

    @Test
    fun `addAllElements with isDirty marks page dirty`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 5)
        paginator.addAllElements(
            elements = listOf("x"),
            targetPage = 1,
            index = 0,
            silently = true,
            isDirty = true
        )
        assertTrue(paginator.isDirty(1))
    }

    @Test
    fun `addAllElements overflow without next page removes extra pages`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        // page1: [p1_item0, p1_item1, p1_item2]
        // page2: [p2_item0, p2_item1, p2_item2]

        // Add to page 1 at index 0, causing overflow.
        // Page 2 has same type (SuccessPage) so overflow cascades there.
        // But page 2 is already full, so page 2 overflows.
        // No page 3 and no initPageState → pages after page 2 get removed.
        paginator.addAllElements(
            elements = listOf("x1", "x2", "x3"),
            targetPage = 1,
            index = 0,
            silently = true
        )

        val page1 = paginator.getStateOf(1)!!.data
        assertEquals(3, page1.size)
        assertEquals("x1", page1[0])
        assertEquals("x2", page1[1])
        assertEquals("x3", page1[2])

        // page2 received overflow from page1
        val page2 = paginator.getStateOf(2)!!.data
        assertEquals(3, page2.size)
        assertEquals("p1_item0", page2[0])
        assertEquals("p1_item1", page2[1])
        assertEquals("p1_item2", page2[2])
        // Original page2 data lost (overflow couldn't cascade further)
    }

    // =========================================================================
    // replaceAllElement
    // =========================================================================

    @Test
    fun `replaceAllElement replaces matching elements`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)

        paginator.replaceAllElement(
            providerElement = { _, _, _ -> "replaced" },
            silently = true,
            predicate = { current, _, _ -> current.contains("item1") }
        )

        // p1_item1 and p2_item1 should be replaced
        assertEquals("replaced", paginator.getElement(1, 1))
        assertEquals("replaced", paginator.getElement(2, 1))
        // Others unchanged
        assertEquals("p1_item0", paginator.getElement(1, 0))
        assertEquals("p2_item0", paginator.getElement(2, 0))
    }

    @Test
    fun `replaceAllElement removes element when provider returns null`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        // page1: [p1_item0, p1_item1, p1_item2]

        paginator.replaceAllElement(
            providerElement = { _, _, _ -> null }, // remove
            silently = true,
            predicate = { current, _, _ -> current == "p1_item1" }
        )

        // p1_item1 should be removed
        val data = paginator.getStateOf(1)!!.data
        assertEquals(2, data.size)
        assertEquals("p1_item0", data[0])
        assertEquals("p1_item2", data[1])
    }

    @Test
    fun `replaceAllElement removes consecutive elements correctly (bug fix)`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 5)
        // page1: [p1_item0, p1_item1, p1_item2, p1_item3, p1_item4]

        // Remove all items containing "item1" or "item2" (consecutive)
        paginator.replaceAllElement(
            providerElement = { _, _, _ -> null },
            silently = true,
            predicate = { current, _, _ ->
                current == "p1_item1" || current == "p1_item2"
            }
        )

        val data = paginator.getStateOf(1)!!.data
        assertEquals(3, data.size)
        assertEquals("p1_item0", data[0])
        assertEquals("p1_item3", data[1])
        assertEquals("p1_item4", data[2])
    }

    @Test
    fun `replaceAllElement removes all elements from page`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)

        paginator.replaceAllElement(
            providerElement = { _, _, _ -> null },
            silently = true,
            predicate = { _, _, _ -> true }
        )

        // Page should be removed since all elements were deleted
        assertNull(paginator.getStateOf(1))
    }
}
