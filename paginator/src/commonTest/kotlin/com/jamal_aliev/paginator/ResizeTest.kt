package com.jamal_aliev.paginator

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResizeTest {

    @Test
    fun `resize changes capacity without redistributing when resize is false`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        // Each page has 3 items
        assertEquals(3, paginator.cache.pagingCore.capacity)

        paginator.cache.pagingCore.resize(capacity = 5, resize = false, silently = true)

        assertEquals(5, paginator.cache.pagingCore.capacity)
        // Pages still have 3 items each (not redistributed)
        assertEquals(3, paginator.cache.getStateOf(1)!!.data.size)
        assertEquals(3, paginator.cache.getStateOf(2)!!.data.size)
        assertEquals(3, paginator.cache.getStateOf(3)!!.data.size)
    }

    @Test
    fun `resize with same capacity is noop`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val statesBefore = paginator.cache.pagingCore.states.toList()

        paginator.cache.pagingCore.resize(capacity = 3, resize = true, silently = true)

        assertEquals(statesBefore.size, paginator.cache.size)
    }

    @Test
    fun `resize rejects negative capacity`() {
        val paginator = createDeterministicPaginator()
        assertFailsWith<IllegalArgumentException> {
            paginator.cache.pagingCore.resize(capacity = -1)
        }
    }

    @Test
    fun `resize to unlimited capacity`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        paginator.cache.pagingCore.resize(capacity = PagingCore.UNLIMITED_CAPACITY, resize = false, silently = true)
        assertEquals(0, paginator.cache.pagingCore.capacity)
        assertTrue(paginator.cache.pagingCore.isCapacityUnlimited)
    }

    @Test
    fun `resize redistributes data when resize is true`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        // Total 9 items across 3 pages of capacity 3

        paginator.cache.pagingCore.resize(capacity = 5, resize = true, silently = true)

        // 9 items / 5 per page = 2 pages (5 + 4)
        assertEquals(5, paginator.cache.pagingCore.capacity)
        assertEquals(2, paginator.cache.size)
        assertEquals(5, paginator.cache.getStateOf(1)!!.data.size)
        assertEquals(4, paginator.cache.getStateOf(2)!!.data.size)
    }

    @Test
    fun `resize redistributes preserving data order`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        // page1: [p1_item0, p1_item1, p1_item2]
        // page2: [p2_item0, p2_item1, p2_item2]

        paginator.cache.pagingCore.resize(capacity = 2, resize = true, silently = true)

        // 6 items / 2 per page = 3 pages
        assertEquals(3, paginator.cache.size)
        val all = paginator.cache.pagingCore.states.flatMap { it.data }
        assertEquals(
            listOf("p1_item0", "p1_item1", "p1_item2", "p2_item0", "p2_item1", "p2_item2"),
            all
        )
    }
}
