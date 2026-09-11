package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.core.page.PaginatorUiState
import com.jamal_aliev.paginator.cursor.extension.uiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CursorPaginatorUiStateTest {

    @Test
    fun idle_before_restart() = runTest {
        val paginator = cursorPaginatorOf()
        // First emission with no navigation must be Idle.
        val state = paginator.uiState.first()
        assertEquals(PaginatorUiState.Idle, state)
    }

    @Test
    fun content_after_restart_and_goNext() = runTest {
        val backend = FakeCursorBackend()
        val paginator = cursorPaginatorOf(backend)
        paginator.restart()
        paginator.goNextPage()

        val state = paginator.uiState.first()
        assertTrue(state is PaginatorUiState.Content, "expected Content, got $state")
        // Two pages × 3 items = 6.
        assertEquals(6, state.items.size)
        assertEquals(null, state.prependState, "head is a full success page")
        assertEquals(null, state.appendState, "tail is a full success page")
    }

    @Test
    fun empty_when_source_returns_empty_list() = runTest {
        val pages = listOf(
            FakeCursorBackend.Page(
                self = "empty",
                prev = null,
                next = null,
                items = emptyList(),
            ),
        )
        val backend = FakeCursorBackend(pages = pages)
        val paginator = cursorPaginatorOf(backend)
        paginator.restart()
        val state = paginator.uiState.first()
        assertTrue(state is PaginatorUiState.Empty)
    }

    @Test
    fun error_after_failed_unanchored_restart() = runTest {
        // Regression test for issue #3: a failed restart() with no initialCursor (the
        // first-page / not-yet-started case) used to null out both context cursors
        // before calling core.snapshot(), leaving it with no range to compute and thus
        // publishing nothing — uiState stayed stuck on the transient Loading state.
        val paginator = failingCursorPaginator(RuntimeException("boom"))
        paginator.restart()

        val state = paginator.uiState.first()
        assertTrue(state is PaginatorUiState.Error, "expected Error, got $state")
    }
}
