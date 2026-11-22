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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
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

    /** A page load: Progress is written first, then the settled Success of the same page. */
    private fun CursorPagingCache<String, String>.settle(i: Int, data: List<String>) {
        putProgress(i)
        putSuccess(i, data)
    }

    private fun CursorPagingCache<String, String>.load(vararg pages: Int) {
        pages.forEach { settle(it, listOf("item_$it")) }
    }

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

        cache.load(3)

        assertEquals(names(1, 2, 3), cache.selves())
        assertEquals(names(0), evicted)
    }

    @Test
    fun `backward add evicts the tail page`() {
        val cache = cacheWith(1, 2, 3)

        cache.load(0)

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

        cache.load(3)
        assertEquals(names(1, 2, 3), cache.selves())
        assertEquals(names(0), evicted)
    }

    @Test
    fun `error page on the forward edge does not evict`() {
        val cache = cacheWith(0, 1, 2)

        cache.putError(3)
        assertEquals(names(0, 1, 2, 3), cache.selves())
        assertEquals(emptyList(), evicted)

        cache.load(3)
        assertEquals(names(1, 2, 3), cache.selves())
        assertEquals(names(0), evicted)
    }

    @Test
    fun `error page on the backward edge does not evict`() {
        val cache = cacheWith(1, 2, 3)

        cache.putError(0)
        assertEquals(names(0, 1, 2, 3), cache.selves())
        assertEquals(emptyList(), evicted)

        cache.load(0)
        assertEquals(names(0, 1, 2), cache.selves())
        assertEquals(names(3), evicted)
    }

    @Test
    fun `empty success page evicts like a success page`() {
        val cache = cacheWith(0, 1, 2)

        cache.settle(3, emptyList())

        assertEquals(names(1, 2, 3), cache.selves())
        assertEquals(names(0), evicted)
    }

    @Test
    fun `incomplete tail page added forward evicts the head page`() {
        val cache = cacheWith(0, 1, 2)

        // Fewer items than a full page: still a settled page.
        cache.settle(3, listOf("only"))

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

        cache.load(0)

        assertEquals(names(0, 1, 2), cache.selves())
        // Farthest first: the pending page p4, then p3.
        assertEquals(names(4, 3), evicted)
    }

    // -- 2b. Only the completion of a load evicts --

    @Test
    fun `success written without a preceding progress does not evict`() {
        val cache = cacheWith(0, 1, 2)

        // CRUD overflow or L2 promotion: a new page appears without a load.
        cache.put(3)

        assertEquals(names(0, 1, 2, 3), cache.selves())
        assertEquals(emptyList(), evicted)
    }

    @Test
    fun `editing an already loaded page and its overflow page keeps the edited page`() {
        val cache = cacheWith(0, 1, 2)

        // A message is inserted into p0; the overflow cascades and creates p3 at the end.
        cache.putSuccess(0, listOf("new", "item_0"))
        cache.putSuccess(1, listOf("item_0", "item_1"))
        cache.putSuccess(2, listOf("item_1", "item_2"))
        cache.putSuccess(3, listOf("item_2"))

        assertEquals(names(0, 1, 2, 3), cache.selves())
        assertEquals(listOf("new", "item_0"), cache.getStateOf("p0")!!.data)
        assertEquals(emptyList(), evicted)
    }

    @Test
    fun `next completed load trims crud overflow pages`() {
        val cache = cacheWith(0, 1, 2)
        cache.put(3)
        assertEquals(names(0, 1, 2, 3), cache.selves())

        cache.load(4)

        assertEquals(names(2, 3, 4), cache.selves())
        assertEquals(names(0, 1), evicted)
    }

    @Test
    fun `success over an error page without a new progress does not evict`() {
        val cache = cacheWith(0, 1, 2)
        cache.putError(3)

        cache.put(3)

        assertEquals(names(0, 1, 2, 3), cache.selves())
        assertEquals(emptyList(), evicted)
    }

    // -- 2c. Links --

    @Test
    fun `cyclic links terminate and evict by the shortest distance`() {
        // p1 and p2 link to each other and the walk from p3 never reaches the anchor again: only
        // the visited set stops it.
        val b1 = CursorBookmark(prev = "p2", self = "p1", next = "p2")
        val b2 = CursorBookmark(prev = "p1", self = "p2", next = "p1")
        val b3 = CursorBookmark<String>(prev = "p2", self = "p3", next = null)
        val cache = createCache(maxSize = 2)
        cache.setStateUntracked(b1, CursorPageState.Success(b1, listOf("a")), silently = true)
        cache.setStateUntracked(b2, CursorPageState.Success(b2, listOf("b")), silently = true)
        cache.setState(b3, CursorPageState.Progress(b3, emptyList()), silently = true)

        cache.setState(b3, CursorPageState.Success(b3, listOf("c")), silently = true)

        // p2 is one step behind p3, p1 two steps: the farthest page goes.
        assertEquals(names(2, 3), cache.selves())
        assertEquals(names(1), evicted)
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

        cache.load(3)

        assertEquals(listOf("p1", "p2", "p3"), cache.selves())
        assertEquals(listOf("x"), evicted)
    }

    @Test
    fun `unreachable island goes before the farthest reachable page`() {
        val cache = createCache(maxSize = 2)
        cache.put(1, 2)
        cache.putIsland()
        evicted.clear()

        cache.load(3)

        assertEquals(names(2, 3), cache.selves())
        assertEquals(listOf("x", "p1"), evicted)
    }

    @Test
    fun `unreachable pages keep the order of cursors even inside the context window`() {
        val other = CursorBookmark<String>(prev = null, self = "y", next = null)
        val cache = createCache(maxSize = 2)
        cache.put(1)
        cache.putIsland()
        cache.setStateUntracked(other, CursorPageState.Success(other, listOf("other")), silently = true)
        cache.putProgress(2)

        // The island that comes first in `cursors` is the one the context window points at.
        val islands = cache.cursors.filter { it.self == "x" || it.self == "y" }
        cache.startContextCursor = islands.first()
        cache.endContextCursor = islands.first()
        evicted.clear()

        cache.putSuccess(2, listOf("item_2"))

        // Both islands are unreachable from p2: they go first, in the order of `cursors`.
        assertEquals(islands.map { it.self }, evicted)
        assertEquals(names(1, 2), cache.selves())
    }

    // -- 4. Equal distance --

    @Test
    fun `equal distance evicts the page outside the context window first`() {
        val cache = cacheWith(0, 1, 3, 4, maxSize = 4)
        cache.setContext(3, 4)

        cache.load(2)

        // p0 and p4 are both two steps from p2, but p4 is inside the window.
        assertEquals(names(1, 2, 3, 4), cache.selves())
        assertEquals(names(0), evicted)
    }

    @Test
    fun `equal distance outside the window evicts the next side page`() {
        val cache = cacheWith(0, 1, 3, 4, maxSize = 4)
        cache.setContext(2, 2)

        cache.load(2)

        // p0 and p4 are both two steps from p2 and both outside the window.
        assertEquals(names(0, 1, 2, 3), cache.selves())
        assertEquals(names(4), evicted)
    }

    @Test
    fun `equal distance without a started window evicts the next side page`() {
        val cache = cacheWith(0, 1, 3, 4, maxSize = 4)

        cache.load(2)

        assertEquals(names(0, 1, 2, 3), cache.selves())
        assertEquals(names(4), evicted)
    }

    // -- 5. Parameters --

    @Test
    fun `maxSize of one keeps only the added page`() {
        val cache = createCache(maxSize = 1)

        cache.load(0)
        cache.load(1)
        assertEquals(names(1), cache.selves())

        cache.load(4)
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
        replaced.load(0, 1, 2)
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
        cache.load(0, 1, 2, 3, 4)
        assertEquals(names(2, 3, 4), cache.selves())
        cache.load(1)
        assertEquals(names(1, 2, 3), cache.selves())
        cache.load(0)
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
        outer.load(0, 1, 2, 3, 4, 5)
        assertEquals(names(3, 4, 5), outer.selves())
        assertEquals(names(0, 1, 2), evicted)

        // Two pages backward: only the farthest pages go, the neighbours of the added page stay.
        outer.load(2)
        assertEquals(names(2, 3, 4), outer.selves())
        outer.load(1)
        assertEquals(names(1, 2, 3), outer.selves())

        assertEquals(names(0, 1, 2, 5, 4), evicted)
        assertEquals(emptyList(), outerEvicted)
        assertEquals(3, outer.size)

        // A long scroll forward: the decorator must not keep a history of the removed pages.
        for (i in 100..160) {
            val bookmark = CursorBookmark(prev = "p${i - 1}", self = "p$i", next = "p${i + 1}")
            outer.setState(bookmark, CursorPageState.Progress(bookmark, emptyList()), silently = true)
            outer.setState(bookmark, CursorPageState.Success(bookmark, listOf("item_$i")), silently = true)
        }
        assertEquals(listOf("p158", "p159", "p160"), outer.selves())
        assertTrue(
            outer.trackedEntryCount <= 2 * outer.size + 16,
            "tracked ${outer.trackedEntryCount} entries for ${outer.size} pages"
        )
        assertEquals(emptyList(), outerEvicted)
    }

    @Test
    fun `plus with CursorTimeLimitedPagingCache keeps the nearest rule`() {
        val time = TestTimeSource()
        val cache = CursorNearestPagingCache<String, String>(maxSize = 3, evictionListener = listener) +
                CursorTimeLimitedPagingCache<String, String>(ttl = 1.hours, timeSource = time)

        // t = 0: p0..p2. t = 40 min: p3 arrives, the nearest rule drops p0.
        cache.put(0, 1, 2)
        time += 40.minutes
        cache.load(3)
        assertEquals(names(1, 2, 3), cache.selves())
        assertEquals(names(0), evicted)

        // t = 70 min: p1 and p2 are older than the ttl and expire on the next write, p3 is fresh.
        time += 30.minutes
        cache.load(4)
        assertEquals(names(3, 4), cache.selves())

        // The nearest rule still applies to the fresh pages.
        cache.load(5)
        assertEquals(names(3, 4, 5), cache.selves())
        cache.load(2)
        assertEquals(names(2, 3, 4), cache.selves())
        assertEquals(names(0, 5), evicted)
    }

    private companion object {
        const val TOTAL = 6
    }
}
