package com.jamal_aliev.paginator.cursor.cache.eviction

import com.jamal_aliev.paginator.core.cache.eviction.CacheEvictionListener
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.cache.CursorPagingCache
import com.jamal_aliev.paginator.cursor.extension.plus
import com.jamal_aliev.paginator.cursor.page.CursorPageState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.hours
import kotlin.time.TestTimeSource

class CursorNearestPagingCacheTest {

    private val evicted = mutableListOf<String>()

    private val listener = CacheEvictionListener<String> {
        evicted.add((it as CursorPageState<*, *>).bookmark.self as String)
    }

    private fun createCache(maxSize: Int = 3): CursorNearestPagingCache<String, String> =
        CursorNearestPagingCache(maxSize = maxSize, evictionListener = listener)

    /** Page `i` of a linked chain `p0 <-> p1 <-> ... <-> p5`. */
    private fun bookmarkAt(i: Int): CursorBookmark<String> =
        CursorBookmark(
            prev = if (i == 0) null else "p${i - 1}",
            self = "p$i",
            next = if (i == TOTAL - 1) null else "p${i + 1}",
        )

    private fun CursorPagingCache<String, String>.put(vararg pages: Int) {
        pages.forEach {
            setState(bookmarkAt(it), CursorPageState.Success(bookmarkAt(it), listOf("item_$it")), silently = true)
        }
    }

    private fun CursorPagingCache<String, String>.putSuccess(i: Int, data: List<String>) =
        setState(bookmarkAt(i), CursorPageState.Success(bookmarkAt(i), data), silently = true)

    private fun CursorPagingCache<String, String>.putProgress(i: Int) =
        setState(bookmarkAt(i), CursorPageState.Progress(bookmarkAt(i), emptyList()), silently = true)

    private fun CursorPagingCache<String, String>.putError(i: Int) =
        setState(
            bookmarkAt(i),
            CursorPageState.Error(IllegalStateException("boom"), bookmarkAt(i), emptyList()),
            silently = true
        )

    private fun CursorPagingCache<String, String>.setContext(start: Int, end: Int) {
        startContextCursor = bookmarkAt(start)
        endContextCursor = bookmarkAt(end)
    }

    private fun CursorPagingCache<String, String>.selves(): List<String> =
        cursors.map { it.self }.sorted()

    private fun names(vararg pages: Int): List<String> = pages.map { "p$it" }

    /** Cache holding exactly [pages] with the eviction history cleared. */
    private fun cacheWith(vararg pages: Int, maxSize: Int = 3): CursorNearestPagingCache<String, String> {
        val cache = createCache(maxSize)
        cache.put(*pages)
        evicted.clear()
        return cache
    }

    // -- 1. The farthest page is evicted --

    @Test
    fun `forward add evicts the head page`() {
        val cache = cacheWith(0, 1, 2)

        cache.put(3)

        assertEquals(names(1, 2, 3), cache.selves())
        assertEquals(names(0), evicted)
    }

    @Test
    fun `backward add evicts the tail page`() {
        val cache = cacheWith(1, 2, 3)

        cache.put(0)

        assertEquals(names(0, 1, 2), cache.selves())
        assertEquals(names(3), evicted)
    }

    // -- 2. Only a settled page evicts --

    @Test
    fun `progress page does not evict`() {
        val cache = cacheWith(0, 1, 2)

        cache.putProgress(3)
        assertEquals(names(0, 1, 2, 3), cache.selves())
        assertEquals(emptyList(), evicted)

        cache.put(3)
        assertEquals(names(1, 2, 3), cache.selves())
        assertEquals(names(0), evicted)
    }

    @Test
    fun `error page on the forward edge does not evict`() {
        val cache = cacheWith(0, 1, 2)

        cache.putError(3)
        assertEquals(names(0, 1, 2, 3), cache.selves())
        assertEquals(emptyList(), evicted)

        cache.put(3)
        assertEquals(names(1, 2, 3), cache.selves())
        assertEquals(names(0), evicted)
    }

    @Test
    fun `error page on the backward edge does not evict`() {
        val cache = cacheWith(1, 2, 3)

        cache.putError(0)
        assertEquals(names(0, 1, 2, 3), cache.selves())
        assertEquals(emptyList(), evicted)

        cache.put(0)
        assertEquals(names(0, 1, 2), cache.selves())
        assertEquals(names(3), evicted)
    }

