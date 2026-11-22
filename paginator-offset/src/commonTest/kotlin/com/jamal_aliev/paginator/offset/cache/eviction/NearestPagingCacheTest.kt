package com.jamal_aliev.paginator.offset.cache.eviction

import com.jamal_aliev.paginator.core.cache.eviction.CacheEvictionListener
import com.jamal_aliev.paginator.offset.cache.PagingCache
import com.jamal_aliev.paginator.offset.extension.plus
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

class NearestPagingCacheTest {

    private val evicted = mutableListOf<Int>()

    private val listener = CacheEvictionListener<String> { evicted.add((it as OffsetPageState).page) }

    private fun createCache(maxSize: Int = 3): NearestPagingCache<String> =
        NearestPagingCache(maxSize = maxSize, evictionListener = listener)

    private fun successPage(
        page: Int,
        data: List<String> = listOf("item_$page"),
    ): OffsetPageState<String> = OffsetPageState.Success(page = page, data = data)

    private fun emptyPage(page: Int): OffsetPageState<String> =
        OffsetPageState.Success(page = page, data = emptyList())

    private fun progressPage(page: Int): OffsetPageState<String> =
        OffsetPageState.Progress(page = page, data = emptyList())

    private fun errorPage(page: Int): OffsetPageState<String> =
        OffsetPageState.Error(exception = IllegalStateException("boom"), page = page, data = emptyList())

    private fun PagingCache<String>.put(vararg pages: Int) {
        pages.forEach { setState(successPage(it), silently = true) }
    }

    /** A page load: Progress is written first, then the settled [state] of the same page. */
    private fun PagingCache<String>.settle(state: OffsetPageState<String>) {
        setState(progressPage(state.page), silently = true)
        setState(state, silently = true)
    }

    private fun PagingCache<String>.load(vararg pages: Int) {
        pages.forEach { settle(successPage(it)) }
    }

    private fun PagingCache<String>.setContext(start: Int, end: Int) {
        endContextPage = end
        startContextPage = start
    }

    /** Cache holding exactly [pages] with the eviction history cleared. */
    private fun cacheWith(vararg pages: Int, maxSize: Int = 3): NearestPagingCache<String> {
        val cache = createCache(maxSize)
        cache.put(*pages)
        evicted.clear()
        return cache
    }

    // -- 1. The farthest page is evicted --

    @Test
    fun `forward add evicts the lowest page`() {
        val cache = cacheWith(1, 2, 3)

        cache.settle(successPage(4))

        assertEquals(listOf(2, 3, 4), cache.pages)
        assertEquals(listOf(1), evicted)
    }

    @Test
    fun `backward add evicts the highest page`() {
        val cache = cacheWith(2, 3, 4)

        cache.settle(successPage(1))

        assertEquals(listOf(1, 2, 3), cache.pages)
        assertEquals(listOf(4), evicted)
    }

    // -- 2. Only a settled page evicts --

    @Test
    fun `progress page does not evict`() {
        val cache = cacheWith(1, 2, 3)

        cache.setState(progressPage(4), silently = true)
        assertEquals(listOf(1, 2, 3, 4), cache.pages)
        assertEquals(emptyList(), evicted)

        cache.setState(successPage(4), silently = true)
        assertEquals(listOf(2, 3, 4), cache.pages)
        assertEquals(listOf(1), evicted)
    }

    @Test
    fun `error page on the forward edge does not evict`() {
        val cache = cacheWith(1, 2, 3)

        cache.setState(errorPage(4), silently = true)
        assertEquals(listOf(1, 2, 3, 4), cache.pages)
        assertEquals(emptyList(), evicted)

        cache.settle(successPage(4))
        assertEquals(listOf(2, 3, 4), cache.pages)
        assertEquals(listOf(1), evicted)
    }

    @Test
    fun `error page on the backward edge does not evict`() {
        val cache = cacheWith(2, 3, 4)

        cache.setState(errorPage(1), silently = true)
        assertEquals(listOf(1, 2, 3, 4), cache.pages)
        assertEquals(emptyList(), evicted)

        cache.settle(successPage(1))
        assertEquals(listOf(1, 2, 3), cache.pages)
        assertEquals(listOf(4), evicted)
    }

    @Test
    fun `empty success page evicts like a success page`() {
        val cache = cacheWith(1, 2, 3)

        cache.settle(emptyPage(4))

        assertEquals(listOf(2, 3, 4), cache.pages)
        assertEquals(listOf(1), evicted)
    }

    @Test
    fun `incomplete last page added forward evicts the lowest page`() {
        val cache = cacheWith(1, 2, 3)

        // Fewer items than a full page: still a settled page.
        cache.settle(successPage(4, data = listOf("only")))

        assertEquals(listOf(2, 3, 4), cache.pages)
        assertEquals(listOf(1), evicted)
    }

