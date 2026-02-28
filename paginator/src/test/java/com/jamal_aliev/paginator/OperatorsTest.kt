package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.page.PageState.SuccessPage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorsTest {

    @Test
    fun `contains with page number`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        assertTrue(1 in paginator)
        assertTrue(2 in paginator)
        assertTrue(3 in paginator)
        assertFalse(4 in paginator)
    }

    @Test
    fun `contains with PageState`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val state = paginator.core.getStateOf(2)!!
        assertTrue(state in paginator)
        val unknown = SuccessPage(page = 99, data = listOf("x"))
        assertFalse(unknown in paginator)
    }

    @Test
    fun `get operator returns page state`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        assertNotNull(paginator[1])
        assertNotNull(paginator[2])
        assertNull(paginator[3])
    }

    @Test
    fun `get with page and index returns element`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        assertEquals("p1_item0", paginator[1, 0])
        assertEquals("p1_item2", paginator[1, 2])
        assertEquals("p2_item1", paginator[2, 1])
    }

    @Test
    fun `compareTo compares by size`() = runTest {
        val small = createPopulatedPaginator(pageCount = 2, capacity = 3)
        val large = createPopulatedPaginator(pageCount = 5, capacity = 3)
        assertTrue(small < large)
        assertTrue(large > small)
    }

    @Test
    fun `compareTo equal size`() = runTest {
        val a = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val b = createPopulatedPaginator(pageCount = 3, capacity = 3)
        assertEquals(0, a.compareTo(b))
    }

    @Test
    fun `iterator iterates all entries`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val pages = mutableListOf<Int>()
        for (state in paginator) {
            pages.add(state.page)
        }
        assertEquals(listOf(1, 2, 3), pages)
    }

    @Test
    fun `plusAssign adds state`() {
        val paginator = MutablePaginator<String> { emptyList() }
        val state = SuccessPage(page = 1, data = mutableListOf("a"))
        paginator += state
        assertEquals(state, paginator[1])
    }

    @Test
    fun `minusAssign by page number removes and collapses`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val sizeBefore = paginator.core.size
        paginator -= 2
        // removeState collapses: page 3 becomes page 2
        assertEquals(sizeBefore - 1, paginator.core.size)
    }

    @Test
    fun `minusAssign by PageState removes and collapses`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val state = paginator.core.getStateOf(2)!!
        val sizeBefore = paginator.core.size
        paginator -= state
        assertEquals(sizeBefore - 1, paginator.core.size)
    }

    @Test
    fun `equals is identity based`() = runTest {
        val a = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val b = createPopulatedPaginator(pageCount = 3, capacity = 3)
        assertTrue(a == a) // same instance
        assertFalse(a == b) // different instance
    }

    @Test
    fun `hashCode based on cache`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        val hash1 = paginator.hashCode()
        // hashCode should be consistent
        assertEquals(hash1, paginator.hashCode())
    }

    @Test
    fun `toString contains pages info`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        val str = paginator.toString()
        assertTrue(str.contains("MutablePaginator"))
        assertTrue(str.contains("bookmarks"))
    }
}
