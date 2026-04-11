package com.jamal_aliev.paginator.strategy

import com.jamal_aliev.paginator.page.PageState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.hours

class CompositeStrategyTest {

    private fun successPage(page: Int): PageState.SuccessPage<String> =
        PageState.SuccessPage(page = page, data = listOf("item_$page"))

    // ── WrappablePagingCache: interface membership ─────────────────────────────
    // These are compile-time assertions: if any strategy stops implementing
    // WrappablePagingCache the assignments below will fail to compile.

    @Test
    fun `all four built-in strategies implement WrappablePagingCache`() {
        @Suppress("UNUSED_VARIABLE") val lru: WrappablePagingCache<String> = LruPagingCache(maxSize = 1)
        @Suppress("UNUSED_VARIABLE") val fifo: WrappablePagingCache<String> = FifoPagingCache(maxSize = 1)
        @Suppress("UNUSED_VARIABLE") val ttl: WrappablePagingCache<String> = TtlPagingCache(ttl = 1.hours)
        @Suppress("UNUSED_VARIABLE") val sliding: WrappablePagingCache<String> = SlidingWindowPagingCache()
    }

    @Test
    fun `DefaultPagingCache is not a WrappablePagingCache - plus returns inner directly`() {
        val leaf = DefaultPagingCache<String>()
        val inner = LruPagingCache<String>(maxSize = 5)
        assertSame(inner, leaf + inner)
    }

    // ── WrappablePagingCache: replaceLeaf() ────────────────────────────────────

    @Test
    fun `LruPagingCache replaceLeaf inserts newLeaf at bottom and preserves config`() {
        val lru = LruPagingCache<String>(maxSize = 7, protectContextWindow = false)
        val newLeaf = FifoPagingCache<String>(maxSize = 3)
        val result = lru.replaceLeaf(newLeaf)
        assertSame(newLeaf, result.wrapped)
        assertEquals(7, result.maxSize)
        assertEquals(false, result.protectContextWindow)
    }

    @Test
    fun `FifoPagingCache replaceLeaf inserts newLeaf at bottom and preserves config`() {
        val fifo = FifoPagingCache<String>(maxSize = 4, protectContextWindow = false)
        val newLeaf = DefaultPagingCache<String>()
        val result = fifo.replaceLeaf(newLeaf)
        assertSame(newLeaf, result.wrapped)
        assertEquals(4, result.maxSize)
        assertEquals(false, result.protectContextWindow)
    }

    @Test
    fun `TtlPagingCache replaceLeaf inserts newLeaf at bottom and preserves config`() {
        val ttl = TtlPagingCache<String>(ttl = 2.hours, refreshOnAccess = true, protectContextWindow = false)
        val newLeaf = DefaultPagingCache<String>()
        val result = ttl.replaceLeaf(newLeaf)
        assertSame(newLeaf, result.wrapped)
        assertEquals(2.hours, result.ttl)
        assertEquals(true, result.refreshOnAccess)
        assertEquals(false, result.protectContextWindow)
    }

    @Test
    fun `SlidingWindowPagingCache replaceLeaf inserts newLeaf at bottom and preserves config`() {
        val sliding = SlidingWindowPagingCache<String>(margin = 3)
        val newLeaf = DefaultPagingCache<String>()
        val result = sliding.replaceLeaf(newLeaf)
        assertSame(newLeaf, result.wrapped)
        assertEquals(3, result.margin)
    }

    @Test
    fun `replaceLeaf on deep chain replaces only the leaf`() {
        // lru → fifo → Default; replaceLeaf(sliding) → lru → fifo → sliding → Default
        val lru = LruPagingCache<String>(maxSize = 5) + FifoPagingCache<String>(maxSize = 3)
        val sliding = SlidingWindowPagingCache<String>()
        val result = assertIs<LruPagingCache<String>>(
            assertIs<WrappablePagingCache<String>>(lru).replaceLeaf(sliding)
        )
        val fifoLayer = assertIs<FifoPagingCache<String>>(result.wrapped)
        assertSame(sliding, fifoLayer.wrapped)
    }

    @Test
    fun `replaceLeaf does not mutate the original strategy`() {
        val lru = LruPagingCache<String>(maxSize = 5)
        val newLeaf = FifoPagingCache<String>(maxSize = 3)
        lru.replaceLeaf(newLeaf)
        assertIs<DefaultPagingCache<String>>(lru.wrapped)
    }

    // ── plus operator: structural tests ───────────────────────────────────────

    @Test
    fun `plus on custom non-WrappablePagingCache returns inner directly`() {
        val customLeaf = object : PagingCache<String> by DefaultPagingCache() {}
        val fifo = FifoPagingCache<String>(maxSize = 3)
        val result = customLeaf + fifo
        assertSame(fifo, result)
    }

    @Test
    fun `LRU plus FIFO produces LRU wrapping FIFO`() {
        val lru = LruPagingCache<String>(maxSize = 10)
        val fifo = FifoPagingCache<String>(maxSize = 5)
        val result = assertIs<LruPagingCache<String>>(lru + fifo)
        assertIs<FifoPagingCache<String>>(result.wrapped)
    }

    @Test
    fun `FIFO plus SlidingWindow produces FIFO wrapping SlidingWindow`() {
        val fifo = FifoPagingCache<String>(maxSize = 5)
        val sliding = SlidingWindowPagingCache<String>()
        val result = assertIs<FifoPagingCache<String>>(fifo + sliding)
        assertIs<SlidingWindowPagingCache<String>>(result.wrapped)
    }