    @Test
    fun `reloading the incomplete last page evicts nothing`() {
        val cache = cacheWith(2, 3, 4)

        cache.setState(progressPage(4), silently = true)
        cache.setState(successPage(4, data = listOf("one", "two")), silently = true)

        assertEquals(listOf(2, 3, 4), cache.pages)
        assertEquals(emptyList(), evicted)
    }

    @Test
    fun `replacing a cached page evicts nothing`() {
        val cache = cacheWith(1, 2, 3)

        cache.setState(successPage(3, data = listOf("updated")), silently = true)
        cache.setState(successPage(1, data = listOf("updated")), silently = true)

        assertEquals(listOf(1, 2, 3), cache.pages)
        assertEquals(emptyList(), evicted)
    }

    @Test
    fun `untracked write evicts nothing and keeps all pages`() {
        val cache = cacheWith(1, 2, 3)
        cache.setState(progressPage(4), silently = true)
        assertEquals(listOf(1, 2, 3, 4), cache.pages)

        // A background update of a cached page must not move the window.
        cache.setStateUntracked(successPage(4), silently = true)
        cache.setStateUntracked(successPage(2, data = listOf("live")), silently = true)

        assertEquals(listOf(1, 2, 3, 4), cache.pages)
        assertEquals(emptyList(), evicted)
    }

    @Test
    fun `success evicts far pending pages down to maxSize`() {
        val cache = cacheWith(2, 3, 4)
        cache.setState(progressPage(5), silently = true)
        assertEquals(listOf(2, 3, 4, 5), cache.pages)

        cache.settle(successPage(1))

        assertEquals(listOf(1, 2, 3), cache.pages)
        // Farthest first: the pending page 5, then page 4.
        assertEquals(listOf(5, 4), evicted)
    }

    // -- 2b. Only the completion of a load evicts --

    @Test
    fun `success written without a preceding progress does not evict`() {
        val cache = cacheWith(1, 2, 3)

        // CRUD overflow, restore or L2 promotion: a new page appears without a load.
        cache.setState(successPage(4), silently = true)

        assertEquals(listOf(1, 2, 3, 4), cache.pages)
        assertEquals(emptyList(), evicted)
    }

    @Test
    fun `editing an already loaded page and its overflow page keeps the edited page`() {
        val cache = cacheWith(1, 2, 3)

        // A message is inserted into page 1; the overflow cascades and creates page 4.
        cache.setState(successPage(1, data = listOf("new", "item_1")), silently = true)
        cache.setState(successPage(2, data = listOf("item_1", "item_2")), silently = true)
        cache.setState(successPage(3, data = listOf("item_2", "item_3")), silently = true)
        cache.setState(successPage(4, data = listOf("item_3")), silently = true)

        assertEquals(listOf(1, 2, 3, 4), cache.pages)
        assertEquals(listOf("new", "item_1"), cache.getStateOf(1)!!.data)
        assertEquals(emptyList(), evicted)
    }

    @Test
    fun `next completed load trims crud overflow pages`() {
        val cache = cacheWith(1, 2, 3)
        cache.setState(successPage(4), silently = true)
        assertEquals(listOf(1, 2, 3, 4), cache.pages)

        cache.settle(successPage(5))

        assertEquals(listOf(3, 4, 5), cache.pages)
        assertEquals(listOf(1, 2), evicted)
    }

    @Test
    fun `success over an error page without a new progress does not evict`() {
        val cache = cacheWith(1, 2, 3)
        cache.setState(errorPage(4), silently = true)

        cache.setState(successPage(4), silently = true)

        assertEquals(listOf(1, 2, 3, 4), cache.pages)
        assertEquals(emptyList(), evicted)
    }

    // -- 3. Equal distance --

    @Test
    fun `equal distance evicts the page outside the context window first`() {
        val cache = cacheWith(1, 2, 4, 5, maxSize = 4)
        cache.setContext(4, 5)

        cache.settle(successPage(3))

        // Pages 1 and 5 are both two steps from 3, but 5 is inside the window.
        assertEquals(listOf(2, 3, 4, 5), cache.pages)
        assertEquals(listOf(1), evicted)
    }

    @Test
    fun `equal distance outside the window evicts the higher page`() {
        val cache = cacheWith(1, 2, 4, 5, maxSize = 4)
        cache.setContext(3, 3)

        cache.settle(successPage(3))

        // Pages 1 and 5 are both two steps from 3 and both outside the window.
        assertEquals(listOf(1, 2, 3, 4), cache.pages)
        assertEquals(listOf(5), evicted)
    }

    @Test
    fun `equal distance without a started window evicts the higher page`() {
        val cache = cacheWith(1, 2, 4, 5, maxSize = 4)

        cache.settle(successPage(3))

        assertEquals(listOf(1, 2, 3, 4), cache.pages)
        assertEquals(listOf(5), evicted)
    }

