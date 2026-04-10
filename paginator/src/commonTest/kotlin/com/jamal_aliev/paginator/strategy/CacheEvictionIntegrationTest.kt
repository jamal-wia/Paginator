package com.jamal_aliev.paginator.strategy

import com.jamal_aliev.paginator.MutablePaginator
import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class CacheEvictionIntegrationTest {

    private fun createLruPaginator(
        maxSize: Int = 5,
        capacity: Int = 3,
        totalPages: Int = 20,
        protectContextWindow: Boolean = true,
    ): MutablePaginator<String> {
        val core = LruPagingCore<String>(
            initialCapacity = capacity,
            maxSize = maxSize,
            protectContextWindow = protectContextWindow,
        )
        return MutablePaginator(core = core) { page: Int ->
            if (page in 1..totalPages) {
                List(this.core.capacity) { "p${page}_item$it" }
            } else {
                emptyList()
            }
        }
    }

    private fun createFifoPaginator(
        maxSize: Int = 5,
        capacity: Int = 3,
        totalPages: Int = 20,
        protectContextWindow: Boolean = true,
    ): MutablePaginator<String> {
        val core = FifoPagingCore<String>(
            initialCapacity = capacity,
            maxSize = maxSize,
            protectContextWindow = protectContextWindow,
        )
        return MutablePaginator(core = core) { page: Int ->
            if (page in 1..totalPages) {
                List(this.core.capacity) { "p${page}_item$it" }
            } else {
                emptyList()
            }
        }
    }

    private fun createTtlPaginator(
        ttlMs: Long = 1000,
        capacity: Int = 3,
        totalPages: Int = 20,
        timeSource: TestTimeSource = TestTimeSource(),
    ): Pair<MutablePaginator<String>, TestTimeSource> {
        val core = TtlPagingCore<String>(
            initialCapacity = capacity,
            ttl = ttlMs.milliseconds,
            timeSource = timeSource,
        )
        val paginator = MutablePaginator(core = core) { page: Int ->
            if (page in 1..totalPages) {
                List(this.core.capacity) { "p${page}_item$it" }
            } else {
                emptyList()
            }
        }
        return Pair(paginator, timeSource)
    }

    // ── 1. LRU: jump to new location evicts old pages ────────────────────

    @Test
    fun `LRU evicts old pages when jumping to a different location`() = runTest {
        val paginator = createLruPaginator(maxSize = 4, capacity = 3)

        // Load pages 1-3
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        // Jump far away — resets context window to page 10
        paginator.jump(BookmarkInt(10), silentlyLoading = true, silentlyResult = true)

        // Pages 1-3 are now outside context window, page 10 is inside
        // Cache should have at most maxSize=4 pages
        assertTrue(paginator.core.size <= 4)
        assertNotNull(paginator.core.getStateOf(10))

        // Load more pages at new location
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        // Old pages from first location should be evicted
        assertTrue(paginator.core.size <= 4)
    }

    // ── 2. FIFO: jump evicts first-loaded pages ──────────────────────────

    @Test
    fun `FIFO evicts first-loaded pages when jumping`() = runTest {
        val paginator = createFifoPaginator(maxSize = 3, capacity = 3)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        // Jump to page 10 — pages 1, 2 are now outside context
        paginator.jump(BookmarkInt(10), silentlyLoading = true, silentlyResult = true)

        // With maxSize=3 and pages 1, 2, 10 in cache, we're at limit
        // Load page 11 — should evict page 1 (first inserted)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        assertTrue(paginator.core.size <= 3)
        assertNotNull(paginator.core.getStateOf(10))
        assertNotNull(paginator.core.getStateOf(11))
    }

    // ── 3. TTL: expired pages evicted on navigation ──────────────────────

    @Test
    fun `TTL evicts expired pages when navigating after time passes`() = runTest {
        val ts = TestTimeSource()
        val (paginator, _) = createTtlPaginator(ttlMs = 500, timeSource = ts)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        ts += 600.milliseconds

        // Jump to page 10 — pages 1 & 2 are outside context and expired
        paginator.jump(BookmarkInt(10), silentlyLoading = true, silentlyResult = true)

        // Expired pages should be evicted
        assertNull(paginator.core.getStateOf(1))
        assertNull(paginator.core.getStateOf(2))
        assertNotNull(paginator.core.getStateOf(10))
    }

    // ── 4. Context window protection during navigation ────────────────────

    @Test
    fun `LRU protects context window pages during navigation`() = runTest {
        val paginator = createLruPaginator(maxSize = 3, capacity = 3)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        // Context window = 1..3, all 3 pages are protected, maxSize=3
        assertEquals(3, paginator.core.size)
        assertNotNull(paginator.core.getStateOf(1))
        assertNotNull(paginator.core.getStateOf(2))
        assertNotNull(paginator.core.getStateOf(3))
    }

    // ── 5. release() fully resets strategy ────────────────────────────────

    @Test
    fun `release resets LRU strategy completely`() = runTest {
        val paginator = createLruPaginator(maxSize = 3, capacity = 3)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        paginator.release()
        assertEquals(0, paginator.core.size)

        // Can use normally after release
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        assertNotNull(paginator.core.getStateOf(1))
    }

    // ── 6. restoreState rebuilds tracking ─────────────────────────────────

    @Test
    fun `restoreState rebuilds LRU tracking correctly`() = runTest {
        val core = LruPagingCore<String>(initialCapacity = 3, maxSize = 10)
        val paginator = MutablePaginator(core = core) { page: Int ->
            List(3) { "p${page}_item$it" }
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        for (i in 2..4) {
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        }

        val savedPages = paginator.core.pages.toList()
        val snapshot = paginator.core.saveState()
        paginator.core.restoreState(snapshot, silently = true)

        // All pages should be restored
        assertEquals(savedPages, paginator.core.pages.toList())
    }

    // ── 7. resize rebuilds tracking ───────────────────────────────────────

    @Test
    fun `resize clears and rebuilds LRU tracking`() = runTest {
        val core = LruPagingCore<String>(initialCapacity = 3, maxSize = 10)
        val paginator = MutablePaginator(core = core) { page: Int ->
            List(3) { "p${page}_item$it" }
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        paginator.core.resize(capacity = 2, resize = true, silently = true)

        // After resize, cache should have pages
        assertTrue(paginator.core.size > 0)
    }

    // ── 8. CRUD operations with strategy ──────────────────────────────────

    @Test
    fun `setElement works with LRU strategy`() = runTest {
        val paginator = createLruPaginator(maxSize = 5, capacity = 3)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        paginator.setElement("modified", page = 1, index = 0)

        assertNotNull(paginator.core.getStateOf(1))
        assertEquals("modified", paginator.core.getElement(1, 0))
    }

    // ── 9. evictionListener during navigation ─────────────────────────────

    @Test
    fun `evictionListener called when pages evicted during navigation`() = runTest {
        val core = LruPagingCore<String>(initialCapacity = 3, maxSize = 3)
        val evicted = mutableListOf<Int>()
        core.evictionListener = CacheEvictionListener { evicted.add(it.page) }

        val paginator = MutablePaginator(core = core) { page: Int ->
            List(3) { "p${page}_item$it" }
        }

        // Load pages 1-3 in context
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        assertTrue(evicted.isEmpty()) // all in context, no eviction needed

        // Jump far away — pages 1-3 leave context
        paginator.jump(BookmarkInt(10), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        // Some old pages should have been evicted
        assertTrue(evicted.isNotEmpty())
    }

    // ── 10. Eviction during goNextPage after jump ─────────────────────────

    @Test
    fun `eviction during goNextPage after context window shift`() = runTest {
        val paginator = createLruPaginator(maxSize = 2, capacity = 3, protectContextWindow = false)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        assertEquals(2, paginator.core.size)

        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        // With protectContextWindow=false, oldest page should be evicted
        assertTrue(paginator.core.size <= 2)
        assertNotNull(paginator.core.getStateOf(3))
    }

    // ── 11. Eviction during jump ──────────────────────────────────────────

    @Test
    fun `eviction during jump to distant page`() = runTest {
        val paginator = createLruPaginator(maxSize = 2, capacity = 3)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        // Jump to page 10 — old pages leave context
        paginator.jump(BookmarkInt(10), silentlyLoading = true, silentlyResult = true)

        // maxSize=2, pages 1 & 2 outside context, page 10 inside
        assertTrue(paginator.core.size <= 2)
        assertNotNull(paginator.core.getStateOf(10))
    }

    // ── 12. Serialization roundtrip ───────────────────────────────────────

    @Test
    fun `serialization roundtrip works with LRU core`() = runTest {
        val core = LruPagingCore<String>(initialCapacity = 3, maxSize = 10)
        val paginator = MutablePaginator(core = core) { page: Int ->
            List(3) { "p${page}_item$it" }
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        val savedPages = paginator.core.pages.toList()
        val snapshot = paginator.core.saveState()
        paginator.core.restoreState(snapshot, silently = true)

        assertEquals(savedPages, paginator.core.pages.toList())
    }
}
