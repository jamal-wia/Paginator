package com.jamal_aliev.paginator.pagestate

import com.jamal_aliev.paginator.extension.isEmptyState
import com.jamal_aliev.paginator.extension.isSuccessState
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmptyStateTest {

    @Test
    fun `isEmptyState true for SuccessPage with empty data`() {
        val state: PageState<String> = SuccessPage(page = 1, data = emptyList())
        assertTrue(state.isEmptyState())
        assertFalse(state.isSuccessState())
    }

    @Test
    fun `isEmptyState false for SuccessPage with items`() {
        val state: PageState<String> = SuccessPage(page = 1, data = listOf("a"))
        assertFalse(state.isEmptyState())
        assertTrue(state.isSuccessState())
    }

    @Test
    fun `isEmptyState false for non-success states`() {
        val progress: PageState<String> = PageState.ProgressPage(page = 1, data = emptyList())
        val error: PageState<String> = PageState.ErrorPage(
            exception = RuntimeException("nope"),
            page = 1,
            data = emptyList(),
        )
        assertFalse(progress.isEmptyState())
        assertFalse(error.isEmptyState())
    }

    @Test
    fun `isEmptyState false for null receiver`() {
        val state: PageState<String>? = null
        assertFalse(state.isEmptyState())
    }
}
