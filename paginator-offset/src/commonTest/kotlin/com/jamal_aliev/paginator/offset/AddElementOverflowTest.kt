package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.offset.extension.addElement
import com.jamal_aliev.paginator.offset.extension.insertAfter
import com.jamal_aliev.paginator.offset.extension.insertBefore
import com.jamal_aliev.paginator.offset.extension.moveElement
import com.jamal_aliev.paginator.offset.extension.prependElement
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression coverage for the "addElement overflow at the end is silently dropped" bug.
 *
 * When CRUD overflows past the last cached page and no `initPageState` is supplied, a new page is
 * now created by default (same class as the source, via the configured `core.initializer*Page`
 * factories) instead of dropping the element.
 */
class AddElementOverflowTest {

    private class MySuccess<T>(page: Int, data: List<T>) : OffsetPageState.Success<T>(page, data)

    @Test
    fun `addElement on a full last page creates a new page so the item is not dropped`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3) // page1 = [p1_item0..2], full

        val added = paginator.addElement("new", silently = true)

        assertTrue(added)
        assertEquals(listOf("p1_item0", "p1_item1", "p1_item2"), paginator.cache.getStateOf(1)!!.data)
        val page2 = paginator.cache.getStateOf(2)
        assertNotNull(page2)
        assertTrue(page2 is OffsetPageState.Success)
        assertEquals(listOf("new"), page2.data)
    }

    @Test
    fun `the overflow item is visible in the snapshot`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)

        paginator.addElement("new", silently = false)

        val visible = paginator.core.snapshot.first().flatMap { it.data }
        assertTrue("new" in visible, "the added item must be visible, was $visible")
    }

    @Test
    fun `overflow page uses the configured success initializer (custom subclass preserved)`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        paginator.core.initializerSuccessPage = { page, data, _ -> MySuccess(page, data) }

        paginator.addElement("new", silently = true)

        val page2 = paginator.cache.getStateOf(2)
        assertTrue(
            page2 is MySuccess,
            "overflow page should be the custom subclass, was ${page2?.let { it::class.simpleName }}",
        )
    }

    @Test
    fun `overflow page gets a fresh id, not shared with the source page`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        val sourceId = paginator.cache.getStateOf(1)!!.id

        paginator.addElement("new", silently = true)

        assertTrue(paginator.cache.getStateOf(2)!!.id != sourceId, "overflow page must have a fresh id")
    }

    @Test
    fun `overflow from an Error page creates an Error overflow page (CRUD on Error is legal)`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        val boom = RuntimeException("boom")
        // Turn page 1 into a full Error page carrying stale data.
        paginator.cache.setState(
            OffsetPageState.Error(exception = boom, page = 1, data = mutableListOf("a", "b", "c")),
            silently = true,
        )

        paginator.addElement("new", page = 1, index = 3, silently = true)

        val page2 = paginator.cache.getStateOf(2)
        assertNotNull(page2)
        assertTrue(page2 is OffsetPageState.Error)
        assertEquals(boom, page2.exception)
        assertEquals(listOf("new"), page2.data)
    }

    @Test
    fun `overflow from a Progress page creates a Progress overflow page (CRUD on Progress is legal)`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)
        paginator.cache.setState(
            OffsetPageState.Progress(page = 1, data = mutableListOf("a", "b", "c")),
            silently = true,
        )

        paginator.addElement("new", page = 1, index = 3, silently = true)

        val page2 = paginator.cache.getStateOf(2)
        assertNotNull(page2)
        assertTrue(page2 is OffsetPageState.Progress)
        assertEquals(listOf("new"), page2.data)
    }

    @Test
    fun `passing null initPageState opts out — overflow is dropped, no page created`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3) // page1 full

        // Explicit null disables the overflow algorithm: the element has nowhere to go and is dropped.
        paginator.addElement("new", silently = true, initSuccessPageState = null)

        assertEquals(listOf("p1_item0", "p1_item1", "p1_item2"), paginator.cache.getStateOf(1)!!.data)
        assertNull(paginator.cache.getStateOf(2))
    }

    // =========================================================================
    // The same default-factory-or-null paradigm on the rest of the insert family:
    // prependElement, moveElement, insertBefore, insertAfter.
    // =========================================================================

    @Test
    fun `prependElement overflow creates a trailing page by default (not dropped)`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3) // both pages full

        paginator.prependElement("new", silently = true)

        // new pushes the cache forward; the last item cascades into a created page 3.
        assertEquals(listOf("new", "p1_item0", "p1_item1"), paginator.cache.getStateOf(1)!!.data)
        val page3 = paginator.cache.getStateOf(3)
        assertNotNull(page3)
        assertEquals(listOf("p2_item2"), page3.data)
    }

    @Test
    fun `prependElement overflow is dropped when initSuccessPageState is null (opt-out)`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)

        paginator.prependElement("new", silently = true, initSuccessPageState = null)

        assertEquals(listOf("new", "p1_item0", "p1_item1"), paginator.cache.getStateOf(1)!!.data)
        assertEquals(listOf("p1_item2", "p2_item0", "p2_item1"), paginator.cache.getStateOf(2)!!.data)
        assertNull(paginator.cache.getStateOf(3)) // p2_item2 was dropped
    }

    @Test
    fun `prependElement overflow uses a custom factory (subclass preserved)`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)

        paginator.prependElement(
            "new",
            silently = true,
            initSuccessPageState = { page, data -> MySuccess(page, data) },
        )

        assertTrue(paginator.cache.getStateOf(3) is MySuccess)
    }

    @Test
    fun `moveElement overflow is dropped when initPageState is null (opt-out)`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)

        // Move p1_item0 to the head of the (full) page 2 → the tail overflows past the end.
        paginator.moveElement(
            fromPage = 1, fromIndex = 0,
            toPage = 2, toIndex = 0,
            silently = true,
            initPageState = null,
        )

        assertEquals(listOf("p1_item1", "p1_item2"), paginator.cache.getStateOf(1)!!.data)
        assertEquals(listOf("p1_item0", "p2_item0", "p2_item1"), paginator.cache.getStateOf(2)!!.data)
        assertNull(paginator.cache.getStateOf(3)) // p2_item2 dropped
    }

    @Test
    fun `insertAfter overflow is dropped when initPageState is null (opt-out)`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)

        val inserted = paginator.insertAfter("X", silently = true, initPageState = null) {
            it == "p2_item2"
        }

        assertTrue(inserted)
        assertEquals(listOf("p2_item0", "p2_item1", "p2_item2"), paginator.cache.getStateOf(2)!!.data)
        assertNull(paginator.cache.getStateOf(3)) // X had nowhere to go and was dropped
    }

    @Test
    fun `insertBefore overflow is dropped when initPageState is null (opt-out)`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)

        val inserted = paginator.insertBefore("X", silently = true, initPageState = null) {
            it == "p2_item2"
        }

        assertTrue(inserted)
        // X lands before p2_item2; the page tail (p2_item2) overflows and is dropped.
        assertEquals(listOf("p2_item0", "p2_item1", "X"), paginator.cache.getStateOf(2)!!.data)
        assertNull(paginator.cache.getStateOf(3))
    }

    @Test
    fun `insertAfter overflow creates a trailing page by default`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 2, capacity = 3)

        paginator.insertAfter("X", silently = true) { it == "p2_item2" }

        val page3 = paginator.cache.getStateOf(3)
        assertNotNull(page3)
        assertEquals(listOf("X"), page3.data)
    }
}
