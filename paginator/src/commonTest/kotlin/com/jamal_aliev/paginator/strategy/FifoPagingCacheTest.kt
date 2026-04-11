package com.jamal_aliev.paginator.strategy

import com.jamal_aliev.paginator.page.PageState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FifoPagingCacheTest {

    private fun createCore(maxSize: Int = 3, protectContextWindow: Boolean = true): FifoPagingCache<String> {
        return FifoPagingCache(
            maxSize = maxSize,
            protectContextWindow = protectContextWindow,
        )
    }

    private fun successPage(page: Int, data: List<String> = listOf("item_$page")): PageState.SuccessPage<String> {
        return PageState.SuccessPage(page = page, data = data)
    }

    // -- 1. Eviction in insertion order --

    @Test
    fun `evicts oldest inserted page when maxSize exceeded`() {
        val core = createCore(maxSize = 3)
        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)
        core.setState(successPage(3), silently = true)

        core.setState(successPage(4), silently = true)
        assertEquals(3, core.size)
        assertNull(core.getStateOf(1)) // first in = first out
        assertNotNull(core.getStateOf(2))
        assertNotNull(core.getStateOf(3))
        assertNotNull(core.getStateOf(4))
    }

    // -- 2. getStateOf does NOT change eviction order --

    @Test
    fun `getStateOf does not change eviction order`() {
        val core = createCore(maxSize = 3)
        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)
        core.setState(successPage(3), silently = true)

        // Access page 1 -- should NOT change FIFO order
        core.getStateOf(1)

        // Page 1 should still be evicted first (FIFO)
        core.setState(successPage(4), silently = true)
        assertNull(core.getStateOf(1))
    }

    // -- 3. Updating page does NOT change FIFO position --

    @Test
    fun `updating page via setState does not change FIFO position`() {
        val core = createCore(maxSize = 3)
        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)
        core.setState(successPage(3), silently = true)

        // Update page 1 -- should NOT move it to end of FIFO
        core.setState(successPage(1, listOf("updated")), silently = true)

        // Page 1 is still first in FIFO -- should be evicted
        core.setState(successPage(4), silently = true)
        assertNull(core.getStateOf(1))
    }

    // -- 4. Context window protection works --

    @Test
    fun `protectContextWindow true skips pages in context window`() {
        val core = createCore(maxSize = 2, protectContextWindow = true)
        core.setState(successPage(1, List(5) { "i$it" }), silently = true)
        core.setState(successPage(2, List(5) { "i$it" }), silently = true)

        core.startContextPage = 1
        core.endContextPage = 2

        core.setState(successPage(3), silently = true)
        // Cannot evict pages 1 or 2 -- all protected
        assertEquals(3, core.size)
    }

    @Test
    fun `protectContextWindow false allows evicting context pages`() {
        val core = createCore(maxSize = 2, protectContextWindow = false)
        core.setState(successPage(1, List(5) { "i$it" }), silently = true)
        core.setState(successPage(2, List(5) { "i$it" }), silently = true)

        core.startContextPage = 1
        core.endContextPage = 2

        core.setState(successPage(3), silently = true)
        assertEquals(2, core.size)
        assertNull(core.getStateOf(1))
    }

    // -- 5. maxSize respected --

    @Test
    fun `cache size never exceeds maxSize`() {
        val core = createCore(maxSize = 2)
        for (i in 1..10) {
            core.setState(successPage(i), silently = true)
        }
        assertEquals(2, core.size)
        assertNotNull(core.getStateOf(9))
        assertNotNull(core.getStateOf(10))
    }

    // -- 6. evictionListener called correctly --

    @Test
    fun `evictionListener is called with evicted page state`() {
        val evicted = mutableListOf<PageState<String>>()
        val core = createCore(maxSize = 2)
        core.evictionListener = CacheEvictionListener { evicted.add(it) }

        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)
        assertTrue(evicted.isEmpty())

        core.setState(successPage(3), silently = true)
        assertEquals(1, evicted.size)
        assertEquals(1, evicted[0].page)
    }

    // -- 7. release() clears tracking --

    @Test
    fun `release clears insertion tracking`() {
        val core = createCore(maxSize = 3)
        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)
        core.release(silently = true)

        core.setState(successPage(10), silently = true)
        core.setState(successPage(11), silently = true)
        core.setState(successPage(12), silently = true)

        // Page 10 should be evicted next (first after release)
        core.setState(successPage(13), silently = true)
        assertNull(core.getStateOf(10))
    }

    // -- 8. clear() clears tracking --

    @Test
    fun `clear clears insertion tracking`() {
        val core = createCore(maxSize = 2)
        core.setState(successPage(1), silently = true)
        core.clear()

        core.setState(successPage(5), silently = true)
        core.setState(successPage(6), silently = true)
        core.setState(successPage(7), silently = true)
        assertEquals(2, core.size)
        assertNull(core.getStateOf(5))
    }

    // -- 9. maxSize = 1 --

    @Test
    fun `maxSize 1 keeps only the latest page`() {
        val core = createCore(maxSize = 1)
        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)
        assertEquals(1, core.size)
        assertNull(core.getStateOf(1))
        assertNotNull(core.getStateOf(2))
    }

    // -- 10. maxSize validation --

    @Test
    fun `maxSize 0 throws`() {
        assertFailsWith<IllegalArgumentException> {
            FifoPagingCache<String>(maxSize = 0)
        }
    }

    // -- 11. removeFromCache updates tracking --

    @Test
    fun `removeFromCache updates insertion tracking`() {
        val core = createCore(maxSize = 3)
        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)
        core.setState(successPage(3), silently = true)

        core.removeFromCache(1)

        core.setState(successPage(4), silently = true)
        core.setState(successPage(5), silently = true)
        assertEquals(3, core.size)
        // Page 2 should be evicted (oldest remaining in FIFO)
        assertNull(core.getStateOf(2))
    }

    // -- 12. Multiple evictions tracked --

    @Test
    fun `evictionListener called for each eviction`() {
        val evicted = mutableListOf<Int>()
        val core = createCore(maxSize = 2)
        core.evictionListener = CacheEvictionListener { evicted.add(it.page) }

        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)
        core.setState(successPage(3), silently = true) // evicts 1
        core.setState(successPage(4), silently = true) // evicts 2

        assertEquals(listOf(1, 2), evicted)
    }

    // -- 13. Re-adding evicted page gets new FIFO position --

    @Test
    fun `re-adding evicted page inserts at end of FIFO`() {
        val core = createCore(maxSize = 2)
        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)

        // Evict page 1
        core.setState(successPage(3), silently = true)
        assertNull(core.getStateOf(1))

        // Re-add page 1 -- it should be at the end of FIFO now
        core.setState(successPage(1), silently = true)
        assertEquals(2, core.size)
        // Page 2 was evicted (oldest in FIFO)
        assertNull(core.getStateOf(2))
        assertNotNull(core.getStateOf(1))
        assertNotNull(core.getStateOf(3))
    }
}