    @Test
    fun `empty success page evicts like a success page`() {
        val cache = cacheWith(0, 1, 2)

        cache.putSuccess(3, emptyList())

        assertEquals(names(1, 2, 3), cache.selves())
        assertEquals(names(0), evicted)
    }

    @Test
    fun `incomplete tail page added forward evicts the head page`() {
        val cache = cacheWith(0, 1, 2)

        // Fewer items than a full page: still a settled page.
        cache.putSuccess(3, listOf("only"))

        assertEquals(names(1, 2, 3), cache.selves())
        assertEquals(names(0), evicted)
    }

    @Test
    fun `reloading the incomplete tail page evicts nothing`() {
        val cache = cacheWith(1, 2, 3)

        cache.putProgress(3)
        cache.putSuccess(3, listOf("one", "two"))

        assertEquals(names(1, 2, 3), cache.selves())
        assertEquals(emptyList(), evicted)
    }

    @Test
    fun `replacing a cached page evicts nothing`() {
        val cache = cacheWith(0, 1, 2)

        cache.putSuccess(2, listOf("updated"))
        cache.putSuccess(0, listOf("updated"))

        assertEquals(names(0, 1, 2), cache.selves())
        assertEquals(emptyList(), evicted)
    }

    @Test
    fun `untracked write evicts nothing and keeps all pages`() {
        val cache = cacheWith(0, 1, 2)
        cache.putProgress(3)
        assertEquals(names(0, 1, 2, 3), cache.selves())

        // A background update of a cached page must not move the window.
        cache.setStateUntracked(bookmarkAt(3), CursorPageState.Success(bookmarkAt(3), listOf("live")), silently = true)
        cache.setStateUntracked(bookmarkAt(1), CursorPageState.Success(bookmarkAt(1), listOf("live")), silently = true)

        assertEquals(names(0, 1, 2, 3), cache.selves())
        assertEquals(emptyList(), evicted)
    }

    @Test
    fun `success evicts far pending pages down to maxSize`() {
        val cache = cacheWith(1, 2, 3)
        cache.putProgress(4)
        assertEquals(names(1, 2, 3, 4), cache.selves())

        cache.put(0)

        assertEquals(names(0, 1, 2), cache.selves())
        // Farthest first: the pending page p4, then p3.
        assertEquals(names(4, 3), evicted)
    }

    // -- 3. Unreachable pages --

    private val island = CursorBookmark<String>(prev = null, self = "x", next = null)

    /** Adds the island without evicting anything, so a test can start from an over-full cache. */
    private fun CursorPagingCache<String, String>.putIsland() =
        setStateUntracked(island, CursorPageState.Success(island, listOf("island")), silently = true)

    @Test
    fun `unreachable island is evicted before linked neighbours`() {
        val cache = createCache(maxSize = 3)
        cache.put(1, 2)
        cache.putIsland()
        evicted.clear()

        cache.put(3)

        assertEquals(listOf("p1", "p2", "p3"), cache.selves())
        assertEquals(listOf("x"), evicted)
    }

    @Test
    fun `unreachable island goes before the farthest reachable page`() {
        val cache = createCache(maxSize = 2)
        cache.put(1, 2)
        cache.putIsland()
        evicted.clear()

        cache.put(3)

        assertEquals(names(2, 3), cache.selves())
        assertEquals(listOf("x", "p1"), evicted)
    }

    // -- 4. Equal distance --

    @Test
    fun `equal distance evicts the page outside the context window first`() {
        val cache = cacheWith(0, 1, 3, 4, maxSize = 4)
        cache.setContext(3, 4)

        cache.put(2)

        // p0 and p4 are both two steps from p2, but p4 is inside the window.
        assertEquals(names(1, 2, 3, 4), cache.selves())
        assertEquals(names(0), evicted)
    }

    @Test
    fun `equal distance outside the window evicts the next side page`() {
        val cache = cacheWith(0, 1, 3, 4, maxSize = 4)
        cache.setContext(2, 2)

        cache.put(2)

        // p0 and p4 are both two steps from p2 and both outside the window.
        assertEquals(names(0, 1, 2, 3), cache.selves())
        assertEquals(names(4), evicted)
    }

