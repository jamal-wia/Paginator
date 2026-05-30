package com.jamal_aliev.paginator.offset.pagestate


import com.jamal_aliev.paginator.core.extension.isEmptyState
import com.jamal_aliev.paginator.core.extension.isSuccessState
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmptyStateTest {

    @Test
    fun `isEmptyState true for Success with empty data`() {
        val state: OffsetPageState<String> = OffsetPageState.Success(page = 1, data = emptyList())
        assertTrue(state.isEmptyState())
        assertFalse(state.isSuccessState())
    }

    @Test
    fun `isEmptyState false for Success with items`() {
        val state: OffsetPageState<String> = OffsetPageState.Success(page = 1, data = listOf("a"))
        assertFalse(state.isEmptyState())
        assertTrue(state.isSuccessState())
    }

    @Test
    fun `isEmptyState false for non-success states`() {
        val progress: OffsetPageState<String> =
            OffsetPageState.Progress(page = 1, data = emptyList())
        val error: OffsetPageState<String> = OffsetPageState.Error(
            exception = RuntimeException("nope"),
            page = 1,
            data = emptyList(),
        )
        assertFalse(progress.isEmptyState())
        assertFalse(error.isEmptyState())
    }

    @Test
    fun `isEmptyState false for null receiver`() {
        val state: OffsetPageState<String>? = null
        assertFalse(state.isEmptyState())
    }
}
