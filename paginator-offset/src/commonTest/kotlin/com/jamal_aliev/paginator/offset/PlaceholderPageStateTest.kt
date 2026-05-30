package com.jamal_aliev.paginator.offset


import com.jamal_aliev.paginator.core.extension.isEmptyState
import com.jamal_aliev.paginator.core.page.PlaceholderPageState
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import com.jamal_aliev.paginator.offset.page.OffsetPlaceholderErrorPage
import com.jamal_aliev.paginator.offset.page.OffsetPlaceholderProgressPage
import com.jamal_aliev.paginator.offset.page.OffsetPlaceholderSuccessPage
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
    //  OffsetPlaceholderProgressPage
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `OffsetPlaceholderProgressPage - stores page and data and placeholders`() {
        val state = OffsetPlaceholderProgressPage(
            page = 2,
            data = items,
            placeholders = skeletons,
        )
        assertEquals(2, state.page)
        assertEquals(items, state.data)
        assertEquals(skeletons, state.placeholders)
    }

    @Test
    fun `OffsetPlaceholderProgressPage - is Progress`() {
        val state = OffsetPlaceholderProgressPage(page = 1, data = items, placeholders = skeletons)
        assertIs<OffsetPageState.Progress<*>>(state)
    }

    @Test
    fun `OffsetPlaceholderProgressPage - is PlaceholderPageState`() {
        val state = OffsetPlaceholderProgressPage(page = 1, data = items, placeholders = skeletons)
        assertIs<PlaceholderPageState<*>>(state)
    }

    @Test
    fun `OffsetPlaceholderProgressPage - empty data is allowed`() {
        val state = OffsetPlaceholderProgressPage(
            page = 1,
            data = emptyList<String>(),
            placeholders = skeletons,
        )
        assertEquals(0, state.data.size)
        assertEquals(3, state.placeholders.size)
    }

    @Test
    fun `OffsetPlaceholderProgressPage - empty placeholders is allowed`() {
        val state = OffsetPlaceholderProgressPage<String, Unit>(
            page = 1,
            data = items,
            placeholders = emptyList(),
        )
        assertEquals(0, state.placeholders.size)
    }

    @Test
    fun `OffsetPlaceholderProgressPage - data and placeholders are independent`() {
        val state = OffsetPlaceholderProgressPage(page = 1, data = items, placeholders = skeletons)
        assertEquals(3, state.data.size)
        assertEquals(3, state.placeholders.size)
        // data does NOT contain placeholders
        assertEquals(items, state.data)
    }

    @Test
    fun `OffsetPlaceholderProgressPage - placeholders reference is preserved`() {
        val state = OffsetPlaceholderProgressPage(page = 1, data = items, placeholders = skeletons)
        assertSame(skeletons, state.placeholders)
    }

    @Test
    fun `OffsetPlaceholderProgressPage - custom placeholder type`() {
        data class Skeleton(val width: Int)
        val ph = listOf(Skeleton(100), Skeleton(200))
        val state = OffsetPlaceholderProgressPage(page = 1, data = items, placeholders = ph)
        assertEquals(ph, state.placeholders)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  OffsetPlaceholderSuccessPage
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `OffsetPlaceholderSuccessPage - stores page and data and placeholders`() {
        val state = OffsetPlaceholderSuccessPage(
            page = 3,
            data = items,
            placeholders = skeletons,
        )
        assertEquals(3, state.page)
        assertEquals(items, state.data)
        assertEquals(skeletons, state.placeholders)
    }

    @Test
    fun `OffsetPlaceholderSuccessPage - is Success`() {
        val state = OffsetPlaceholderSuccessPage(page = 1, data = items, placeholders = skeletons)
        assertIs<OffsetPageState.Success<*>>(state)
    }

    @Test
    fun `OffsetPlaceholderSuccessPage - is PlaceholderPageState`() {
        val state = OffsetPlaceholderSuccessPage(page = 1, data = items, placeholders = skeletons)
        assertIs<PlaceholderPageState<*>>(state)
    }

    @Test
    fun `OffsetPlaceholderSuccessPage - allows empty data`() {
        val state = OffsetPlaceholderSuccessPage(
            page = 1,
            data = emptyList<String>(),
            placeholders = skeletons,
        )
        assertEquals(0, state.data.size)
        assertTrue(state.isEmptyState())
    }

    @Test
    fun `OffsetPlaceholderSuccessPage - empty placeholders is allowed`() {
        val state = OffsetPlaceholderSuccessPage<String, Unit>(
            page = 1,
            data = items,
            placeholders = emptyList()
        )
        assertEquals(0, state.placeholders.size)
    }

    @Test
    fun `OffsetPlaceholderSuccessPage - data and placeholders are independent`() {
        val state = OffsetPlaceholderSuccessPage(page = 1, data = items, placeholders = skeletons)
        assertEquals(items, state.data)
        assertEquals(skeletons, state.placeholders)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  OffsetPlaceholderErrorPage
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `OffsetPlaceholderErrorPage - stores exception and page and data and placeholders`() {
        val state = OffsetPlaceholderErrorPage(
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
    fun `OffsetPlaceholderErrorPage - is Error`() {
        val state =
            OffsetPlaceholderErrorPage(exception, page = 1, data = items, placeholders = skeletons)
        assertIs<OffsetPageState.Error<*>>(state)
    }

    @Test
    fun `OffsetPlaceholderErrorPage - is PlaceholderPageState`() {
        val state =
            OffsetPlaceholderErrorPage(exception, page = 1, data = items, placeholders = skeletons)
        assertIs<PlaceholderPageState<*>>(state)
    }

    @Test
    fun `OffsetPlaceholderErrorPage - empty data is allowed`() {
        val state = OffsetPlaceholderErrorPage(
            exception = exception,
            page = 1,
            data = emptyList<String>(),
            placeholders = skeletons,
        )
        assertEquals(0, state.data.size)
    }

    @Test
    fun `OffsetPlaceholderErrorPage - empty placeholders is allowed`() {
        val state = OffsetPlaceholderErrorPage<String, Unit>(
            exception,
            page = 1,
            data = items,
            placeholders = emptyList()
        )
        assertEquals(0, state.placeholders.size)
    }

    @Test
    fun `OffsetPlaceholderErrorPage - exception type is preserved`() {
        val ioEx = IllegalStateException("state error")
        val state =
            OffsetPlaceholderErrorPage(ioEx, page = 1, data = items, placeholders = skeletons)
        assertIs<IllegalStateException>(state.exception)
        assertEquals("state error", state.exception.message)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PlaceholderPageState interface — common contract
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `all variants implement PlaceholderPageState`() {
        val progress =
            OffsetPlaceholderProgressPage(page = 1, data = items, placeholders = skeletons)
        val success = OffsetPlaceholderSuccessPage(page = 1, data = items, placeholders = skeletons)
        val empty =
            OffsetPlaceholderSuccessPage(
                page = 1,
                data = emptyList<String>(),
                placeholders = skeletons
            )
        val error =
            OffsetPlaceholderErrorPage(exception, page = 1, data = items, placeholders = skeletons)

        assertIs<PlaceholderPageState<*>>(progress)
        assertIs<PlaceholderPageState<*>>(success)
        assertIs<PlaceholderPageState<*>>(empty)
        assertIs<PlaceholderPageState<*>>(error)
    }

    @Test
    fun `placeholders list is the same reference via interface`() {
        val state: PlaceholderPageState<Unit> =
            OffsetPlaceholderProgressPage(page = 1, data = items, placeholders = skeletons)
        assertSame(skeletons, state.placeholders)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Page number
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `page number is preserved across all variants`() {
        assertEquals(
            7,
            OffsetPlaceholderProgressPage(page = 7, data = items, placeholders = skeletons).page
        )
        assertEquals(
            7,
            OffsetPlaceholderSuccessPage(page = 7, data = items, placeholders = skeletons).page
        )
        assertEquals(
            7,
            OffsetPlaceholderSuccessPage(
                page = 7,
                data = emptyList<String>(),
                placeholders = skeletons
            ).page
        )
        assertEquals(
            7,
            OffsetPlaceholderErrorPage(
                exception,
                page = 7,
                data = items,
                placeholders = skeletons
            ).page
        )
    }
}
