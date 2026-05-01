package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.extension.isEmptyState
import com.jamal_aliev.paginator.page.PageState.ErrorPage
import com.jamal_aliev.paginator.page.PageState.ProgressPage
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import com.jamal_aliev.paginator.page.PlaceholderPageState
import com.jamal_aliev.paginator.page.PlaceholderPageState.PlaceholderErrorPage
import com.jamal_aliev.paginator.page.PlaceholderPageState.PlaceholderProgressPage
import com.jamal_aliev.paginator.page.PlaceholderPageState.PlaceholderSuccessPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlaceholderPageStateTest {

    // ──────────────────────────────────────────────────────────────────────
    //  Shared test data
    // ──────────────────────────────────────────────────────────────────────

    private val items = listOf("a", "b", "c")
    private val skeletons = listOf(Unit, Unit, Unit)
    private val exception = RuntimeException("oops")

    // ══════════════════════════════════════════════════════════════════════
    //  PlaceholderProgressPage
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `PlaceholderProgressPage - stores page and data and placeholders`() {
        val state = PlaceholderProgressPage(
            page = 2,
            data = items,
            placeholders = skeletons,
        )
        assertEquals(2, state.page)
        assertEquals(items, state.data)
        assertEquals(skeletons, state.placeholders)
    }

    @Test
    fun `PlaceholderProgressPage - is ProgressPage`() {
        val state = PlaceholderProgressPage(page = 1, data = items, placeholders = skeletons)
        assertIs<ProgressPage<*>>(state)
    }

    @Test
    fun `PlaceholderProgressPage - is PlaceholderPageState`() {
        val state = PlaceholderProgressPage(page = 1, data = items, placeholders = skeletons)
        assertIs<PlaceholderPageState<*>>(state)
    }

    @Test
    fun `PlaceholderProgressPage - empty data is allowed`() {
        val state = PlaceholderProgressPage(
            page = 1,
            data = emptyList<String>(),
            placeholders = skeletons,
        )
        assertEquals(0, state.data.size)
        assertEquals(3, state.placeholders.size)
    }

    @Test
    fun `PlaceholderProgressPage - empty placeholders is allowed`() {
        val state = PlaceholderProgressPage<String, Unit>(
            page = 1,
            data = items,
            placeholders = emptyList(),
        )
        assertEquals(0, state.placeholders.size)
    }

    @Test
    fun `PlaceholderProgressPage - data and placeholders are independent`() {
        val state = PlaceholderProgressPage(page = 1, data = items, placeholders = skeletons)
        assertEquals(3, state.data.size)
        assertEquals(3, state.placeholders.size)
        // data does NOT contain placeholders
        assertEquals(items, state.data)
    }

    @Test
    fun `PlaceholderProgressPage - placeholders reference is preserved`() {
        val state = PlaceholderProgressPage(page = 1, data = items, placeholders = skeletons)
        assertSame(skeletons, state.placeholders)
    }

    @Test
    fun `PlaceholderProgressPage - custom placeholder type`() {
        data class Skeleton(val width: Int)
        val ph = listOf(Skeleton(100), Skeleton(200))
        val state = PlaceholderProgressPage(page = 1, data = items, placeholders = ph)
        assertEquals(ph, state.placeholders)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PlaceholderSuccessPage
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `PlaceholderSuccessPage - stores page and data and placeholders`() {
        val state = PlaceholderSuccessPage(
            page = 3,
            data = items,
            placeholders = skeletons,
        )
        assertEquals(3, state.page)
        assertEquals(items, state.data)
        assertEquals(skeletons, state.placeholders)
    }

    @Test
    fun `PlaceholderSuccessPage - is SuccessPage`() {
        val state = PlaceholderSuccessPage(page = 1, data = items, placeholders = skeletons)
        assertIs<SuccessPage<*>>(state)
    }

    @Test
    fun `PlaceholderSuccessPage - is PlaceholderPageState`() {
        val state = PlaceholderSuccessPage(page = 1, data = items, placeholders = skeletons)
        assertIs<PlaceholderPageState<*>>(state)
    }

    @Test
    fun `PlaceholderSuccessPage - allows empty data`() {
        val state = PlaceholderSuccessPage(
            page = 1,
            data = emptyList<String>(),
            placeholders = skeletons,
        )
        assertEquals(0, state.data.size)
        assertTrue(state.isEmptyState())
    }

    @Test
    fun `PlaceholderSuccessPage - empty placeholders is allowed`() {
        val state = PlaceholderSuccessPage<String, Unit>(page = 1, data = items, placeholders = emptyList())
        assertEquals(0, state.placeholders.size)
    }

    @Test
    fun `PlaceholderSuccessPage - data and placeholders are independent`() {
        val state = PlaceholderSuccessPage(page = 1, data = items, placeholders = skeletons)
        assertEquals(items, state.data)
        assertEquals(skeletons, state.placeholders)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PlaceholderErrorPage
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `PlaceholderErrorPage - stores exception and page and data and placeholders`() {
        val state = PlaceholderErrorPage(
            exception = exception,
            page = 5,
            data = items,
            placeholders = skeletons,
        )
        assertSame(exception, state.exception)
        assertEquals(5, state.page)
        assertEquals(items, state.data)
        assertEquals(skeletons, state.placeholders)
    }

    @Test
    fun `PlaceholderErrorPage - is ErrorPage`() {
        val state = PlaceholderErrorPage(exception, page = 1, data = items, placeholders = skeletons)
        assertIs<ErrorPage<*>>(state)
    }

    @Test
    fun `PlaceholderErrorPage - is PlaceholderPageState`() {
        val state = PlaceholderErrorPage(exception, page = 1, data = items, placeholders = skeletons)
        assertIs<PlaceholderPageState<*>>(state)
    }

    @Test
    fun `PlaceholderErrorPage - empty data is allowed`() {
        val state = PlaceholderErrorPage(
            exception = exception,
            page = 1,
            data = emptyList<String>(),
            placeholders = skeletons,
        )
        assertEquals(0, state.data.size)
    }

    @Test
    fun `PlaceholderErrorPage - empty placeholders is allowed`() {
        val state = PlaceholderErrorPage<String, Unit>(exception, page = 1, data = items, placeholders = emptyList())
        assertEquals(0, state.placeholders.size)
    }

    @Test
    fun `PlaceholderErrorPage - exception type is preserved`() {
        val ioEx = IllegalStateException("state error")
        val state = PlaceholderErrorPage(ioEx, page = 1, data = items, placeholders = skeletons)
        assertIs<IllegalStateException>(state.exception)
        assertEquals("state error", state.exception.message)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PlaceholderPageState interface — common contract
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `all variants implement PlaceholderPageState`() {
        val progress = PlaceholderProgressPage(page = 1, data = items, placeholders = skeletons)
        val success  = PlaceholderSuccessPage(page = 1, data = items, placeholders = skeletons)
        val empty =
            PlaceholderSuccessPage(page = 1, data = emptyList<String>(), placeholders = skeletons)
        val error    = PlaceholderErrorPage(exception, page = 1, data = items, placeholders = skeletons)

        assertIs<PlaceholderPageState<*>>(progress)
        assertIs<PlaceholderPageState<*>>(success)
        assertIs<PlaceholderPageState<*>>(empty)
        assertIs<PlaceholderPageState<*>>(error)
    }

    @Test
    fun `placeholders list is the same reference via interface`() {
        val state: PlaceholderPageState<Unit> =
            PlaceholderProgressPage(page = 1, data = items, placeholders = skeletons)
        assertSame(skeletons, state.placeholders)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Page number
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `page number is preserved across all variants`() {
        assertEquals(7, PlaceholderProgressPage(page = 7, data = items, placeholders = skeletons).page)
        assertEquals(7, PlaceholderSuccessPage(page = 7, data = items, placeholders = skeletons).page)
        assertEquals(
            7,
            PlaceholderSuccessPage(
                page = 7,
                data = emptyList<String>(),
                placeholders = skeletons
            ).page
        )
        assertEquals(7, PlaceholderErrorPage(exception, page = 7, data = items, placeholders = skeletons).page)
    }
}
