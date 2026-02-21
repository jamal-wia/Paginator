package com.jamal_aliev.paginator.pagestate

import com.jamal_aliev.paginator.extension.far
import com.jamal_aliev.paginator.extension.gap
import com.jamal_aliev.paginator.extension.near
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageStateDistanceTest {

    private fun state(page: Int): PageState<String> =
        SuccessPage(page = page, data = listOf("data"))

    @Test
    fun `gap returns zero for same page`() {
        val a = state(5)
        val b = state(5)
        assertEquals(0, a gap b)
    }

    @Test
    fun `gap returns 1 for adjacent pages`() {
        assertEquals(1, state(3) gap state(4))
        assertEquals(1, state(4) gap state(3))
    }

    @Test
    fun `gap returns absolute distance`() {
        assertEquals(10, state(1) gap state(11))
        assertEquals(10, state(11) gap state(1))
    }

    @Test
    fun `near returns true for same page`() {
        assertTrue(state(5) near state(5))
    }

    @Test
    fun `near returns true for adjacent pages`() {
        assertTrue(state(3) near state(4))
        assertTrue(state(4) near state(3))
    }

    @Test
    fun `near returns false for non-adjacent pages`() {
        assertFalse(state(1) near state(3))
    }

    @Test
    fun `far returns false for same page`() {
        assertFalse(state(5) far state(5))
    }

    @Test
    fun `far returns false for adjacent pages`() {
        assertFalse(state(3) far state(4))
    }

    @Test
    fun `far returns true for non-adjacent pages`() {
        assertTrue(state(1) far state(3))
        assertTrue(state(10) far state(1))
    }
}
