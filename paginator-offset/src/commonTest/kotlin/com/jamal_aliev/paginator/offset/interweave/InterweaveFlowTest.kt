package com.jamal_aliev.paginator.offset.interweave


import com.jamal_aliev.paginator.core.interweave.Interweaver
import com.jamal_aliev.paginator.core.interweave.WovenEntry
import com.jamal_aliev.paginator.core.interweave.interweave
import com.jamal_aliev.paginator.core.interweave.interweaver
import com.jamal_aliev.paginator.core.page.PaginatorUiState
import com.jamal_aliev.paginator.offset.page.OffsetPageState
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
        val state: PaginatorUiState<Row> =
            PaginatorUiState.Loading(OffsetPageState.Progress(page = 1, data = emptyList()))
        val woven = state.interweave(rowWeaver())
        assertIs<PaginatorUiState.Loading<WovenEntry<Row, String>>>(woven)
        assertEquals(1, (woven.state as OffsetPageState<*>).page)
    }

    @Test
    fun `Empty passes through structurally`() {
        val state: PaginatorUiState<Row> =
            PaginatorUiState.Empty(OffsetPageState.Success(page = 2, data = emptyList()))
        val woven = state.interweave(rowWeaver())
        assertIs<PaginatorUiState.Empty<WovenEntry<Row, String>>>(woven)
        assertEquals(2, (woven.state as OffsetPageState<*>).page)
    }

    @Test
    fun `Error passes through structurally`() {
        val boom = RuntimeException("boom")
        val state: PaginatorUiState<Row> =
            PaginatorUiState.Error(
                OffsetPageState.Error(
                    exception = boom,
                    page = 3,
                    data = emptyList()
                )
            )
        val woven = state.interweave(rowWeaver())
        assertIs<PaginatorUiState.Error<WovenEntry<Row, String>>>(woven)
        assertEquals(3, (woven.state as OffsetPageState<*>).page)
        assertSame(boom, woven.state.exception)
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
        val prepend = OffsetPageState.Progress(page = 1, data = listOf(Row(10, "A"), Row(11, "A")))
        val state: PaginatorUiState<Row> = PaginatorUiState.Content(
            prependState = prepend,
            items = listOf(Row(10, "A"), Row(11, "A"), Row(12, "B")),
            appendState = null,
        )
        val woven = state.interweave(rowWeaver())
        val content = assertIs<PaginatorUiState.Content<WovenEntry<Row, String>>>(woven)
        val wrappedPrepend = content.prependState
        assertIs<OffsetPageState.Progress<WovenEntry<Row, String>>>(wrappedPrepend)
        assertEquals(2, wrappedPrepend.data.size)
        wrappedPrepend.data.forEach { assertIs<WovenEntry.Data<Row>>(it) }
        // preserved identity fields:
        assertEquals(prepend.page, wrappedPrepend.page)
        assertEquals(prepend.id, wrappedPrepend.id)
    }

    @Test
    fun `Content wraps appendState data and preserves Error exception`() {
        val boom = IllegalStateException("x")
        val append = OffsetPageState.Error<Row>(
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
        assertIs<OffsetPageState.Error<WovenEntry<Row, String>>>(wrappedAppend)
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
            PaginatorUiState.Loading(OffsetPageState.Progress(page = 1, data = emptyList())),
            PaginatorUiState.Content(
                prependState = null,
                items = listOf(Row(1, "A"), Row(2, "B")),
                appendState = null,
            ),
        )
        val emissions = source.interweave(rowWeaver()).toList()
        assertEquals(3, emissions.size)
        assertSame(PaginatorUiState.Idle, emissions[0])
        assertIs<PaginatorUiState.Loading<WovenEntry<Row, String>>>(emissions[1])
        val content = assertIs<PaginatorUiState.Content<WovenEntry<Row, String>>>(emissions[2])
        assertEquals(3, content.items.size)
    }
}
