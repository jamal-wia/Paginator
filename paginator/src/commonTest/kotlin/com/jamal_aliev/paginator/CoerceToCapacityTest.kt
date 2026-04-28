package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.extension.prefetchController
import com.jamal_aliev.paginator.load.LoadResult
import com.jamal_aliev.paginator.page.PageState.ErrorPage
import com.jamal_aliev.paginator.page.PageState.ProgressPage
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CoerceToCapacityTest {


    fun main() {
        // Data
        fun dataAPICall() = emptyList<String>()

        // ViewModel
        val paginator = Paginator<String>(
            core = PagingCore(initialCapacity = 10),
            load = { LoadResult(dataAPICall()) }
        )

        // UI
        paginator.prefetchController(
            scope = CoroutineScope(CoroutineName("")), // UIScope or ViewModelScope,
            prefetchDistance = 5,
        )


    }


    // ──────────────────────────────────────────────────────────────────────
    //  Helper
    // ──────────────────────────────────────────────────────────────────────

    private fun paginator(capacity: Int): MutablePaginator<String> {
        return MutablePaginator<String> { LoadResult(emptyList()) }.apply {
            core.resize(capacity = capacity, resize = false, silently = true)
        }
    }

    private fun unlimitedPaginator(): MutablePaginator<String> {
        return paginator(PagingCore.UNLIMITED_CAPACITY)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  coerceToCapacity(data: List<T>)
    // ══════════════════════════════════════════════════════════════════════

    // ── Basic trimming ───────────────────────────────────────────────────

    @Test
    fun `list - trims data exceeding capacity`() {
        val p = paginator(3)
        val result = p.core.coerceToCapacity(listOf("a", "b", "c", "d", "e"))
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `list - keeps first N elements when trimming`() {
        val p = paginator(2)
        val result = p.core.coerceToCapacity(listOf("first", "second", "third"))
        assertEquals(listOf("first", "second"), result)
    }

    // ── No trimming needed ───────────────────────────────────────────────

    @Test
    fun `list - returns same data when size equals capacity`() {
        val p = paginator(3)
        val result = p.core.coerceToCapacity(listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `list - returns same data when size is less than capacity`() {
        val p = paginator(5)
        val result = p.core.coerceToCapacity(listOf("a", "b"))
        assertEquals(listOf("a", "b"), result)
    }

    // ── Empty list ───────────────────────────────────────────────────────

    @Test
    fun `list - empty list stays empty`() {
        val p = paginator(3)
        val result = p.core.coerceToCapacity(emptyList())
        assertTrue(result.isEmpty())
    }

    // ── Unlimited capacity ───────────────────────────────────────────────

    @Test
    fun `list - unlimited capacity never trims`() {
        val p = unlimitedPaginator()
        val big = List(1000) { "item_$it" }
        val result = p.core.coerceToCapacity(big)
        assertEquals(1000, result.size)
    }

    @Test
    fun `list - unlimited capacity with empty list`() {
        val p = unlimitedPaginator()
        val result = p.core.coerceToCapacity(emptyList())
        assertTrue(result.isEmpty())
    }

    // ── MutableList guarantee ────────────────────────────────────────────

    @Test
    fun `list - result is always MutableList when trimmed`() {
        val p = paginator(2)
        val result = p.core.coerceToCapacity(listOf("a", "b", "c"))
        assertIs<MutableList<String>>(result)
    }

    @Test
    fun `list - result is MutableList when not trimmed and input is immutable`() {
        val p = paginator(5)
        val immutable = listOf("a", "b")
        val result = p.core.coerceToCapacity(immutable)
        assertIs<MutableList<String>>(result)
    }

    @Test
    fun `list - MutableList input is preserved as-is when not trimmed`() {
        val p = paginator(5)
        val mutable = mutableListOf("a", "b")
        val result = p.core.coerceToCapacity(mutable)
        assertSame(result, mutable)
    }

    // ── Capacity = 1 ─────────────────────────────────────────────────────

    @Test
    fun `list - capacity 1 trims to single element`() {
        val p = paginator(1)
        val result = p.core.coerceToCapacity(listOf("a", "b", "c"))
        assertEquals(listOf("a"), result)
    }

    @Test
    fun `list - capacity 1 keeps single element`() {
        val p = paginator(1)
        val result = p.core.coerceToCapacity(listOf("only"))
        assertEquals(listOf("only"), result)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  coerceToCapacity(state: PageState<T>)
    // ══════════════════════════════════════════════════════════════════════

    // ── SuccessPage ──────────────────────────────────────────────────────

    @Test
    fun `state - SuccessPage trimmed when data exceeds capacity`() {
        val p = paginator(2)
        val state = SuccessPage(page = 1, data = listOf("a", "b", "c", "d"))
        val result = p.core.coerceToCapacity(state)
        assertEquals(listOf("a", "b"), result.data)
        assertEquals(1, result.page)
    }

    @Test
    fun `state - SuccessPage returned as-is when within capacity`() {
        val p = paginator(5)
        val state = SuccessPage(page = 1, data = listOf("a", "b"))
        val result = p.core.coerceToCapacity(state)
        assertSame(result, state)
    }

    @Test
    fun `state - SuccessPage returned as-is when size equals capacity`() {
        val p = paginator(3)
        val state = SuccessPage(page = 1, data = listOf("a", "b", "c"))
        val result = p.core.coerceToCapacity(state)
        assertSame(result, state)
    }

    @Test
    fun `state - SuccessPage trimmed at capacity boundary stays SuccessPage`() {
        val p1 = paginator(1)
        val state = SuccessPage(page = 1, data = listOf("a"))
        val result = p1.core.coerceToCapacity(state)
        assertIs<SuccessPage<String>>(result)
        assertEquals(listOf("a"), result.data)
    }

    // ── empty SuccessPage ────────────────────────────────────────────────

    @Test
    fun `state - empty SuccessPage returned as-is`() {
        val p = paginator(3)
        val state = SuccessPage<String>(page = 1, data = emptyList())
        val result = p.core.coerceToCapacity(state)
        assertSame(result, state)
    }

    // ── ProgressPage ─────────────────────────────────────────────────────

    @Test
    fun `state - ProgressPage trimmed when data exceeds capacity`() {
        val p = paginator(2)
        val state = ProgressPage(page = 1, data = listOf("a", "b", "c"))
        val result = p.core.coerceToCapacity(state)
        assertEquals(listOf("a", "b"), result.data)
        assertEquals(1, result.page)
    }

    @Test
    fun `state - ProgressPage returned as-is when within capacity`() {
        val p = paginator(5)
        val state = ProgressPage(page = 1, data = listOf("a"))
        val result = p.core.coerceToCapacity(state)
        assertSame(result, state)
    }

    @Test
    fun `state - ProgressPage with empty data returned as-is`() {
        val p = paginator(3)
        val state = ProgressPage<String>(page = 1, data = emptyList())
        val result = p.core.coerceToCapacity(state)
        assertSame(result, state)
    }

    // ── ErrorPage ────────────────────────────────────────────────────────

    @Test
    fun `state - ErrorPage trimmed when data exceeds capacity`() {
        val p = paginator(2)
        val ex = RuntimeException("fail")
        val state = ErrorPage(exception = ex, page = 1, data = listOf("a", "b", "c"))
        val result = p.core.coerceToCapacity(state)
        assertEquals(listOf("a", "b"), result.data)
        assertEquals(1, result.page)
    }

    @Test
    fun `state - ErrorPage returned as-is when within capacity`() {
        val p = paginator(5)
        val ex = RuntimeException("fail")
        val state = ErrorPage(exception = ex, page = 1, data = listOf("a"))
        val result = p.core.coerceToCapacity(state)
        assertSame(result, state)
    }

    @Test
    fun `state - ErrorPage with empty data returned as-is`() {
        val p = paginator(3)
        val ex = RuntimeException("fail")
        val state = ErrorPage<String>(exception = ex, page = 1, data = emptyList())
        val result = p.core.coerceToCapacity(state)
        assertSame(result, state)
    }

    // ── Unlimited capacity (state) ───────────────────────────────────────

    @Test
    fun `state - unlimited capacity never trims SuccessPage`() {
        val p = unlimitedPaginator()
        val state = SuccessPage(page = 1, data = List(500) { "item_$it" })
        val result = p.core.coerceToCapacity(state)
        assertSame(result, state)
        assertEquals(500, result.data.size)
    }

    @Test
    fun `state - unlimited capacity never trims ProgressPage`() {
        val p = unlimitedPaginator()
        val state = ProgressPage(page = 1, data = List(500) { "item_$it" })
        val result = p.core.coerceToCapacity(state)
        assertSame(result, state)
    }

    @Test
    fun `state - unlimited capacity never trims ErrorPage`() {
        val p = unlimitedPaginator()
        val ex = RuntimeException("fail")
        val state = ErrorPage(exception = ex, page = 1, data = List(500) { "item_$it" })
        val result = p.core.coerceToCapacity(state)
        assertSame(result, state)
    }

    // ── Page number preservation ─────────────────────────────────────────

    @Test
    fun `state - page number is preserved after trimming`() {
        val p = paginator(1)
        val state = SuccessPage(page = 42, data = listOf("a", "b", "c"))
        val result = p.core.coerceToCapacity(state)
        assertEquals(42, result.page)
    }

    // ── Type preservation ────────────────────────────────────────────────

    @Test
    fun `state - ProgressPage type preserved after trimming`() {
        val p = paginator(1)
        val state = ProgressPage(page = 1, data = listOf("a", "b"))
        val result = p.core.coerceToCapacity(state)
        assertIs<ProgressPage<String>>(result)
    }

    @Test
    fun `state - ErrorPage type preserved after trimming`() {
        val p = paginator(1)
        val ex = RuntimeException("fail")
        val state = ErrorPage(exception = ex, page = 1, data = listOf("a", "b"))
        val result = p.core.coerceToCapacity(state)
        assertIs<ErrorPage<String>>(result)
    }

    @Test
    fun `state - data exactly one over capacity is trimmed`() {
        val p = paginator(3)
        val state = SuccessPage(page = 1, data = listOf("a", "b", "c", "d"))
        val result = p.core.coerceToCapacity(state)
        assertEquals(3, result.data.size)
    }
}
