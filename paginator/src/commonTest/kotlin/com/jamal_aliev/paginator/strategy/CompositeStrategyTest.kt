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
        // DefaultPagingCache has no wrap() method; the plus operator returns the inner unchanged.
        val leaf = DefaultPagingCache<String>()
        val inner = LruPagingCache<String>(maxSize = 5)
        assertSame(inner, leaf + inner)
    }

    // ── WrappablePagingCache: wrapped property ─────────────────────────────────

    @Test
    fun `LruPagingCache wrapped returns the inner cache`() {
        val inner = DefaultPagingCache<String>()
        val lru = LruPagingCache<String>(cache = inner, maxSize = 5)
        assertSame(inner, lru.wrapped)
    }

    @Test
    fun `FifoPagingCache wrapped returns the inner cache`() {
        val inner = DefaultPagingCache<String>()
        val fifo = FifoPagingCache<String>(cache = inner, maxSize = 5)
        assertSame(inner, fifo.wrapped)
    }

    @Test
    fun `TtlPagingCache wrapped returns the inner cache`() {
        val inner = DefaultPagingCache<String>()
        val ttl = TtlPagingCache<String>(cache = inner, ttl = 1.hours)
        assertSame(inner, ttl.wrapped)
    }

    @Test
    fun `SlidingWindowPagingCache wrapped returns the inner cache`() {
        val inner = DefaultPagingCache<String>()
        val sliding = SlidingWindowPagingCache<String>(cache = inner)
        assertSame(inner, sliding.wrapped)
    }

    // ── WrappablePagingCache: wrap() preserves config ──────────────────────────

    @Test
    fun `LruPagingCache wrap creates new instance with new inner but same config`() {
        val lru = LruPagingCache<String>(maxSize = 7, protectContextWindow = false)
        val newInner = FifoPagingCache<String>(maxSize = 3)
        val result = lru.wrap(newInner)
        assertSame(newInner, result.wrapped)
        assertEquals(7, result.maxSize)
        assertEquals(false, result.protectContextWindow)
    }

    @Test
    fun `FifoPagingCache wrap creates new instance with new inner but same config`() {
        val fifo = FifoPagingCache<String>(maxSize = 4, protectContextWindow = false)
        val newInner = DefaultPagingCache<String>()
        val result = fifo.wrap(newInner)
        assertSame(newInner, result.wrapped)
        assertEquals(4, result.maxSize)
        assertEquals(false, result.protectContextWindow)
    }

    @Test
    fun `TtlPagingCache wrap creates new instance with new inner but same config`() {
        val ttl = TtlPagingCache<String>(ttl = 2.hours, refreshOnAccess = true, protectContextWindow = false)
        val newInner = DefaultPagingCache<String>()
        val result = ttl.wrap(newInner)
        assertSame(newInner, result.wrapped)
        assertEquals(2.hours, result.ttl)
        assertEquals(true, result.refreshOnAccess)
        assertEquals(false, result.protectContextWindow)
    }

    @Test
    fun `SlidingWindowPagingCache wrap creates new instance with new inner but same config`() {
        val sliding = SlidingWindowPagingCache<String>(margin = 3)
        val newInner = DefaultPagingCache<String>()
        val result = sliding.wrap(newInner)
        assertSame(newInner, result.wrapped)
        assertEquals(3, result.margin)
    }

    @Test
    fun `wrap does not mutate the original strategy`() {
        val originalInner = DefaultPagingCache<String>()
        val lru = LruPagingCache<String>(cache = originalInner, maxSize = 5)
        val newInner = FifoPagingCache<String>(maxSize = 3)
        lru.wrap(newInner) // new instance, not mutating lru
        assertSame(originalInner, lru.wrapped)
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
        // lru wraps DefaultPagingCache; after plus, it wraps fifo (which wraps DefaultPagingCache)
        val result = assertIs<LruPagingCache<String>>(lru + fifo)
        val fifoLayer = assertIs<FifoPagingCache<String>>(result.wrapped)
        // The FIFO's inner should be a fresh DefaultPagingCache (the one from new FifoPagingCache())
        assertIs<DefaultPagingCache<String>>(fifoLayer.wrapped)
    }

    // ── plus operator: behavioral / eviction tests ────────────────────────────

    @Test
    fun `LRU outer FIFO inner - LRU eviction policy is applied`() {
        // LRU(maxSize=2) wraps FIFO(maxSize=10)
        val cache = LruPagingCache<String>(maxSize = 2) + FifoPagingCache<String>(maxSize = 10)

        cache.setState(successPage(1), silently = true)
        cache.setState(successPage(2), silently = true)
        // Access page 1 to make it the most-recently-used
        cache.getStateOf(1)
        // Add page 3 → LRU should evict page 2 (least recently used)
        cache.setState(successPage(3), silently = true)

        assertEquals(2, cache.size)
        assertNotNull(cache.getStateOf(1))
        assertNull(cache.getStateOf(2))
        assertNotNull(cache.getStateOf(3))
    }

    @Test
    fun `FIFO outer LRU inner - FIFO eviction policy is applied regardless of access order`() {
        // FIFO(maxSize=2) wraps LRU(maxSize=10)
        val cache = FifoPagingCache<String>(maxSize = 2) + LruPagingCache<String>(maxSize = 10)

        cache.setState(successPage(1), silently = true)
        cache.setState(successPage(2), silently = true)
        // Access page 1 to make it MRU — but FIFO ignores access order
        cache.getStateOf(1)
        // Add page 3 → FIFO should evict page 1 (first inserted), not page 2
        cache.setState(successPage(3), silently = true)

        assertEquals(2, cache.size)
        assertNull(cache.getStateOf(1))
        assertNotNull(cache.getStateOf(2))
        assertNotNull(cache.getStateOf(3))
    }

    @Test
    fun `LRU outer FIFO inner - both constraints apply when both are tight`() {
        // LRU(maxSize=3) wraps FIFO(maxSize=2)
        // FIFO inner has the tighter limit, its eviction runs first inside setState
        val cache = LruPagingCache<String>(maxSize = 3) + FifoPagingCache<String>(maxSize = 2)

        cache.setState(successPage(1), silently = true)
        cache.setState(successPage(2), silently = true)
        // Adding page 3: FIFO (inner, maxSize=2) evicts page 1; then LRU checks size=2 ≤ 3, ok
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

        // Verify structure
        val lruLayer = assertIs<LruPagingCache<String>>(cache)
        val fifoLayer = assertIs<FifoPagingCache<String>>(lruLayer.wrapped)
        assertIs<SlidingWindowPagingCache<String>>(fifoLayer.wrapped)

        cache.setState(successPage(1), silently = true)
        cache.setState(successPage(2), silently = true)
        // Add page 3 → LRU (maxSize=2) evicts page 1
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

        // LRU maxSize=3 means only the 3 most-recently-used pages remain
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

        // Should be fully functional after release
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
