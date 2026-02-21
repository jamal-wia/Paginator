package com.jamal_aliev.paginator

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResizeTest {

    @Test
    fun `resize changes capacity without redistributing when resize is false`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        // Each page has 3 items
        assertEquals(3, paginator.capacity)

        paginator.resize(capacity = 5, resize = false, silently = true)

        assertEquals(5, paginator.capacity)
        // Pages still have 3 items each (not redistributed)
        assertEquals(3, paginator.getStateOf(1)!!.data.size)
        assertEquals(3, paginator.getStateOf(2)!!.data.size)
        assertEquals(3, paginator.getStateOf(3)!!.data.size)
    }

    @Test
    fun `resize with same capacity is noop`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val statesBefore = paginator.states.toList()

        paginator.resize(capacity = 3, resize = true, silently = true)

        assertEquals(statesBefore.size, paginator.size)
    }

    @Test
    fun `resize rejects negative capacity`() {
        val paginator = createDeterministicPaginator()
        assertThrows(IllegalArgumentException::class.java) {
            paginator.resize(capacity = -1)
        }
    }

    @Test
    fun `resize to unlimited capacity`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        paginator.resize(capacity = Paginator.UNLIMITED_CAPACITY, resize = false, silently = true)
        assertEquals(0, paginator.capacity)
        assertTrue(paginator.isCapacityUnlimited)
    }

    @Test
    fun `resize redistributes data when resize is true`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        // Total 9 items across 3 pages of capacity 3

        paginator.resize(capacity = 5, resize = true, silently = true)

        // 9 items / 5 per page = 2 pages (5 + 4)
        assertEquals(5, paginator.capacity)
        assertEquals(2, paginator.size)
        assertEquals(5, paginator.getStateOf(1)!!.data.size)
        assertEquals(4, paginator.getStateOf(2)!!.data.size)
    }

    @Test
    fun `resize redistributes preserving data order`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        // page1: [p1_item0, p1_item1, p1_item2]
        // page2: [p2_item0, p2_item1, p2_item2]

        paginator.resize(capacity = 2, resize = true, silently = true)

        // 6 items / 2 per page = 3 pages
        assertEquals(3, paginator.size)
        val all = paginator.states.flatMap { it.data }
        assertEquals(
            listOf("p1_item0", "p1_item1", "p1_item2", "p2_item0", "p2_item1", "p2_item2"),
            all
        )
    }
}
