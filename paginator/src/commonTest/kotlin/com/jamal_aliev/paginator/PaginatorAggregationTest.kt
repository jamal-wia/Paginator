package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.extension.all
import com.jamal_aliev.paginator.extension.any
import com.jamal_aliev.paginator.extension.contains
import com.jamal_aliev.paginator.extension.count
import com.jamal_aliev.paginator.extension.elementAtOrNull
import com.jamal_aliev.paginator.extension.find
import com.jamal_aliev.paginator.extension.findLast
import com.jamal_aliev.paginator.extension.firstOrNull
import com.jamal_aliev.paginator.extension.flatMap
import com.jamal_aliev.paginator.extension.flatten
import com.jamal_aliev.paginator.extension.forEachIndexed
import com.jamal_aliev.paginator.extension.lastOrNull
import com.jamal_aliev.paginator.extension.loadedItemsCount
import com.jamal_aliev.paginator.extension.loadedPagesCount
import com.jamal_aliev.paginator.extension.mapPages
import com.jamal_aliev.paginator.extension.none
import com.jamal_aliev.paginator.load.LoadResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaginatorAggregationTest {

    // =========================================================================
    // find / findLast
    // =========================================================================

    @Test
    fun `find returns the first matching element`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val found = paginator.find { it.endsWith("item1") }
        assertEquals("p1_item1", found)
    }

    @Test
    fun `find returns null when nothing matches`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        assertNull(paginator.find { it == "missing" })
    }

    @Test
    fun `findLast returns the last matching element`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val found = paginator.findLast { it.endsWith("item0") }
        assertEquals("p3_item0", found)
    }

    @Test
    fun `findLast returns null when nothing matches`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        assertNull(paginator.findLast { it == "missing" })
    }

    // =========================================================================
    // any / none / all
    // =========================================================================

    @Test
    fun `any returns true when at least one element matches`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        assertTrue(paginator.any { it == "p2_item1" })
    }

    @Test
    fun `any returns false when nothing matches`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        assertFalse(paginator.any { it.startsWith("zzz") })
    }

    @Test
    fun `none is the inverse of any`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        assertTrue(paginator.none { it.startsWith("zzz") })
        assertFalse(paginator.none { it == "p1_item0" })
    }

    @Test
    fun `all returns true when every element matches`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        assertTrue(paginator.all { it.startsWith("p") })
    }

    @Test
    fun `all returns false when at least one element fails the predicate`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        assertFalse(paginator.all { it.endsWith("item0") })
    }

    @Test
    fun `all is vacuously true on empty paginator`() {
        val paginator = MutablePaginator<String> { LoadResult(emptyList()) }
        assertTrue(paginator.all { false })
    }

    // =========================================================================
    // count
    // =========================================================================

    @Test
    fun `count without predicate returns total loaded items`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 4)
        assertEquals(12, paginator.count())
    }

    @Test
    fun `count with predicate returns matching elements`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        assertEquals(3, paginator.count { it.endsWith("item0") })
    }

    // =========================================================================
    // firstOrNull / lastOrNull / elementAtOrNull
    // =========================================================================

    @Test
    fun `firstOrNull returns the first loaded element`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        assertEquals("p1_item0", paginator.firstOrNull())
    }

    @Test
    fun `lastOrNull returns the last loaded element`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        assertEquals("p2_item2", paginator.lastOrNull())
    }

    @Test
    fun `firstOrNull returns null when nothing is loaded`() {
        val paginator = MutablePaginator<String> { LoadResult(emptyList()) }
        assertNull(paginator.firstOrNull())
    }

    @Test
    fun `elementAtOrNull addresses the flat global index`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        // page 1 holds indices 0..2, page 2 holds 3..5.
        assertEquals("p1_item0", paginator.elementAtOrNull(0))
        assertEquals("p1_item2", paginator.elementAtOrNull(2))
        assertEquals("p2_item0", paginator.elementAtOrNull(3))
        assertEquals("p2_item2", paginator.elementAtOrNull(5))
    }

    @Test
    fun `elementAtOrNull returns null for out-of-range and negative indices`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        assertNull(paginator.elementAtOrNull(-1))
        assertNull(paginator.elementAtOrNull(99))
    }

    // =========================================================================
    // flatten / flatMap / mapPages
    // =========================================================================

    @Test
    fun `flatten produces a single ordered list`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 2)
        val all = paginator.flatten()
        assertEquals(listOf("p1_item0", "p1_item1", "p2_item0", "p2_item1"), all)
    }

    @Test
    fun `flatMap expands every element via transform`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 2)
        val expanded = paginator.flatMap { listOf(it, it.uppercase()) }
        assertEquals(
            listOf("p1_item0", "P1_ITEM0", "p1_item1", "P1_ITEM1"),
            expanded,
        )
    }

    @Test
    fun `mapPages projects every page state`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 2)
        val pageNumbers: List<Int> = paginator.mapPages { it.page }
        assertEquals(listOf(1, 2, 3), pageNumbers)
    }

    // =========================================================================
    // forEachIndexed / contains / loadedXxx
    // =========================================================================

    @Test
    fun `forEachIndexed iterates with global indices`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 2)
        val collected: MutableList<Pair<Int, String>> = mutableListOf()
        paginator.forEachIndexed { i, e -> collected.add(i to e) }
        assertEquals(
            listOf(
                0 to "p1_item0",
                1 to "p1_item1",
                2 to "p2_item0",
                3 to "p2_item1",
            ),
            collected,
        )
    }

    @Test
    fun `contains operator finds present and absent elements`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 2)
        assertTrue("p1_item0" in paginator)
        assertFalse("missing" in paginator)
    }

    @Test
    fun `loadedItemsCount and loadedPagesCount expose totals`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 4)
        assertEquals(12, paginator.loadedItemsCount)
        assertEquals(3, paginator.loadedPagesCount)
    }
}
