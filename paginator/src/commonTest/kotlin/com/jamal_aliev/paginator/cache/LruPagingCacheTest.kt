package com.jamal_aliev.paginator.cache

import com.jamal_aliev.paginator.page.PageState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LruPagingCacheTest {

    private fun createCore(maxSize: Int = 3, protectContextWindow: Boolean = true): LruPagingCache<String> {
        return LruPagingCache(
            maxSize = maxSize,
            protectContextWindow = protectContextWindow,
        )
    }

    private fun successPage(page: Int, data: List<String> = listOf("item_$page")): PageState.SuccessPage<String> {
        return PageState.SuccessPage(page = page, data = data)
    }

    // -- 1. Eviction in LRU order --

    @Test
    fun `evicts least recently used page when maxSize exceeded`() {
        val core = createCore(maxSize = 3)
        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)
        core.setState(successPage(3), silently = true)

        // All 3 pages present
        assertEquals(3, core.size)

        // Add page 4 -- page 1 (oldest) should be evicted
        core.setState(successPage(4), silently = true)
        assertEquals(3, core.size)
        assertNull(core.getStateOf(1))
        assertNotNull(core.getStateOf(2))
        assertNotNull(core.getStateOf(3))
        assertNotNull(core.getStateOf(4))
    }

    // -- 2. getStateOf updates access order --

    @Test
    fun `getStateOf updates access order so page is not evicted`() {
        val core = createCore(maxSize = 3)
        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)
        core.setState(successPage(3), silently = true)

        // Access page 1 -- makes it most recent
        core.getStateOf(1)

        // Add page 4 -- page 2 (now oldest) should be evicted, not page 1
        core.setState(successPage(4), silently = true)
        assertNotNull(core.getStateOf(1))
        assertNull(core.getStateOf(2))
    }

    // -- 3. getElement updates access order --

    @Test
    fun `getElement updates access order`() {
        val core = createCore(maxSize = 3)
        core.setState(successPage(1, listOf("a", "b")), silently = true)
        core.setState(successPage(2, listOf("c")), silently = true)
        core.setState(successPage(3, listOf("d")), silently = true)

        // Access element from page 1
        val element = core.getElement(1, 0)
        assertEquals("a", element)

        // Add page 4 -- page 2 should be evicted (page 1 was refreshed by getElement)
        core.setState(successPage(4), silently = true)
        assertNotNull(core.getStateOf(1))
        assertNull(core.getStateOf(2))
    }

    // -- 4. Context window protection enabled --

    @Test
    fun `protectContextWindow true skips pages in context window`() {
        val core = createCore(maxSize = 2, protectContextWindow = true)
        core.setState(successPage(1, List(5) { "i$it" }), silently = true)
        core.setState(successPage(2, List(5) { "i$it" }), silently = true)

        // Simulate context window covering pages 1-2
        core.startContextPage = 1
        core.endContextPage = 2

        // Add page 3 -- should NOT evict pages 1 or 2 (protected)
        core.setState(successPage(3), silently = true)

        // Cache exceeds maxSize because all eviction candidates are protected
        assertEquals(3, core.size)
        assertNotNull(core.getStateOf(1))
        assertNotNull(core.getStateOf(2))
        assertNotNull(core.getStateOf(3))
    }

    // -- 5. Context window protection disabled --

    @Test
    fun `protectContextWindow false allows evicting context pages`() {
        val core = createCore(maxSize = 2, protectContextWindow = false)
        core.setState(successPage(1, List(5) { "i$it" }), silently = true)
        core.setState(successPage(2, List(5) { "i$it" }), silently = true)

        core.startContextPage = 1
        core.endContextPage = 2

        // Add page 3 -- page 1 should be evicted even though in context
        core.setState(successPage(3), silently = true)
        assertEquals(2, core.size)
        assertNull(core.getStateOf(1))
    }

    // -- 6. maxSize strictly respected --

    @Test
    fun `cache size never exceeds maxSize after setState`() {
        val core = createCore(maxSize = 2)
        for (i in 1..10) {
            core.setState(successPage(i), silently = true)
        }
        assertEquals(2, core.size)
        // Only the last 2 pages should remain
        assertNotNull(core.getStateOf(9))
        assertNotNull(core.getStateOf(10))
    }

    // -- 7. evictionListener receives correct PageState --

    @Test
    fun `evictionListener is called with correct page state`() {
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

    // -- 8. release() clears tracking --

    @Test
    fun `release clears access tracking`() {
        val core = createCore(maxSize = 3)
        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)
        core.setState(successPage(3), silently = true)

        core.release(silently = true)
        assertEquals(0, core.size)

        // After release, adding pages should work fresh
        core.setState(successPage(10), silently = true)
        core.setState(successPage(11), silently = true)
        core.setState(successPage(12), silently = true)
        assertEquals(3, core.size)

        // Page 10 should be evicted next (oldest after release)
        core.setState(successPage(13), silently = true)
        assertNull(core.getStateOf(10))
    }

    // -- 9. clear() clears tracking --

    @Test
    fun `clear clears access tracking`() {
        val core = createCore(maxSize = 2)
        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)
        core.clear()
        assertEquals(0, core.size)

        // Fresh after clear
        core.setState(successPage(5), silently = true)
        core.setState(successPage(6), silently = true)
        core.setState(successPage(7), silently = true)
        assertEquals(2, core.size)
        assertNull(core.getStateOf(5))
    }

    // -- 10. maxSize = 1 --

    @Test
    fun `maxSize 1 keeps only the latest page`() {
        val core = createCore(maxSize = 1)
        core.setState(successPage(1), silently = true)
        assertEquals(1, core.size)

        core.setState(successPage(2), silently = true)
        assertEquals(1, core.size)
        assertNull(core.getStateOf(1))
        assertNotNull(core.getStateOf(2))

        core.setState(successPage(3), silently = true)
        assertEquals(1, core.size)
        assertNull(core.getStateOf(2))
        assertNotNull(core.getStateOf(3))
    }

    // -- 11. All pages in protected range --

    @Test
    fun `all pages in protected range allows cache to exceed maxSize`() {
        val core = createCore(maxSize = 2, protectContextWindow = true)
        core.setState(successPage(1, List(5) { "i$it" }), silently = true)
        core.setState(successPage(2, List(5) { "i$it" }), silently = true)

        core.startContextPage = 1
        core.endContextPage = 5

        core.setState(successPage(3, List(5) { "i$it" }), silently = true)
        core.setState(successPage(4, List(5) { "i$it" }), silently = true)

        // All pages are in context window, so none can be evicted
        assertEquals(4, core.size)
    }

    // -- 12. Re-adding evicted page works --

    @Test
    fun `re-adding previously evicted page works correctly`() {
        val core = createCore(maxSize = 2)
        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)

        // Evict page 1
        core.setState(successPage(3), silently = true)
        assertNull(core.getStateOf(1))

        // Re-add page 1
        core.setState(successPage(1), silently = true)
        assertEquals(2, core.size)
        assertNotNull(core.getStateOf(1))
        // Page 2 should be evicted (oldest among 2, 3)
        assertNull(core.getStateOf(2))
    }

    // -- 13. removeFromCache removes from tracking --

    @Test
    fun `removeFromCache updates tracking`() {
        val core = createCore(maxSize = 3)
        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)
        core.setState(successPage(3), silently = true)

        // Manually remove page 1
        core.removeFromCache(1)
        assertEquals(2, core.size)

        // Add two more pages -- should work without issues
        core.setState(successPage(4), silently = true)
        core.setState(successPage(5), silently = true)
        assertEquals(3, core.size)

        // Page 2 should be evicted (oldest remaining)
        core.setState(successPage(6), silently = true)
        assertNull(core.getStateOf(2))
    }

    // -- 14. maxSize validation --

    @Test
    fun `maxSize 0 throws`() {
        assertFailsWith<IllegalArgumentException> {
            LruPagingCache<String>(maxSize = 0)
        }
    }

    @Test
    fun `negative maxSize throws`() {
        assertFailsWith<IllegalArgumentException> {
            LruPagingCache<String>(maxSize = -1)
        }
    }

    // -- 15. Updating existing page refreshes access order --

    @Test
    fun `updating existing page via setState refreshes access order`() {
        val core = createCore(maxSize = 3)
        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)
        core.setState(successPage(3), silently = true)

        // Update page 1 (re-setState)
        core.setState(successPage(1, listOf("updated")), silently = true)

        // Add page 4 -- page 2 should be evicted (1 was refreshed)
        core.setState(successPage(4), silently = true)
        assertNotNull(core.getStateOf(1))
        assertNull(core.getStateOf(2))
    }

    // -- 16. Multiple evictions in one setState --

    @Test
    fun `evictionListener called for each evicted page`() {
        val evicted = mutableListOf<Int>()
        val core = createCore(maxSize = 2)
        core.evictionListener = CacheEvictionListener { evicted.add(it.page) }

        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)
        core.setState(successPage(3), silently = true) // evicts 1
        core.setState(successPage(4), silently = true) // evicts 2

        assertEquals(listOf(1, 2), evicted)
    }

    // -- 17. getStateOf for non-existent page returns null without side effects --

    @Test
    fun `getStateOf for missing page returns null`() {
        val core = createCore(maxSize = 3)
        assertNull(core.getStateOf(999))
    }

    // -- 18. getElement for non-existent page returns null --

    @Test
    fun `getElement for missing page returns null`() {
        val core = createCore(maxSize = 3)
        assertNull(core.getElement(999, 0))
    }
}
