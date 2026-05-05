package com.jamal_aliev.paginator.cache.eviction

import com.jamal_aliev.paginator.cache.InMemoryPagingCache
import com.jamal_aliev.paginator.cache.PagingCache

import com.jamal_aliev.paginator.extension.plus
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

    // ── ChainablePagingCache: interface membership ─────────────────────────────
    // Compile-time assertions: if any strategy stops implementing
    // ChainablePagingCache the assignments below will fail to compile.

    @Test
    fun `all four built-in strategies implement ChainablePagingCache`() {
        @Suppress("UNUSED_VARIABLE") val lru: ChainablePagingCache<String> =
            MostRecentPagingCache(maxSize = 1)
        @Suppress("UNUSED_VARIABLE") val fifo: ChainablePagingCache<String> =
            QueuedPagingCache(maxSize = 1)
        @Suppress("UNUSED_VARIABLE") val ttl: ChainablePagingCache<String> =
            TimeLimitedPagingCache(ttl = 1.hours)
        @Suppress("UNUSED_VARIABLE") val sliding: ChainablePagingCache<String> =
            ContextWindowPagingCache()
    }

    @Test
    fun `InMemoryPagingCache is not a ChainablePagingCache - plus returns inner directly`() {
        val leaf = InMemoryPagingCache<String>()
        val inner = MostRecentPagingCache<String>(maxSize = 5)
        assertSame(inner, leaf + inner)
    }

    // ── ChainablePagingCache: replaceLeaf() ────────────────────────────────────

    @Test
    fun `MostRecentPagingCache replaceLeaf preserves config and activates new leaf`() {
        val lru = MostRecentPagingCache<String>(maxSize = 7, protectContextWindow = false)
        // Replace Default leaf with QueuedPagingCache(maxSize=3) — inner FIFO now limits size to 3
        val result = lru.replaceLeaf(QueuedPagingCache<String>(maxSize = 3))
        assertEquals(7, result.maxSize)
        assertEquals(false, result.protectContextWindow)
        repeat(4) { result.setState(successPage(it + 1), silently = true) }
        assertEquals(3, result.size) // inner FIFO(maxSize=3) evicted one
    }

    @Test
    fun `QueuedPagingCache replaceLeaf preserves config and activates new leaf`() {
        val fifo = QueuedPagingCache<String>(maxSize = 4, protectContextWindow = false)
        // Replace Default leaf with MostRecentPagingCache(maxSize=2) — inner LRU limits size to 2
        val result = fifo.replaceLeaf(MostRecentPagingCache<String>(maxSize = 2))
        assertEquals(4, result.maxSize)
        assertEquals(false, result.protectContextWindow)
        repeat(3) { result.setState(successPage(it + 1), silently = true) }
        assertEquals(2, result.size) // inner LRU(maxSize=2) evicted one
    }

    @Test
    fun `TimeLimitedPagingCache replaceLeaf preserves config`() {
        val ttl = TimeLimitedPagingCache<String>(
            ttl = 2.hours,
            refreshOnAccess = true,
            protectContextWindow = false
        )
        val result = ttl.replaceLeaf(InMemoryPagingCache())
        assertEquals(2.hours, result.ttl)
        assertEquals(true, result.refreshOnAccess)
        assertEquals(false, result.protectContextWindow)
    }

    @Test
    fun `ContextWindowPagingCache replaceLeaf preserves config`() {
        val sliding = ContextWindowPagingCache<String>(margin = 3)
        val result = sliding.replaceLeaf(InMemoryPagingCache())
        assertEquals(3, result.margin)
    }

    @Test
    fun `replaceLeaf on deep chain inserts at the bottom`() {
        // chain: LRU(10) → FIFO(10) → Default
        // after replaceLeaf(FIFO(2)): LRU(10) → FIFO(10) → FIFO(2) → Default
        // inner FIFO(2) evicts after 2 items, so 3 inserts → size=2
        val chain =
            MostRecentPagingCache<String>(maxSize = 10) + QueuedPagingCache<String>(maxSize = 10)
        val result = assertIs<ChainablePagingCache<String>>(chain)
            .replaceLeaf(QueuedPagingCache<String>(maxSize = 2))
        repeat(3) { result.setState(successPage(it + 1), silently = true) }
        assertEquals(2, result.size)
    }

    @Test
    fun `replaceLeaf does not mutate the original strategy`() {
        val lru = MostRecentPagingCache<String>(maxSize = 5)
        lru.replaceLeaf(QueuedPagingCache<String>(maxSize = 2))
        // original lru still has Default underneath — not limited by inner QueuedPagingCache(maxSize=2)
        repeat(5) { lru.setState(successPage(it + 1), silently = true) }
        assertEquals(5, lru.size)
    }

    // ── plus operator: type and config tests ──────────────────────────────────

    @Test
    fun `plus on custom non-ChainablePagingCache returns inner directly`() {
        val customLeaf = object : PagingCache<String> by InMemoryPagingCache() {}
        val fifo = QueuedPagingCache<String>(maxSize = 3)
        assertSame(fifo, customLeaf + fifo)
    }

    @Test
    fun `LRU plus FIFO result is MostRecentPagingCache`() {
        assertIs<MostRecentPagingCache<String>>(
            MostRecentPagingCache<String>(maxSize = 10) + QueuedPagingCache<String>(maxSize = 5)
        )
    }

    @Test
    fun `FIFO plus SlidingWindow result is QueuedPagingCache`() {
        assertIs<QueuedPagingCache<String>>(
            QueuedPagingCache<String>(maxSize = 5) + ContextWindowPagingCache<String>()
        )
    }

    @Test
    fun `plus preserves outer strategy config`() {
        val result = assertIs<MostRecentPagingCache<String>>(
            MostRecentPagingCache<String>(
                maxSize = 7,
                protectContextWindow = false
            ) + QueuedPagingCache<String>(maxSize = 3)
        )
        assertEquals(7, result.maxSize)
        assertEquals(false, result.protectContextWindow)
    }

    @Test
    fun `plus preserves inner strategy config - inner maxSize governs eviction`() {
        // LRU(10) + FIFO(4): inner FIFO(4) limits to 4, outer LRU(10) does not interfere
        val cache =
            MostRecentPagingCache<String>(maxSize = 10) + QueuedPagingCache<String>(maxSize = 4)
        repeat(5) { cache.setState(successPage(it + 1), silently = true) }
        assertEquals(4, cache.size)
    }

    // ── plus operator: behavioral / eviction tests ────────────────────────────

    @Test
    fun `LRU outer FIFO inner - LRU eviction policy is applied`() {
        val cache =
            MostRecentPagingCache<String>(maxSize = 2) + QueuedPagingCache<String>(maxSize = 10)

        cache.setState(successPage(1), silently = true)
        cache.setState(successPage(2), silently = true)
        cache.getStateOf(1) // make page 1 most recently used
        cache.setState(successPage(3), silently = true) // LRU evicts page 2

        assertEquals(2, cache.size)
        assertNotNull(cache.getStateOf(1))
        assertNull(cache.getStateOf(2))
        assertNotNull(cache.getStateOf(3))
    }

    @Test
    fun `FIFO outer LRU inner - FIFO eviction policy is applied regardless of access order`() {
        val cache =
            QueuedPagingCache<String>(maxSize = 2) + MostRecentPagingCache<String>(maxSize = 10)

        cache.setState(successPage(1), silently = true)
        cache.setState(successPage(2), silently = true)
        cache.getStateOf(1) // access page 1 — but FIFO ignores access order
        cache.setState(successPage(3), silently = true) // FIFO evicts page 1 (oldest inserted)

        assertEquals(2, cache.size)
        assertNull(cache.getStateOf(1))
        assertNotNull(cache.getStateOf(2))
        assertNotNull(cache.getStateOf(3))
    }

    @Test
    fun `LRU outer FIFO inner - both constraints apply when both are tight`() {
        // FIFO inner has tighter limit — evicts first inside setState
        val cache =
            MostRecentPagingCache<String>(maxSize = 3) + QueuedPagingCache<String>(maxSize = 2)

        cache.setState(successPage(1), silently = true)
        cache.setState(successPage(2), silently = true)
        cache.setState(successPage(3), silently = true) // FIFO(2) evicts page 1

        assertEquals(2, cache.size)
        assertNull(cache.getStateOf(1))
        assertNotNull(cache.getStateOf(2))
        assertNotNull(cache.getStateOf(3))
    }

    @Test
    fun `plus is left-associative - outermost strategy governs eviction`() {
        // (LRU(2) + FIFO(10)) + SlidingWindow = LRU(2) outermost → LRU eviction applies
        val cache = MostRecentPagingCache<String>(maxSize = 2) +
                QueuedPagingCache<String>(maxSize = 10) +
                ContextWindowPagingCache<String>()

        cache.setState(successPage(1), silently = true)
        cache.setState(successPage(2), silently = true)
        cache.setState(successPage(3), silently = true) // LRU(2) evicts page 1

        assertEquals(2, cache.size)
        assertNull(cache.getStateOf(1))
        assertNotNull(cache.getStateOf(2))
        assertNotNull(cache.getStateOf(3))
    }

    @Test
    fun `composed cache size is consistent after multiple evictions`() {
        val cache =
            MostRecentPagingCache<String>(maxSize = 3) + QueuedPagingCache<String>(maxSize = 10)

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
        val cache =
            MostRecentPagingCache<String>(maxSize = 5) + QueuedPagingCache<String>(maxSize = 10)

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
        val cache =
            QueuedPagingCache<String>(maxSize = 5) + MostRecentPagingCache<String>(maxSize = 10)

        cache.setState(successPage(1), silently = true)
        cache.setState(successPage(2), silently = true)
        assertEquals(2, cache.size)

        cache.clear()
        assertEquals(0, cache.size)
    }
}
