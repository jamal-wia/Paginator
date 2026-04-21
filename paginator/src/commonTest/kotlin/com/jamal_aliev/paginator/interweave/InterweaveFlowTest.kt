package com.jamal_aliev.paginator.interweave

import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PaginatorUiState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

private data class Row(val id: Int, val bucket: String)

private fun rowWeaver(): Interweaver<Row, String> = interweaver {
    itemKey { it.id }
    decorationKey { label, _, next -> "label-${next?.bucket}-$label" }
    between { prev, next ->
        val p = prev?.bucket
        val n = next?.bucket
        if (p != n && n != null) n else null
    }
}

class InterweaveFlowTest {

    @Test
    fun `Idle passes through unchanged`() {
        val woven: PaginatorUiState<WovenEntry<Row, String>> =
            PaginatorUiState.Idle.interweave(rowWeaver())
        assertSame(PaginatorUiState.Idle, woven)
    }

    @Test
    fun `Loading passes through structurally`() {
        val state: PaginatorUiState<Row> = PaginatorUiState.Loading(page = 1)
        val woven = state.interweave(rowWeaver())
        assertEquals(PaginatorUiState.Loading(page = 1), woven)
    }

    @Test
    fun `Empty passes through structurally`() {
        val state: PaginatorUiState<Row> = PaginatorUiState.Empty(page = 2)
        val woven = state.interweave(rowWeaver())
        assertEquals(PaginatorUiState.Empty(page = 2), woven)
    }

    @Test
    fun `Error passes through structurally`() {
        val boom = RuntimeException("boom")
        val state: PaginatorUiState<Row> = PaginatorUiState.Error(page = 3, exception = boom)
        val woven = state.interweave(rowWeaver())
        assertEquals(PaginatorUiState.Error(page = 3, exception = boom), woven)
    }

    @Test
    fun `Content without boundary states weaves items`() {
        val state: PaginatorUiState<Row> = PaginatorUiState.Content(
            prependState = null,
            items = listOf(Row(1, "A"), Row(2, "B")),
            appendState = null,
        )
        val woven = state.interweave(rowWeaver())
        val content = assertIs<PaginatorUiState.Content<WovenEntry<Row, String>>>(woven)
        assertNull(content.prependState)
        assertNull(content.appendState)
        // Data(1), Decoration(B), Data(2)
        assertEquals(3, content.items.size)
        assertIs<WovenEntry.Data<Row>>(content.items[0])
        assertIs<WovenEntry.Decoration<String>>(content.items[1])
        assertIs<WovenEntry.Data<Row>>(content.items[2])
    }

    @Test
    fun `Content wraps prependState data into WovenEntry Data`() {
        val prepend = PageState.ProgressPage(page = 1, data = listOf(Row(10, "A"), Row(11, "A")))
        val state: PaginatorUiState<Row> = PaginatorUiState.Content(
            prependState = prepend,
            items = listOf(Row(10, "A"), Row(11, "A"), Row(12, "B")),
            appendState = null,
        )
        val woven = state.interweave(rowWeaver())
        val content = assertIs<PaginatorUiState.Content<WovenEntry<Row, String>>>(woven)
        val wrappedPrepend = content.prependState
        assertIs<PageState.ProgressPage<WovenEntry<Row, String>>>(wrappedPrepend)
        assertEquals(2, wrappedPrepend.data.size)
        wrappedPrepend.data.forEach { assertIs<WovenEntry.Data<Row>>(it) }
        // preserved identity fields:
        assertEquals(prepend.page, wrappedPrepend.page)
        assertEquals(prepend.id, wrappedPrepend.id)
    }

    @Test
    fun `Content wraps appendState data and preserves ErrorPage exception`() {
        val boom = IllegalStateException("x")
        val append = PageState.ErrorPage<Row>(
            exception = boom,
            page = 4,
            data = listOf(Row(40, "Z")),
        )
        val state: PaginatorUiState<Row> = PaginatorUiState.Content(
            prependState = null,
            items = listOf(Row(1, "A"), Row(40, "Z")),
            appendState = append,
        )
        val woven = state.interweave(rowWeaver())
        val content = assertIs<PaginatorUiState.Content<WovenEntry<Row, String>>>(woven)
        val wrappedAppend = content.appendState
        assertIs<PageState.ErrorPage<WovenEntry<Row, String>>>(wrappedAppend)
        assertSame(boom, wrappedAppend.exception)
        assertEquals(append.page, wrappedAppend.page)
        assertEquals(append.id, wrappedAppend.id)
        assertEquals(1, wrappedAppend.data.size)
        assertIs<WovenEntry.Data<Row>>(wrappedAppend.data.single())
    }

    @Test
    fun `flow operator maps every emission`() = runTest {
        val source = flowOf<PaginatorUiState<Row>>(
            PaginatorUiState.Idle,
            PaginatorUiState.Loading(page = 1),
            PaginatorUiState.Content(
                prependState = null,
                items = listOf(Row(1, "A"), Row(2, "B")),
                appendState = null,
            ),
        )
        val emissions = source.interweave(rowWeaver()).toList()
        assertEquals(3, emissions.size)
        assertSame(PaginatorUiState.Idle, emissions[0])
        assertEquals(PaginatorUiState.Loading(page = 1), emissions[1])
        val content = assertIs<PaginatorUiState.Content<WovenEntry<Row, String>>>(emissions[2])
        assertEquals(3, content.items.size)
    }
}
