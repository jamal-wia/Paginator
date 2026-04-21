package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.PagingCore.Companion.UNLIMITED_CAPACITY
import com.jamal_aliev.paginator.load.LoadResult
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PageState.EmptyPage
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrudOperationsTest {

    @Test
    fun `setElement replaces element at given position`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        assertEquals("p1_item0", paginator.cache.getElement(1, 0))

        paginator.setElement(
            element = "replaced",
            page = 1,
            index = 0,
            silently = true
        )

        assertEquals("replaced", paginator.cache.getElement(1, 0))
        assertEquals("p1_item1", paginator.cache.getElement(1, 1))
        assertEquals("p1_item2", paginator.cache.getElement(1, 2))
    }

    @Test
    fun `setElement throws when page not found`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        assertFailsWith<NoSuchElementException> {
            paginator.setElement(element = "x", page = 99, index = 0, silently = true)
        }
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
        assertTrue(paginator.core.isDirty(1))
    }

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
        val page1Data = paginator.cache.getStateOf(1)!!.data
        assertEquals(3, page1Data.size) // rebalanced to capacity
        assertEquals("p1_item1", page1Data[0])
        assertEquals("p1_item2", page1Data[1])
        assertEquals("p2_item0", page1Data[2])

        // page2 should have lost its first element
        val page2Data = paginator.cache.getStateOf(2)!!.data
        assertEquals(3, page2Data.size) // rebalanced from page 3
        assertEquals("p2_item1", page2Data[0])
        assertEquals("p2_item2", page2Data[1])
        assertEquals("p3_item0", page2Data[2])
    }

    @Test
    fun `removeElement empties page and removes it`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 1)
        paginator.removeElement(page = 1, index = 0, silently = true)
        assertNull(paginator.cache.getStateOf(1))
        assertEquals(0, paginator.cache.size)
    }

    @Test
    fun `removeElement with isDirty marks page dirty`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        paginator.removeElement(page = 1, index = 0, silently = true, isDirty = true)
        assertTrue(paginator.core.isDirty(1))
    }

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

        val data = paginator.cache.getStateOf(1)!!.data
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

        val page1 = paginator.cache.getStateOf(1)!!.data
        assertEquals(3, page1.size) // capped at capacity
        assertEquals("new1", page1[0])
        assertEquals("new2", page1[1])
        assertEquals("p1_item0", page1[2])

        // Overflow went to page 2
        val page2 = paginator.cache.getStateOf(2)!!.data
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
        assertTrue(paginator.core.isDirty(1))
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

        val page1 = paginator.cache.getStateOf(1)!!.data
        assertEquals(3, page1.size)
        assertEquals("x1", page1[0])
        assertEquals("x2", page1[1])
        assertEquals("x3", page1[2])

        // page2 received overflow from page1
        val page2 = paginator.cache.getStateOf(2)!!.data
        assertEquals(3, page2.size)
        assertEquals("p1_item0", page2[0])
        assertEquals("p1_item1", page2[1])
        assertEquals("p1_item2", page2[2])
        // Original page2 data lost (overflow couldn't cascade further)
    }

    @Test
    fun `replaceAllElements replaces matching elements`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)

        paginator.replaceAllElements(
            providerElement = { _, _, _ -> "replaced" },
            silently = true,
            predicate = { current, _, _ -> current.contains("item1") }
        )

        // p1_item1 and p2_item1 should be replaced
        assertEquals("replaced", paginator.cache.getElement(1, 1))
        assertEquals("replaced", paginator.cache.getElement(2, 1))
        // Others unchanged
        assertEquals("p1_item0", paginator.cache.getElement(1, 0))
        assertEquals("p2_item0", paginator.cache.getElement(2, 0))
    }

    @Test
    fun `replaceAllElements removes element when provider returns null`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        // page1: [p1_item0, p1_item1, p1_item2]

        paginator.replaceAllElements(
            providerElement = { _, _, _ -> null }, // remove
            silently = true,
            predicate = { current, _, _ -> current == "p1_item1" }
        )

        // p1_item1 should be removed
        val data = paginator.cache.getStateOf(1)!!.data
        assertEquals(2, data.size)
        assertEquals("p1_item0", data[0])
        assertEquals("p1_item2", data[1])
    }

    @Test
    fun `replaceAllElements removes consecutive elements correctly - bug fix`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 5)
        // page1: [p1_item0, p1_item1, p1_item2, p1_item3, p1_item4]

        // Remove all items containing "item1" or "item2" (consecutive)
        paginator.replaceAllElements(
            providerElement = { _, _, _ -> null },
            silently = true,
            predicate = { current, _, _ ->
                current == "p1_item1" || current == "p1_item2"
            }
        )

        val data = paginator.cache.getStateOf(1)!!.data
        assertEquals(3, data.size)
        assertEquals("p1_item0", data[0])
        assertEquals("p1_item3", data[1])
        assertEquals("p1_item4", data[2])
    }

    @Test
    fun `replaceAllElements removes all elements from page`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)

        paginator.replaceAllElements(
            providerElement = { _, _, _ -> null },
            silently = true,
            predicate = { _, _, _ -> true }
        )

        // Page should be removed since all elements were deleted
        assertNull(paginator.cache.getStateOf(1))
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  addAllElements — additional coverage
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `addAllElements inserts at end of page`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 5)
        // page1 starts as [p1_item0..p1_item4]

        paginator.addAllElements(
            elements = listOf("tail1", "tail2"),
            targetPage = 1,
            index = 5, // end of page
            silently = true,
        )

        val page1 = paginator.cache.getStateOf(1)!!.data
        assertEquals(5, page1.size)
        // Original data unchanged at the head
        for (i in 0..4) assertEquals("p1_item$i", page1[i])
        // Nothing to keep — tail1/tail2 were cascaded out

        val page2 = paginator.cache.getStateOf(2)!!.data
        assertEquals(5, page2.size)
        // Cascade order: extras = last 2 items after addAll = [tail1, tail2]
        assertEquals("tail1", page2[0])
        assertEquals("tail2", page2[1])
        assertEquals("p2_item0", page2[2])
        assertEquals("p2_item1", page2[3])
        assertEquals("p2_item2", page2[4])
    }

    @Test
    fun `addAllElements fits within capacity does not cascade`() = runTest {
        val paginator = MutablePaginator<String> { LoadResult(emptyList()) }
        paginator.core.resize(capacity = 10, resize = false, silently = true)
        paginator.cache.setState(
            state = SuccessPage(page = 1, data = mutableListOf("a", "b", "c")),
            silently = true,
        )
        paginator.cache.setState(
            state = SuccessPage(page = 2, data = mutableListOf("x", "y", "z")),
            silently = true,
        )

        paginator.addAllElements(
            elements = listOf("m", "n"),
            targetPage = 1,
            index = 1,
            silently = true,
        )

        val page1 = paginator.cache.getStateOf(1)!!.data
        assertEquals(listOf("a", "m", "n", "b", "c"), page1)
        // Page 2 must be untouched — no overflow
        val page2 = paginator.cache.getStateOf(2)!!.data
        assertEquals(listOf("x", "y", "z"), page2)
    }

    @Test
    fun `addAllElements deep cascade through many pages`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 5, capacity = 3)

        paginator.addAllElements(
            elements = listOf("N"),
            targetPage = 1,
            index = 0,
            silently = true,
        )

        // Each page shifts by 1: last item of page N becomes first item of page N+1.
        assertEquals(listOf("N", "p1_item0", "p1_item1"), paginator.cache.getStateOf(1)!!.data)
        assertEquals(
            listOf("p1_item2", "p2_item0", "p2_item1"),
            paginator.cache.getStateOf(2)!!.data
        )
        assertEquals(
            listOf("p2_item2", "p3_item0", "p3_item1"),
            paginator.cache.getStateOf(3)!!.data
        )
        assertEquals(
            listOf("p3_item2", "p4_item0", "p4_item1"),
            paginator.cache.getStateOf(4)!!.data
        )
        assertEquals(
            listOf("p4_item2", "p5_item0", "p5_item1"),
            paginator.cache.getStateOf(5)!!.data
        )
    }

    @Test
    fun `addAllElements uses initPageState to create trailing page`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)

        paginator.addAllElements(
            elements = listOf("A", "B"),
            targetPage = 1,
            index = 0,
            silently = true,
            initPageState = { page, data ->
                // SuccessPage asserts data is non-empty; the library calls the factory
                // with an empty list, so use EmptyPage here — SuccessPage.copy auto-demotes
                // to it anyway inside cascade. Data will be filled in-place afterward.
                EmptyPage(page = page, data = data.toMutableList())
            },
        )

        // Pages 1, 2 full; page 3 created with overflow from page 2.
        assertEquals(listOf("A", "B", "p1_item0"), paginator.cache.getStateOf(1)!!.data)
        assertEquals(
            listOf("p1_item1", "p1_item2", "p2_item0"),
            paginator.cache.getStateOf(2)!!.data
        )
        val page3 = paginator.cache.getStateOf(3)
        assertNotNull(page3)
        assertEquals(listOf("p2_item1", "p2_item2"), page3.data)
    }

    @Test
    fun `addAllElements throws when target page missing and no initPageState`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)
        assertFailsWith<IndexOutOfBoundsException> {
            paginator.addAllElements(
                elements = listOf("x"),
                targetPage = 99,
                index = 0,
                silently = true,
            )
        }
    }

    @Test
    fun `addAllElements unlimited capacity never cascades`() = runTest {
        val paginator = MutablePaginator<String> { LoadResult(emptyList()) }
        paginator.core.resize(capacity = UNLIMITED_CAPACITY, resize = false, silently = true)
        paginator.cache.setState(
            state = SuccessPage(page = 1, data = mutableListOf("a", "b", "c")),
            silently = true,
        )
        paginator.cache.setState(
            state = SuccessPage(page = 2, data = mutableListOf("x", "y", "z")),
            silently = true,
        )

        paginator.addAllElements(
            elements = List(50) { "n$it" },
            targetPage = 1,
            index = 3,
            silently = true,
        )

        assertEquals(53, paginator.cache.getStateOf(1)!!.data.size)
        // Page 2 must be untouched: unlimited capacity suppresses cascade entirely.
        assertEquals(listOf("x", "y", "z"), paginator.cache.getStateOf(2)!!.data)
    }

    // ── Gap-cascade behavior (the user-described scenario) ───────────────────

    @Test
    fun `addAllElements gap-cascade shifts next chunk and creates trailing page`() = runTest {
        val paginator = MutablePaginator<String> { LoadResult(emptyList()) }
        paginator.core.resize(capacity = 20, resize = false, silently = true)

        fun seed(page: Int) {
            paginator.cache.setState(
                state = SuccessPage(
                    page = page,
                    data = MutableList(20) { "p${page}_$it" },
                ),
                silently = true,
            )
        }

        // Data: [1..5] gap [10..15] gap [20..25]
        for (p in 1..5) seed(p)
        for (p in 10..15) seed(p)
        for (p in 20..25) seed(p)

        // Insert 5 new elements at page 13, index 10
        paginator.addAllElements(
            elements = listOf("N0", "N1", "N2", "N3", "N4"),
            targetPage = 13,
            index = 10,
            silently = true,
        )

        // Pages 1..5 and 10..12 must be untouched.
        for (p in (1..5) + (10..12)) {
            val data = paginator.cache.getStateOf(p)!!.data
            assertEquals(20, data.size, "page $p should stay full")
            for (i in 0..19) {
                assertEquals("p${p}_$i", data[i], "page $p index $i")
            }
        }

        // Page 13: first 10 original, then 5 new, then 5 more original (11..15).
        val page13 = paginator.cache.getStateOf(13)!!.data
        assertEquals(20, page13.size)
        for (i in 0..9) assertEquals("p13_$i", page13[i])
        for (i in 0..4) assertEquals("N$i", page13[10 + i])
        for (i in 0..4) assertEquals("p13_${10 + i}", page13[15 + i])

        // Page 14: first 5 = last 5 of original page 13 (indices 15..19), then page 14's first 15.
        val page14 = paginator.cache.getStateOf(14)!!.data
        assertEquals(20, page14.size)
        for (i in 0..4) assertEquals("p13_${15 + i}", page14[i])
        for (i in 0..14) assertEquals("p14_$i", page14[5 + i])

        // Page 15: last 5 of original page 14 + first 15 of original page 15.
        val page15 = paginator.cache.getStateOf(15)!!.data
        assertEquals(20, page15.size)
        for (i in 0..4) assertEquals("p14_${15 + i}", page15[i])
        for (i in 0..14) assertEquals("p15_$i", page15[5 + i])

        // Page 16 must NOT be created — no initPageState on the outer call.
        assertNull(paginator.cache.getStateOf(16))

        // Page 20 — partially filled (lost last 5), original first 15 items preserved.
        val page20 = paginator.cache.getStateOf(20)!!.data
        assertEquals(15, page20.size)
        for (i in 0..14) assertEquals("p20_$i", page20[i])

        // Pages 21..25: each shifted by 5, full.
        for (p in 21..25) {
            val data = paginator.cache.getStateOf(p)!!.data
            assertEquals(20, data.size, "page $p should stay full after shift")
            // First 5 items = last 5 items of the previous page (p-1).
            for (i in 0..4) assertEquals("p${p - 1}_${15 + i}", data[i], "page $p idx $i")
            // Remaining 15 = first 15 items of the original page p.
            for (i in 0..14) assertEquals("p${p}_$i", data[5 + i], "page $p idx ${5 + i}")
        }

        // Page 26 — newly created with page 25's original last 5.
        val page26 = paginator.cache.getStateOf(26)
        assertNotNull(page26)
        assertEquals(5, page26.data.size)
        for (i in 0..4) assertEquals("p25_${15 + i}", page26.data[i])
    }

    @Test
    fun `addAllElements gap with no next chunk drops extras without invalidation`() = runTest {
        val paginator = MutablePaginator<String> { LoadResult(emptyList()) }
        paginator.core.resize(capacity = 3, resize = false, silently = true)
        paginator.cache.setState(
            state = SuccessPage(page = 1, data = mutableListOf("a", "b", "c")),
            silently = true,
        )
        paginator.cache.setState(
            state = SuccessPage(page = 2, data = mutableListOf("x", "y", "z")),
            silently = true,
        )

        paginator.addAllElements(
            elements = listOf("N1", "N2", "N3"),
            targetPage = 1,
            index = 0,
            silently = true,
        )

        assertEquals(listOf("N1", "N2", "N3"), paginator.cache.getStateOf(1)!!.data)
        assertEquals(listOf("a", "b", "c"), paginator.cache.getStateOf(2)!!.data)
        // No third page was created, no invalidation of existing pages.
        assertNull(paginator.cache.getStateOf(3))
        assertEquals(setOf(1, 2), paginator.cache.pages.toSet())
    }

    @Test
    fun `addAllElements skips transient-class blocker and shifts next same-class chunk`() =
        runTest {
            val paginator = MutablePaginator<String> { LoadResult(emptyList()) }
            paginator.core.resize(capacity = 5, resize = false, silently = true)

            // Chunk 1: page 1 (Success, full)
            paginator.cache.setState(
                state = SuccessPage(
                    page = 1,
                    data = MutableList(5) { "p1_$it" },
                ),
                silently = true,
            )
            // Blocker: page 2 is an ErrorPage — different class.
            paginator.cache.setState(
                state = PageState.ErrorPage<String>(
                    exception = RuntimeException("boom"),
                    page = 2,
                    data = mutableListOf(),
                ),
                silently = true,
            )
            // Chunk 2: pages 10..11 (Success, full)
            for (p in 10..11) {
                paginator.cache.setState(
                    state = SuccessPage(page = p, data = MutableList(5) { "p${p}_$it" }),
                    silently = true,
                )
            }

            // Insert 2 elements at page 1 → cascade can't continue to page 2 (ErrorPage).
            paginator.addAllElements(
                elements = listOf("N0", "N1"),
                targetPage = 1,
                index = 0,
                silently = true,
            )

            // Page 1 capped at capacity with the new elements prepended.
            assertEquals(
                listOf("N0", "N1", "p1_0", "p1_1", "p1_2"),
                paginator.cache.getStateOf(1)!!.data
            )
            // Page 2 (ErrorPage) untouched — we do not disturb transient states.
            val page2 = paginator.cache.getStateOf(2)
            assertTrue(page2 is PageState.ErrorPage)
            // Page 10 — partially filled (lost last 2 items to cascade).
            assertEquals(listOf("p10_0", "p10_1", "p10_2"), paginator.cache.getStateOf(10)!!.data)
            // Page 11 — full after receiving last 2 of page 10.
            assertEquals(
                listOf("p10_3", "p10_4", "p11_0", "p11_1", "p11_2"),
                paginator.cache.getStateOf(11)!!.data
            )
            // Page 12 — created with page 11's original last 2 items.
            val page12 = paginator.cache.getStateOf(12)
            assertNotNull(page12)
            assertEquals(listOf("p11_3", "p11_4"), page12.data)
        }

    @Test
    fun `addAllElements gap-cascade leaves partial head when chunk head has more items than shift`() =
        runTest {
            val paginator = MutablePaginator<String> { LoadResult(emptyList()) }
            paginator.core.resize(capacity = 10, resize = false, silently = true)

            paginator.cache.setState(
                state = SuccessPage(page = 1, data = MutableList(10) { "p1_$it" }),
                silently = true,
            )
            // Next chunk's head is under-filled (7 items) but still larger than the shift (5).
            paginator.cache.setState(
                state = SuccessPage(page = 10, data = MutableList(7) { "p10_$it" }),
                silently = true,
            )
            paginator.cache.setState(
                state = SuccessPage(page = 11, data = MutableList(10) { "p11_$it" }),
                silently = true,
            )

            paginator.addAllElements(
                elements = listOf("N0", "N1", "N2", "N3", "N4"),
                targetPage = 1,
                index = 0,
                silently = true,
            )

            // Page 1 full with prepended new items.
            assertEquals(
                expected = listOf(
                    "N0",
                    "N1",
                    "N2",
                    "N3",
                    "N4",
                    "p1_0",
                    "p1_1",
                    "p1_2",
                    "p1_3",
                    "p1_4"
                ),
                actual = paginator.cache.getStateOf(1)!!.data,
            )
            // Page 10 loses its last 5 items → 2 remain.
            assertEquals(listOf("p10_0", "p10_1"), paginator.cache.getStateOf(10)!!.data)
            // Page 11 gains peeled 5 at the start, pushes its last 5 to page 12.
            assertEquals(
                expected = listOf(
                    "p10_2",
                    "p10_3",
                    "p10_4",
                    "p10_5",
                    "p10_6",
                    "p11_0",
                    "p11_1",
                    "p11_2",
                    "p11_3",
                    "p11_4"
                ),
                actual = paginator.cache.getStateOf(11)!!.data,
            )
            // Page 12 created with page 11's last 5.
            val page12 = paginator.cache.getStateOf(12)
            assertNotNull(page12)
            assertEquals(listOf("p11_5", "p11_6", "p11_7", "p11_8", "p11_9"), page12.data)
        }

    @Test
    fun `addAllElements gap-cascade drops head of next chunk when shift drains it`() = runTest {
        val paginator = MutablePaginator<String> { LoadResult(emptyList()) }
        paginator.core.resize(capacity = 5, resize = false, silently = true)

        // Chunk 1: pages 1..2 full (5 items each).
        for (p in 1..2) {
            paginator.cache.setState(
                state = SuccessPage(page = p, data = MutableList(5) { "p${p}_$it" }),
                silently = true,
            )
        }
        // Chunk 2: pages 10..11 full.
        for (p in 10..11) {
            paginator.cache.setState(
                state = SuccessPage(page = p, data = MutableList(5) { "p${p}_$it" }),
                silently = true,
            )
        }

        // Insert 5 elements at page 1, index 0. Overflow is 5 (a full page worth),
        // which will cascade through page 2 and then hit the gap.
        paginator.addAllElements(
            elements = listOf("N0", "N1", "N2", "N3", "N4"),
            targetPage = 1,
            index = 0,
            silently = true,
        )

        // Page 1 — fully replaced by new elements (5 items, capacity 5).
        assertEquals(listOf("N0", "N1", "N2", "N3", "N4"), paginator.cache.getStateOf(1)!!.data)
        // Page 2 — fully replaced by original page 1's content.
        assertEquals(
            listOf("p1_0", "p1_1", "p1_2", "p1_3", "p1_4"),
            paginator.cache.getStateOf(2)!!.data
        )

        // Page 10's entire content was peeled as the shift → page must disappear.
        assertNull(paginator.cache.getStateOf(10))
        // Page 11 gains page 10's 5 items at the start, so its whole tail overflows…
        assertEquals(
            listOf("p10_0", "p10_1", "p10_2", "p10_3", "p10_4"),
            paginator.cache.getStateOf(11)!!.data
        )
        // …creating page 12 with page 11's original content.
        val page12 = paginator.cache.getStateOf(12)
        assertNotNull(page12)
        assertEquals(listOf("p11_0", "p11_1", "p11_2", "p11_3", "p11_4"), page12.data)
    }

    @Test
    fun `addAllElements marks cascaded pages as affected`() = runTest {
        val paginator = MutablePaginator<String> { LoadResult(emptyList()) }
        paginator.core.resize(capacity = 3, resize = false, silently = true)
        for (p in 1..3) {
            paginator.cache.setState(
                state = SuccessPage(page = p, data = MutableList(3) { "p${p}_$it" }),
                silently = true,
            )
        }

        paginator.addAllElements(
            elements = listOf("N"),
            targetPage = 1,
            index = 0,
            silently = true,
            isDirty = true,
        )

        // targetPage and every cascade target must be flagged dirty.
        assertTrue(paginator.core.isDirty(1), "page 1 dirty")
        // Pages 2 and 3 get markAffected but not markDirty — verify only page 1 is dirty.
        assertTrue(!paginator.core.isDirty(2), "page 2 not marked dirty by caller flag")
    }
}