    @Test
    fun `plus is left-associative - LRU plus FIFO plus SlidingWindow`() {
        val lru = LruPagingCache<String>(maxSize = 10)
        val fifo = FifoPagingCache<String>(maxSize = 5)
        val sliding = SlidingWindowPagingCache<String>()

        val result = assertIs<LruPagingCache<String>>(lru + fifo + sliding)
        val fifoLayer = assertIs<FifoPagingCache<String>>(result.wrapped)
        assertIs<SlidingWindowPagingCache<String>>(fifoLayer.wrapped)
    }

    @Test
    fun `plus preserves outer strategy config`() {
        val lru = LruPagingCache<String>(maxSize = 7, protectContextWindow = false)
        val fifo = FifoPagingCache<String>(maxSize = 3)
        val result = assertIs<LruPagingCache<String>>(lru + fifo)
        assertEquals(7, result.maxSize)
        assertEquals(false, result.protectContextWindow)
    }

    @Test
    fun `plus preserves inner strategy config`() {
        val lru = LruPagingCache<String>(maxSize = 10)
        val fifo = FifoPagingCache<String>(maxSize = 4, protectContextWindow = false)
        val result = assertIs<LruPagingCache<String>>(lru + fifo)
        val fifoLayer = assertIs<FifoPagingCache<String>>(result.wrapped)
        assertEquals(4, fifoLayer.maxSize)
        assertEquals(false, fifoLayer.protectContextWindow)
    }

    @Test
    fun `plus replaces DefaultPagingCache leaf deep in the chain`() {
        val lru = LruPagingCache<String>(maxSize = 10)
        val fifo = FifoPagingCache<String>(maxSize = 5)
        val result = assertIs<LruPagingCache<String>>(lru + fifo)
        val fifoLayer = assertIs<FifoPagingCache<String>>(result.wrapped)
        assertIs<DefaultPagingCache<String>>(fifoLayer.wrapped)
    }

    // ── plus operator: behavioral / eviction tests ────────────────────────────

    @Test
    fun `LRU outer FIFO inner - LRU eviction policy is applied`() {
        val cache = LruPagingCache<String>(maxSize = 2) + FifoPagingCache<String>(maxSize = 10)

        cache.setState(successPage(1), silently = true)
        cache.setState(successPage(2), silently = true)
        cache.getStateOf(1)
        cache.setState(successPage(3), silently = true)

        assertEquals(2, cache.size)
        assertNotNull(cache.getStateOf(1))
        assertNull(cache.getStateOf(2))
        assertNotNull(cache.getStateOf(3))
    }

    @Test
    fun `FIFO outer LRU inner - FIFO eviction policy is applied regardless of access order`() {
        val cache = FifoPagingCache<String>(maxSize = 2) + LruPagingCache<String>(maxSize = 10)

        cache.setState(successPage(1), silently = true)
        cache.setState(successPage(2), silently = true)
        cache.getStateOf(1)
        cache.setState(successPage(3), silently = true)

        assertEquals(2, cache.size)
        assertNull(cache.getStateOf(1))
        assertNotNull(cache.getStateOf(2))
        assertNotNull(cache.getStateOf(3))
    }

    @Test
    fun `LRU outer FIFO inner - both constraints apply when both are tight`() {
        val cache = LruPagingCache<String>(maxSize = 3) + FifoPagingCache<String>(maxSize = 2)

        cache.setState(successPage(1), silently = true)
        cache.setState(successPage(2), silently = true)
        cache.setState(successPage(3), silently = true)

        assertEquals(2, cache.size)
        assertNull(cache.getStateOf(1))
        assertNotNull(cache.getStateOf(2))
        assertNotNull(cache.getStateOf(3))
    }

    @Test
    fun `three-layer LRU plus FIFO plus SlidingWindow - outermost LRU governs eviction`() {
        val cache = LruPagingCache<String>(maxSize = 2) +
                FifoPagingCache<String>(maxSize = 10) +
                SlidingWindowPagingCache<String>()

        val lruLayer = assertIs<LruPagingCache<String>>(cache)
        val fifoLayer = assertIs<FifoPagingCache<String>>(lruLayer.wrapped)
        assertIs<SlidingWindowPagingCache<String>>(fifoLayer.wrapped)

        cache.setState(successPage(1), silently = true)
        cache.setState(successPage(2), silently = true)
        cache.setState(successPage(3), silently = true)

        assertEquals(2, cache.size)
        assertNull(cache.getStateOf(1))
        assertNotNull(cache.getStateOf(2))
        assertNotNull(cache.getStateOf(3))
    }

    @Test
    fun `composed cache size is consistent after multiple evictions`() {
        val cache = LruPagingCache<String>(maxSize = 3) + FifoPagingCache<String>(maxSize = 10)

        for (i in 1..10) {
            cache.setState(successPage(i), silently = true)
        }

        assertEquals(3, cache.size)
        assertNotNull(cache.getStateOf(8))
        assertNotNull(cache.getStateOf(9))
        assertNotNull(cache.getStateOf(10))
    }

    @Test
    fun `release clears composed cache state`() {
        val cache = LruPagingCache<String>(maxSize = 5) + FifoPagingCache<String>(maxSize = 10)

        cache.setState(successPage(1), silently = true)
        cache.setState(successPage(2), silently = true)
        assertEquals(2, cache.size)

        cache.release(silently = true)
        assertEquals(0, cache.size)

        cache.setState(successPage(10), silently = true)
        assertEquals(1, cache.size)
    }

    @Test
    fun `clear clears composed cache state`() {
        val cache = FifoPagingCache<String>(maxSize = 5) + LruPagingCache<String>(maxSize = 10)

        cache.setState(successPage(1), silently = true)
        cache.setState(successPage(2), silently = true)
        assertEquals(2, cache.size)

        cache.clear()
        assertEquals(0, cache.size)
    }
}
