package com.jamal_aliev.paginator.compose.core

import com.jamal_aliev.paginator.core.load.Metadata
import com.jamal_aliev.paginator.core.page.PageState
import com.jamal_aliev.paginator.core.page.PaginatorUiState
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

private class FakeProgress<T>(override val data: List<T>) : PageState.ProgressState<T> {
    override val metadata: Metadata? = null
    override val id: Long = PageState.nextId()
    override fun <R> withData(data: List<R>): PageState<R> = FakeProgress(data)
}

private class FakeSuccess<T>(override val data: List<T>) : PageState.SuccessState<T> {
    override val metadata: Metadata? = null
    override val id: Long = PageState.nextId()
    override fun <R> withData(data: List<R>): PageState<R> = FakeSuccess(data)
}

private class FakeError<T>(override val data: List<T>) : PageState.ErrorState<T> {
    override val exception: Exception = RuntimeException("boom")
    override val metadata: Metadata? = null
    override val id: Long = PageState.nextId()
    override fun <R> withData(data: List<R>): PageState<R> = FakeError(data)
}

class ScrollEdgeIndicatorsTest {

    @Test
    fun prepend_progress_returned_when_top_is_loading() {
        val progress = FakeProgress(listOf("a"))
        val state = PaginatorUiState.Content(
            prependState = progress,
            items = listOf("a", "b"),
            appendState = null,
        )
        assertSame(progress, state.prependProgressState())
        assertNull(state.appendProgressState())
    }

    @Test
    fun append_progress_returned_when_bottom_is_loading() {
        val progress = FakeProgress(listOf("z"))
        val state = PaginatorUiState.Content(
            prependState = null,
            items = listOf("y", "z"),
            appendState = progress,
        )
        assertSame(progress, state.appendProgressState())
        assertNull(state.prependProgressState())
    }

    @Test
    fun error_boundary_is_not_treated_as_a_loading_indicator() {
        val state = PaginatorUiState.Content(
            prependState = FakeError(emptyList<String>()),
            items = listOf("a"),
            appendState = FakeError(emptyList<String>()),
        )
        assertNull(state.prependProgressState())
        assertNull(state.appendProgressState())
    }

    @Test
    fun error_state_returned_when_boundary_failed() {
        val prependError = FakeError(emptyList<String>())
        val appendError = FakeError(emptyList<String>())
        val state = PaginatorUiState.Content(
            prependState = prependError,
            items = listOf("a"),
            appendState = appendError,
        )
        assertSame(prependError, state.prependErrorState())
        assertSame(appendError, state.appendErrorState())
    }

    @Test
    fun loading_boundary_is_not_treated_as_an_error() {
        val state = PaginatorUiState.Content(
            prependState = FakeProgress(emptyList<String>()),
            items = listOf("a"),
            appendState = FakeProgress(emptyList<String>()),
        )
        assertNull(state.prependErrorState())
        assertNull(state.appendErrorState())
    }

    @Test
    fun non_content_states_have_no_error_boundaries() {
        val error: PaginatorUiState<String> =
            PaginatorUiState.Error(FakeError(emptyList<String>()))
        assertNull(error.prependErrorState())
        assertNull(error.appendErrorState())
        assertNull(PaginatorUiState.Idle.prependErrorState())
    }

    @Test
    fun success_boundary_has_no_indicator() {
        val state = PaginatorUiState.Content<String>(
            prependState = null,
            items = listOf("a", "b"),
            appendState = null,
        )
        assertNull(state.prependProgressState())
        assertNull(state.appendProgressState())
    }

    @Test
    fun non_content_states_have_no_indicators() {
        val loading: PaginatorUiState<String> =
            PaginatorUiState.Loading(FakeProgress(emptyList<String>()))
        assertNull(loading.prependProgressState())
        assertNull(loading.appendProgressState())

        val idle: PaginatorUiState<String> = PaginatorUiState.Idle
        assertNull(idle.prependProgressState())
        assertNull(idle.appendProgressState())
    }
}