    @Test
    fun `equal distance without a started window evicts the next side page`() {
        val cache = cacheWith(0, 1, 3, 4, maxSize = 4)

        cache.put(2)

        assertEquals(names(0, 1, 2, 3), cache.selves())
        assertEquals(names(4), evicted)
    }

    // -- 5. Parameters --

    @Test
    fun `maxSize of one keeps only the added page`() {
        val cache = createCache(maxSize = 1)

        cache.put(0)
        cache.put(1)
        assertEquals(names(1), cache.selves())

        cache.put(4)
        assertEquals(names(4), cache.selves())
        assertEquals(names(0, 1), evicted)
    }

    @Test
    fun `maxSize must be positive`() {
        assertFailsWith<IllegalArgumentException> { CursorNearestPagingCache<String, String>(maxSize = 0) }
        assertFailsWith<IllegalArgumentException> { CursorNearestPagingCache<String, String>(maxSize = -1) }
    }

    @Test
    fun `replaceLeaf keeps maxSize and listener`() {
        val original = createCache(maxSize = 2)
        val leaf = CursorQueuedPagingCache<String, String>(maxSize = 10)

        val replaced = original.replaceLeaf(leaf)

        assertEquals(2, replaced.maxSize)
        assertSame(listener, replaced.evictionListener)
        replaced.put(0, 1, 2)
        assertEquals(names(1, 2), replaced.selves())
        assertEquals(names(0), evicted)
    }

    @Test
    fun `strategy implements CursorChainablePagingCache`() {
        @Suppress("UNUSED_VARIABLE") val chainable: CursorChainablePagingCache<String, String> = createCache()
    }

    // -- 6. Composition --

    @Test
    fun `plus with a larger CursorQueuedPagingCache keeps the nearest rule`() {
        val cache = CursorNearestPagingCache<String, String>(maxSize = 3, evictionListener = listener) +
                CursorQueuedPagingCache<String, String>(maxSize = 5, protectContextWindow = false)

        // Forward over five pages, then back to the start.
        cache.put(0, 1, 2, 3, 4)
        assertEquals(names(2, 3, 4), cache.selves())
        cache.put(1)
        assertEquals(names(1, 2, 3), cache.selves())
        cache.put(0)
        assertEquals(names(0, 1, 2), cache.selves())

        assertEquals(names(0, 1, 4, 3), evicted)
    }

    @Test
    fun `outer CursorQueuedPagingCache stays consistent after inner nearest eviction`() {
        val outerEvicted = mutableListOf<String>()
        val outer = CursorQueuedPagingCache(
            cache = CursorNearestPagingCache<String, String>(maxSize = 3, evictionListener = listener),
            maxSize = 5,
            protectContextWindow = false,
            evictionListener = CacheEvictionListener { outerEvicted.add((it as CursorPageState<*, *>).bookmark.self as String) },
        )

        // Six pages forward: the inner nearest strategy drops pages behind the FIFO decorator's back.
        outer.put(0, 1, 2, 3, 4, 5)
        assertEquals(names(3, 4, 5), outer.selves())
        assertEquals(names(0, 1, 2), evicted)

        // Two pages backward: only the farthest pages go, the neighbours of the added page stay.
        outer.put(2)
        assertEquals(names(2, 3, 4), outer.selves())
        outer.put(1)
        assertEquals(names(1, 2, 3), outer.selves())

        assertEquals(names(0, 1, 2, 5, 4), evicted)
        assertEquals(emptyList(), outerEvicted)
        assertEquals(3, outer.size)
    }

    @Test
    fun `plus with CursorTimeLimitedPagingCache keeps the nearest rule`() {
        val cache = CursorNearestPagingCache<String, String>(maxSize = 3, evictionListener = listener) +
                CursorTimeLimitedPagingCache<String, String>(ttl = 1.hours, timeSource = TestTimeSource())

        cache.put(0, 1, 2)
        cache.put(3)
        assertEquals(names(1, 2, 3), cache.selves())
        assertEquals(names(0), evicted)

        cache.put(0)
        assertEquals(names(0, 1, 2), cache.selves())
        assertEquals(names(0, 3), evicted)
    }

    private companion object {
        const val TOTAL = 6
    }
}