    @Test
    fun `just added page is never evicted even when far from the others`() {
        val cache = cacheWith(1, 2, 3)

        cache.settle(successPage(10))

        assertEquals(listOf(2, 3, 10), cache.pages)
        assertEquals(listOf(1), evicted)
    }

    // -- 4. Parameters --

    @Test
    fun `maxSize of one keeps only the added page`() {
        val cache = createCache(maxSize = 1)

        cache.load(1)
        cache.load(2)
        assertEquals(listOf(2), cache.pages)

        cache.load(5)
        assertEquals(listOf(5), cache.pages)
        assertEquals(listOf(1, 2), evicted)
    }

    @Test
    fun `maxSize must be positive`() {
        assertFailsWith<IllegalArgumentException> { NearestPagingCache<String>(maxSize = 0) }
        assertFailsWith<IllegalArgumentException> { NearestPagingCache<String>(maxSize = -1) }
    }

    @Test
    fun `replaceLeaf keeps maxSize and listener`() {
        val original = createCache(maxSize = 2)
        val leaf = QueuedPagingCache<String>(maxSize = 10)

        val replaced = original.replaceLeaf(leaf)

        assertEquals(2, replaced.maxSize)
        assertSame(listener, replaced.evictionListener)
        replaced.load(1, 2, 3)
        assertEquals(listOf(2, 3), replaced.pages)
        assertEquals(listOf(1), evicted)
    }

    @Test
    fun `strategy implements ChainablePagingCache`() {
        @Suppress("UNUSED_VARIABLE") val chainable: ChainablePagingCache<String> = createCache()
    }

    // -- 5. Composition --

    @Test
    fun `plus with TimeLimitedPagingCache keeps the nearest rule`() {
        val time = TestTimeSource()
        val cache = NearestPagingCache<String>(maxSize = 3, evictionListener = listener) +
                TimeLimitedPagingCache<String>(ttl = 1.hours, timeSource = time)

        // t = 0: pages 1..3. t = 40 min: page 4 arrives, the nearest rule drops page 1.
        cache.put(1, 2, 3)
        time += 40.minutes
        cache.settle(successPage(4))
        assertEquals(listOf(2, 3, 4), cache.pages)
        assertEquals(listOf(1), evicted)

        // t = 70 min: pages 2 and 3 are older than the ttl and expire on the next write,
        // page 4 is still fresh.
        time += 30.minutes
        cache.settle(successPage(5))
        assertEquals(listOf(4, 5), cache.pages)

        // The nearest rule still applies to the fresh pages.
        cache.load(6, 7)
        assertEquals(listOf(5, 6, 7), cache.pages)
        assertEquals(listOf(1, 4), evicted)
    }

    @Test
    fun `plus with a larger QueuedPagingCache keeps the nearest rule`() {
        val cache = NearestPagingCache<String>(maxSize = 3, evictionListener = listener) +
                QueuedPagingCache<String>(maxSize = 5, protectContextWindow = false)

        // Forward over five pages, then back to the start.
        cache.load(1, 2, 3, 4, 5)
        assertEquals(listOf(3, 4, 5), cache.pages)
        cache.load(2)
        assertEquals(listOf(2, 3, 4), cache.pages)
        cache.load(1)
        assertEquals(listOf(1, 2, 3), cache.pages)

        assertEquals(listOf(1, 2, 5, 4), evicted)
    }

    @Test
    fun `outer QueuedPagingCache stays consistent after inner nearest eviction`() {
        val outerEvicted = mutableListOf<Int>()
        val outer = QueuedPagingCache(
            cache = NearestPagingCache<String>(maxSize = 3, evictionListener = listener),
            maxSize = 5,
            protectContextWindow = false,
            evictionListener = CacheEvictionListener { outerEvicted.add((it as OffsetPageState).page) },
        )

        // Six pages forward: the inner nearest strategy drops pages behind the FIFO decorator's back.
        outer.load(1, 2, 3, 4, 5, 6)
        assertEquals(listOf(4, 5, 6), outer.pages)
        assertEquals(listOf(1, 2, 3), evicted)

        // Two pages backward: only the farthest pages go, the neighbours of the added page stay.
        outer.load(3)
        assertEquals(listOf(3, 4, 5), outer.pages)
        outer.load(2)
        assertEquals(listOf(2, 3, 4), outer.pages)

        assertEquals(listOf(1, 2, 3, 6, 5), evicted)
        assertEquals(emptyList(), outerEvicted)
        assertEquals(3, outer.size)

        // A long scroll forward: the decorator must not keep a history of the removed pages.
        for (page in 5..60) outer.load(page)
        assertEquals(listOf(58, 59, 60), outer.pages)
        assertTrue(
            outer.trackedEntryCount <= 2 * outer.size + 16,
            "tracked ${outer.trackedEntryCount} entries for ${outer.size} pages"
        )
        assertEquals(emptyList(), outerEvicted)
    }
}
