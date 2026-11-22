package com.jamal_aliev.paginator.offset.cache.eviction

import com.jamal_aliev.paginator.core.cache.eviction.CacheEvictionListener
import com.jamal_aliev.paginator.offset.cache.InMemoryPagingCache
import com.jamal_aliev.paginator.offset.cache.PagingCache
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueuedPagingCacheTest {

    private fun createCore(
        maxSize: Int = 3,
        protectContextWindow: Boolean = true
    ): QueuedPagingCache<String> {
        return QueuedPagingCache(
            maxSize = maxSize,
            protectContextWindow = protectContextWindow,
        )
    }

    private fun successPage(
        page: Int,
        data: List<String> = listOf("item_$page")
    ): OffsetPageState.Success<String> {
        return OffsetPageState.Success(page = page, data = data)
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
        val evicted = mutableListOf<OffsetPageState<String>>()
        val core = createCore(maxSize = 2)
        core.evictionListener = CacheEvictionListener { evicted.add(it as OffsetPageState<String>) }

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
            QueuedPagingCache<String>(maxSize = 0)
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
        core.evictionListener = CacheEvictionListener { evicted.add((it as OffsetPageState).page) }

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

    // -- 14. Composition with an inner evicting strategy --

    /** FIFO decorator (outer) over a strict context window strategy (inner, `margin = 0`). */
    private fun createComposition(maxSize: Int): QueuedPagingCache<String> =
        QueuedPagingCache(
            cache = ContextWindowPagingCache(margin = 0),
            maxSize = maxSize,
            protectContextWindow = false,
        )

    /** Moves the context window; the end is set first so the window is never inverted. */
    private fun PagingCache<String>.setContext(start: Int, end: Int) {
        endContextPage = end
        startContextPage = start
    }

    @Test
    fun `re-added page after inner eviction is evicted after the older surviving page`() {
        val evicted = mutableListOf<Int>()
        val core = createComposition(maxSize = 2)
        core.evictionListener = CacheEvictionListener { evicted.add((it as OffsetPageState).page) }

        // Context 1..2: pages 1 and 2 are inserted.
        core.setContext(1, 2)
        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)

        // Context 2..2: the inner window strategy drops page 1 behind the FIFO decorator's back.
        core.setContext(2, 2)
        core.setState(successPage(2), silently = true)
        assertNull(core.peekStateOf(1))

        // Context 1..2: page 1 is inserted again, so it is newer than page 2.
        core.setContext(1, 2)
        core.setState(successPage(1), silently = true)

        // Context 1..3: page 3 overflows maxSize, the oldest surviving page is 2, not 1.
        core.setContext(1, 3)
        core.setState(successPage(3), silently = true)

        assertEquals(2, core.size)
        assertNull(core.getStateOf(2))
        assertNotNull(core.getStateOf(1))
        assertNotNull(core.getStateOf(3))
        assertEquals(listOf(2), evicted)
    }

    @Test
    fun `insertion order stays bounded when inner strategy evicts`() {
        val core = createComposition(maxSize = 5)

        for (i in 1..1000) {
            core.setContext(i, i)
            core.setState(successPage(i), silently = true)
        }

        assertEquals(1, core.size)
        assertTrue(core.trackedEntryCount < 50, "tracked ${core.trackedEntryCount} entries")
    }

    @Test
    fun `page re-added many times keeps fifo order of surviving pages`() {
        val evicted = mutableListOf<Int>()
        val core = createComposition(maxSize = 2)
        core.evictionListener = CacheEvictionListener { evicted.add((it as OffsetPageState).page) }

        // Round 1: inner eviction of 1, then 1 is added again, then 3 overflows -> evicts 2.
        core.setContext(1, 2)
        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)
        core.setContext(2, 2)
        core.setState(successPage(2), silently = true)
        core.setContext(1, 2)
        core.setState(successPage(1), silently = true)
        core.setContext(1, 3)
        core.setState(successPage(3), silently = true)
        assertEquals(listOf(2), evicted)

        // Round 2: inner eviction of 1 again, 1 re-added, 4 overflows -> evicts 3 (older than 1).
        core.setContext(3, 3)
        core.setState(successPage(3), silently = true)
        assertNull(core.peekStateOf(1))
        core.setContext(1, 3)
        core.setState(successPage(1), silently = true)
        core.setContext(1, 4)
        core.setState(successPage(4), silently = true)
        assertEquals(listOf(2, 3), evicted)

        // Round 3: same again -> evicts 4.
        core.setContext(4, 4)
        core.setState(successPage(4), silently = true)
        assertNull(core.peekStateOf(1))
        core.setContext(1, 4)
        core.setState(successPage(1), silently = true)
        core.setContext(1, 5)
        core.setState(successPage(5), silently = true)
        assertEquals(listOf(2, 3, 4), evicted)

        assertEquals(2, core.size)
        assertNotNull(core.peekStateOf(1))
        assertNotNull(core.peekStateOf(5))
    }

    @Test
    fun `removeFromCache of a re-added page leaves no entry behind`() {
        val core = createComposition(maxSize = 5)

        core.setContext(1, 2)
        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)

        // The inner window strategy drops page 1, then page 1 is added again.
        core.setContext(2, 2)
        core.setState(successPage(2), silently = true)
        assertNull(core.peekStateOf(1))
        core.setContext(1, 2)
        core.setState(successPage(1), silently = true)
        assertEquals(2, core.size)

        // Removing it through the outer decorator must not leave a second entry for page 1.
        assertNotNull(core.removeFromCache(1))
        assertEquals(1, core.size)
        assertEquals(core.size, core.trackedEntryCount)
    }

    @Test
    fun `compaction keeps the insertion order of surviving pages`() {
        val evicted = mutableListOf<Int>()
        val leaf = InMemoryPagingCache<String>()
        val core = QueuedPagingCache(
            cache = leaf,
            maxSize = 4,
            protectContextWindow = false,
            evictionListener = CacheEvictionListener { evicted.add((it as OffsetPageState).page) },
        )

        // The survivors 3, 2, 1 are inserted in this order, not in ascending order.
        core.setState(successPage(3), silently = true)
        core.setState(successPage(2), silently = true)
        core.setState(successPage(1), silently = true)

        // Churn pages are removed behind the decorator's back and leave stale entries: far more
        // than the compaction limit, so the compaction runs while the survivors are queued.
        for (i in 100 until 140) {
            core.setState(successPage(i), silently = true)
            leaf.removeFromCache(i)
        }
        assertEquals(listOf(1, 2, 3), core.pages)
        assertTrue(core.trackedEntryCount < 50, "tracked ${core.trackedEntryCount} entries")
        assertEquals(emptyList(), evicted)

        // Overflow: the oldest insertion is page 3, not the lowest page 1.
        core.setState(successPage(4), silently = true)
        core.setState(successPage(5), silently = true)

        assertEquals(listOf(3), evicted)
        assertEquals(listOf(1, 2, 4, 5), core.pages)
    }
}
