package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.BookmarkInt
import com.jamal_aliev.paginator.extension.asUiState
import com.jamal_aliev.paginator.extension.toUiState
import com.jamal_aliev.paginator.extension.uiState
import com.jamal_aliev.paginator.load.LoadResult
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PageState.EmptyPage
import com.jamal_aliev.paginator.page.PageState.ErrorPage
import com.jamal_aliev.paginator.page.PageState.ProgressPage
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import com.jamal_aliev.paginator.page.PaginatorUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class PaginatorUiStateTest {

    // =========================================================================
    // toUiState — pure mapper
    // =========================================================================

    @Test
    fun `empty list with isStarted=false returns Idle`() {
        val state = emptyList<PageState<String>>().toUiState(isStarted = false)
        assertSame(PaginatorUiState.Idle, state)
    }

    @Test
    fun `empty list with isStarted=true returns Idle`() {
        val state = emptyList<PageState<String>>().toUiState(isStarted = true)
        assertSame(PaginatorUiState.Idle, state)
    }

    @Test
    fun `non-empty list with isStarted=false returns Idle`() {
        val list = listOf(SuccessPage(page = 1, data = mutableListOf("a")))
        val state = list.toUiState(isStarted = false)
        assertSame(PaginatorUiState.Idle, state)
    }

    @Test
    fun `single ProgressPage with empty data returns Loading`() {
        val list = listOf<PageState<String>>(ProgressPage(page = 1, data = mutableListOf()))
        val state = list.toUiState(isStarted = true)
        assertEquals(PaginatorUiState.Loading(page = 1), state)
    }

    @Test
    fun `single EmptyPage with empty data returns Empty`() {
        val list = listOf<PageState<String>>(EmptyPage(page = 1, data = mutableListOf()))
        val state = list.toUiState(isStarted = true)
        assertEquals(PaginatorUiState.Empty(page = 1), state)
    }

    @Test
    fun `single ErrorPage with empty data returns Error`() {
        val exception = Exception("boom")
        val list = listOf<PageState<String>>(
            ErrorPage(exception = exception, page = 1, data = mutableListOf())
        )
        val state = list.toUiState(isStarted = true)
        assertEquals(PaginatorUiState.Error(page = 1, exception = exception), state)
    }

    @Test
    fun `single ProgressPage with carried data returns Content exposing that data`() {
        val progress = ProgressPage(page = 1, data = mutableListOf("carried"))
        val list: List<PageState<String>> = listOf(progress)
        val state = list.toUiState(isStarted = true)
        assertIs<PaginatorUiState.Content<String>>(state)
        // Data carried forward by the paginator into a ProgressPage (e.g. when a
        // partially-filled SuccessPage is reloaded) flows through to items so it
        // stays visible while the reload is in flight.
        assertEquals(listOf("carried"), state.items)
        assertSame(progress, state.prependState)
        assertSame(progress, state.appendState)
    }

    @Test
    fun `single ErrorPage with carried data returns Content exposing that data`() {
        val error = ErrorPage<String>(
            exception = Exception("boom"),
            page = 1,
            data = mutableListOf("carried"),
        )
        val list: List<PageState<String>> = listOf(error)
        val state = list.toUiState(isStarted = true)
        assertIs<PaginatorUiState.Content<String>>(state)
        // Data carried forward by the paginator into an ErrorPage (e.g. when a
        // reload of a partially-filled SuccessPage fails) flows through to items
        // so the last known items stay visible alongside the error indicator.
        assertEquals(listOf("carried"), state.items)
        assertSame(error, state.prependState)
        assertSame(error, state.appendState)
    }

    @Test
    fun `single SuccessPage returns Content with null boundaries`() {
        val success = SuccessPage(page = 1, data = mutableListOf("a", "b"))
        val list: List<PageState<String>> = listOf(success)
        val state = list.toUiState(isStarted = true)
        assertIs<PaginatorUiState.Content<String>>(state)
        assertEquals(listOf("a", "b"), state.items)
        assertNull(state.prependState)
        assertNull(state.appendState)
    }

    @Test
    fun `multiple SuccessPages aggregates items with null boundaries`() {
        val list: List<PageState<String>> = listOf(
            SuccessPage(page = 1, data = mutableListOf("a")),
            SuccessPage(page = 2, data = mutableListOf("b", "c")),
            SuccessPage(page = 3, data = mutableListOf("d")),
        )
        val state = list.toUiState(isStarted = true)
        assertIs<PaginatorUiState.Content<String>>(state)
        assertEquals(listOf("a", "b", "c", "d"), state.items)
        assertNull(state.prependState)
        assertNull(state.appendState)
    }

    @Test
    fun `leading non-success becomes prependState`() {
        val progress = ProgressPage<String>(page = 0, data = mutableListOf())
        val success = SuccessPage(page = 1, data = mutableListOf("a", "b"))
        val list: List<PageState<String>> = listOf(progress, success)
        val state = list.toUiState(isStarted = true)
        assertIs<PaginatorUiState.Content<String>>(state)
        assertEquals(listOf("a", "b"), state.items)
        assertSame(progress, state.prependState)
        assertNull(state.appendState)
    }

    @Test
    fun `trailing non-success becomes appendState`() {
        val success = SuccessPage(page = 1, data = mutableListOf("a"))
        val progress = ProgressPage<String>(page = 2, data = mutableListOf())
        val list: List<PageState<String>> = listOf(success, progress)
        val state = list.toUiState(isStarted = true)
        assertIs<PaginatorUiState.Content<String>>(state)
        assertEquals(listOf("a"), state.items)
        assertNull(state.prependState)
        assertSame(progress, state.appendState)
    }

    @Test
    fun `both boundaries non-success merge carried data into items`() {
        val topError = ErrorPage<String>(
            exception = Exception("prev-failed"),
            page = 0,
            data = mutableListOf("prev-carried"),
        )
        val mid = SuccessPage(page = 1, data = mutableListOf("a", "b"))
        val bottomProgress = ProgressPage<String>(
            page = 2,
            data = mutableListOf("next-carried"),
        )
        val list: List<PageState<String>> = listOf(topError, mid, bottomProgress)
        val state = list.toUiState(isStarted = true)
        assertIs<PaginatorUiState.Content<String>>(state)
        assertEquals(listOf("prev-carried", "a", "b", "next-carried"), state.items)
        assertSame(topError, state.prependState)
        assertSame(bottomProgress, state.appendState)
    }

    @Test
    fun `EmptyPage in the middle does not contribute data`() {
        val a = SuccessPage(page = 1, data = mutableListOf("a"))
        val emptyMid = EmptyPage(page = 2, data = mutableListOf<String>())
        val c = SuccessPage(page = 3, data = mutableListOf("c"))
        val list: List<PageState<String>> = listOf(a, emptyMid, c)
        val state = list.toUiState(isStarted = true)
        assertIs<PaginatorUiState.Content<String>>(state)
        assertEquals(listOf("a", "c"), state.items)
        assertNull(state.prependState)
        assertNull(state.appendState)
    }

    // =========================================================================
    // asUiState — Flow extension with isStarted provider
    // =========================================================================

    @Test
    fun `asUiState reads isStarted provider on every emission`() = runTest {
        val source = MutableStateFlow<List<PageState<String>>>(emptyList())
        var started = false
        val flow = source.asUiState(isStarted = { started })

        assertSame(PaginatorUiState.Idle, flow.first())

        started = true
        source.value = listOf(SuccessPage(page = 1, data = mutableListOf("a")))
        val content = flow.first()
        assertIs<PaginatorUiState.Content<String>>(content)
        assertEquals(listOf("a"), content.items)

        started = false
        source.value = listOf(SuccessPage(page = 1, data = mutableListOf("b")))
        assertSame(PaginatorUiState.Idle, flow.first())
    }

    // =========================================================================
    // Paginator.uiState — integration via real paginator
    // =========================================================================

    @Test
    fun `uiState is Idle before any navigation`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        assertSame(PaginatorUiState.Idle, paginator.uiState.first())
    }

    @Test
    fun `uiState is Content after successful jump`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 3, totalItems = 9)
        paginator.jump(BookmarkInt(1))

        val state = paginator.uiState.first()
        assertIs<PaginatorUiState.Content<String>>(state)
        assertEquals(3, state.items.size)
        assertEquals("item_0", state.items.first())
        assertNull(state.prependState)
        assertNull(state.appendState)
    }

    @Test
    fun `uiState is Empty when first jump returns no data`() = runTest {
        val paginator = MutablePaginator<String> { LoadResult(emptyList()) }
        paginator.core.resize(capacity = 5, resize = false, silently = true)
        paginator.jump(BookmarkInt(1))

        val state = paginator.uiState.first()
        assertEquals(PaginatorUiState.Empty(page = 1), state)
    }

    @Test
    fun `uiState is Error when first jump throws with no prior data`() = runTest {
        val failure = IllegalStateException("network down")
        val paginator = MutablePaginator<String> { throw failure }
        paginator.core.resize(capacity = 5, resize = false, silently = true)

        // jump() catches load exceptions and writes them back as an ErrorPage; it does not throw.
        paginator.jump(BookmarkInt(1))

        val state = paginator.uiState.first()
        assertIs<PaginatorUiState.Error>(state)
        assertEquals(1, state.page)
        assertSame(failure, state.exception)
    }

    @Test
    fun `uiState keeps carried items while refreshing a loaded page`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 1, capacity = 3)

        // Manually place a ProgressPage that carries the previously loaded data and trigger
        // a snapshot emission via core.setState (the cache-level setState does not emit
        // a snapshot). This mimics what Paginator.goNextPage / refresh do internally while
        // a reload is in flight.
        val current = paginator.cache.getStateOf(1)!!
        val carried: List<String> = current.data.toList()
        paginator.core.setState(
            state = ProgressPage(page = 1, data = current.data.toMutableList()),
            silently = false,
        )

        val state = paginator.uiState.first()
        assertIs<PaginatorUiState.Content<String>>(state)
        // Previously loaded items must stay visible while the page reloads.
        assertEquals(carried, state.items)
        assertIs<ProgressPage<String>>(state.prependState)
        assertIs<ProgressPage<String>>(state.appendState)
    }

    @Test
    fun `uiState keeps carried items when reloading a partial SuccessPage fails`() = runTest {
        // Scenario: page 1 loads with fewer items than capacity (partial SuccessPage).
        // When goNextPage is called, the paginator detects the partial fill and reloads
        // page 1. During the reload, Paginator creates a ProgressPage with the previously
        // loaded data carried over (see Paginator.kt:642). If the reload throws, the
        // catch branch of loadOrGetPageState (Paginator.kt:1170-1174) creates an ErrorPage
        // from the same pre-loading cachedState, so the carried data survives the failure.
        // PaginatorUiState.Content.items must expose those carried items so the UI
        // keeps showing them next to the error indicator.
        val partialItems = listOf("p1_a", "p1_b")
        val failure = IllegalStateException("reload failed")
        var attempt = 0
        val paginator = MutablePaginator<String> { _ ->
            attempt++
            if (attempt == 1) LoadResult(partialItems) else throw failure
        }
        paginator.core.resize(capacity = 5, resize = false, silently = true)

        paginator.jump(BookmarkInt(1))
        // page 1 is now a partially-filled SuccessPage (2 items, capacity 5).

        paginator.goNextPage()
        // page 1 was reloaded in place; the reload failed and Paginator wrote an
        // ErrorPage(page = 1, data = [carried items]).

        val state = paginator.uiState.first()
        assertIs<PaginatorUiState.Content<String>>(state)
        assertEquals(partialItems, state.items)
        val boundary = assertIs<ErrorPage<String>>(state.appendState)
        assertEquals(1, boundary.page)
        assertSame(failure, boundary.exception)
        assertEquals(partialItems, boundary.data)
    }

    @Test
    fun `uiState is Idle after release`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 3, totalItems = 9)
        paginator.jump(BookmarkInt(1))

        paginator.release(silently = false)

        assertSame(PaginatorUiState.Idle, paginator.uiState.first())
    }

    @Test
    fun `uiState reports appendState when goNextPage fails and no prior data exists`() = runTest {
        val failure = IllegalStateException("page 2 down")
        val paginator = MutablePaginator<String> { page ->
            if (page == 1) LoadResult(MutableList(3) { "p1_$it" })
            else throw failure
        }
        paginator.core.resize(capacity = 3, resize = false, silently = true)

        paginator.jump(BookmarkInt(1))
        // goNextPage() catches load exceptions and writes them back as an ErrorPage; it does not throw.
        paginator.goNextPage()

        val state = paginator.uiState.first()
        assertIs<PaginatorUiState.Content<String>>(state)
        assertEquals(listOf("p1_0", "p1_1", "p1_2"), state.items)
        assertNull(state.prependState)
        val append = state.appendState
        assertIs<ErrorPage<String>>(append)
        assertEquals(2, append.page)
        assertSame(failure, append.exception)
    }
}
