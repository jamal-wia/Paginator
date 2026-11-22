package com.jamal_aliev.paginator.offset.cache.eviction

import com.jamal_aliev.paginator.offset.page.OffsetPageState

import com.jamal_aliev.paginator.core.cache.eviction.CacheEvictionListener
import com.jamal_aliev.paginator.core.extension.isEmptyState
import com.jamal_aliev.paginator.core.extension.isErrorState
import com.jamal_aliev.paginator.core.extension.isProgressState
import com.jamal_aliev.paginator.core.extension.isSuccessState
import com.jamal_aliev.paginator.offset.cache.PagingCache
import com.jamal_aliev.paginator.offset.MutablePaginator
import com.jamal_aliev.paginator.offset.PagingCore
import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.load.LoadResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class CacheEvictionIntegrationTest {

    private fun createLruPaginator(
        maxSize: Int = 5,
        capacity: Int = 3,
        totalPages: Int = 20,
        protectContextWindow: Boolean = true,
    ): MutablePaginator<String> {
        val eviction = MostRecentPagingCache<String>(
            maxSize = maxSize,
            protectContextWindow = protectContextWindow,
        )
        return MutablePaginator(
            core = PagingCore(cache = eviction, initialCapacity = capacity)
        ) { page: Int ->
            if (page in 1..totalPages) {
                LoadResult(List(this.core.capacity) { "p${page}_item$it" })
            } else {
                LoadResult(emptyList())
            }
        }
    }

    private fun createFifoPaginator(
        maxSize: Int = 5,
        capacity: Int = 3,
        totalPages: Int = 20,
        protectContextWindow: Boolean = true,
    ): MutablePaginator<String> {
        val eviction = QueuedPagingCache<String>(
            maxSize = maxSize,
            protectContextWindow = protectContextWindow,
        )
        return MutablePaginator(
            core = PagingCore(cache = eviction, initialCapacity = capacity)
        ) { page: Int ->
            if (page in 1..totalPages) {
                LoadResult(List(this.core.capacity) { "p${page}_item$it" })
            } else {
                LoadResult(emptyList())
            }
        }
    }

    private fun createTtlPaginator(
        ttlMs: Long = 1000,
        capacity: Int = 3,
        totalPages: Int = 20,
        timeSource: TestTimeSource = TestTimeSource(),
    ): Pair<MutablePaginator<String>, TestTimeSource> {
        val eviction = TimeLimitedPagingCache<String>(
            ttl = ttlMs.milliseconds,
            timeSource = timeSource,
        )
        val paginator = MutablePaginator(
            core = PagingCore(cache = eviction, initialCapacity = capacity)
        ) { page: Int ->
            if (page in 1..totalPages) {
                LoadResult(List(this.core.capacity) { "p${page}_item$it" })
            } else {
                LoadResult(emptyList())
            }
        }
        return Pair(paginator, timeSource)
    }

    /**
     * The context window invariant on a started paginator: both window boundaries are cached, every
     * page of `startContextPage..endContextPage` is cached (no hole), and the last published
     * snapshot is not empty, has no hole and holds exactly the window pages.
     */
    private suspend fun assertWindowConsistent(paginator: MutablePaginator<String>, exact: Boolean = true) {
        val core = paginator.core
        assertTrue(core.isStarted, "paginator must be started")
        val window = (core.startContextPage..core.endContextPage).toList()
        for (page in window) {
            assertNotNull(core.cache.peekStateOf(page), "window page $page must be cached: ${core.cache.pages}")
        }
        val snapshot = core.snapshot.first()
        val snapshotPages = snapshot.map { it.page }
        assertTrue(snapshotPages.isNotEmpty(), "the published snapshot must not be empty")
        if (exact) {
            assertEquals(window, snapshotPages, "the published snapshot must hold exactly the window pages")
        } else {
            // With an unfinished page next to the window (Error, Progress, incomplete or empty last
            // page) the snapshot shows it after the window; it has no hole either way.
            assertEquals((snapshotPages.first()..snapshotPages.last()).toList(), snapshotPages, "hole: $snapshotPages")
            for (page in window) assertTrue(page in snapshotPages, "snapshot must cover window page $page")
            for (state in snapshot) {
                if (state.page !in window) {
                    assertTrue(!core.isFilledSuccessState(state), "page ${state.page} outside the window is filled")
                }
            }
        }
    }

    private fun createPaginatorOver(cache: PagingCache<String>): MutablePaginator<String> =
        MutablePaginator(
            core = PagingCore(cache = cache, initialCapacity = 3)
        ) { page: Int -> LoadResult(List(this.core.capacity) { "p${page}_item$it" }) }

    /** Visible navigation 1..3 then page 4 under a limit of three pages. */
    private suspend fun assertFourthPageUnderLimitOfThree(cache: PagingCache<String>) {
        val paginator = createPaginatorOver(cache)
        paginator.jump(BookmarkInt(1))
        paginator.goNextPage()
        paginator.goNextPage()
        assertWindowConsistent(paginator)

        paginator.goNextPage()

        assertTrue(paginator.cache.size <= 3, "limit exceeded: ${paginator.cache.pages}")
        assertNotNull(paginator.cache.peekStateOf(4))
        assertWindowConsistent(paginator)
    }

    /** Window 1..3, forward to 2..4, back to 1..3: the window and the snapshot never get a hole. */
    private suspend fun assertDirectionChangeKeepsWindowWhole(cache: PagingCache<String>): List<List<Int>> {
        val paginator = createPaginatorOver(cache)
        val snapshots = mutableListOf<List<Int>>()
        paginator.jump(BookmarkInt(1))
        paginator.goNextPage()
        paginator.goNextPage()
        assertWindowConsistent(paginator)
        snapshots += paginator.core.snapshot.first().map { it.page }

        paginator.goNextPage()
        assertWindowConsistent(paginator)
        assertNotNull(paginator.cache.peekStateOf(4))
        snapshots += paginator.core.snapshot.first().map { it.page }

        paginator.goPreviousPage()
        assertWindowConsistent(paginator)
        assertNotNull(paginator.cache.peekStateOf(1))
        snapshots += paginator.core.snapshot.first().map { it.page }
        return snapshots
    }

    // -- 1. LRU: jump to new location evicts old pages --

    @Test
    fun `LRU evicts old pages when jumping to a different location`() = runTest {
        val paginator = createLruPaginator(maxSize = 4, capacity = 3)

        // Load pages 1-3
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        // Jump far away -- resets context window to page 10
        paginator.jump(BookmarkInt(10), silentlyLoading = true, silentlyResult = true)

        // Pages 1-3 are now outside context window, page 10 is inside
        // Cache should have at most maxSize=4 pages
        assertTrue(paginator.cache.size <= 4)
        assertNotNull(paginator.cache.getStateOf(10))

        // Load more pages at new location
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        // Old pages from first location should be evicted
        assertTrue(paginator.cache.size <= 4)
    }

    // -- 2. FIFO: jump evicts first-loaded pages --

    @Test
    fun `FIFO evicts first-loaded pages when jumping`() = runTest {
        val paginator = createFifoPaginator(maxSize = 3, capacity = 3)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        // Jump to page 10 -- pages 1, 2 are now outside context
        paginator.jump(BookmarkInt(10), silentlyLoading = true, silentlyResult = true)

        // With maxSize=3 and pages 1, 2, 10 in cache, we're at limit
        // Load page 11 -- should evict page 1 (first inserted)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        assertTrue(paginator.cache.size <= 3)
        assertNotNull(paginator.cache.getStateOf(10))
        assertNotNull(paginator.cache.getStateOf(11))
    }

    // -- 3. TTL: expired pages evicted on navigation --

    @Test
    fun `TTL evicts expired pages when navigating after time passes`() = runTest {
        val ts = TestTimeSource()
        val (paginator, _) = createTtlPaginator(ttlMs = 500, timeSource = ts)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        ts += 600.milliseconds

        // Jump to page 10 -- pages 1 & 2 are outside context and expired
        paginator.jump(BookmarkInt(10), silentlyLoading = true, silentlyResult = true)

        // Expired pages should be evicted
        assertNull(paginator.cache.getStateOf(1))
        assertNull(paginator.cache.getStateOf(2))
        assertNotNull(paginator.cache.getStateOf(10))
    }

    // -- 4. Context window protection during navigation --

    @Test
    fun `LRU protects context window pages during navigation`() = runTest {
        val paginator = createLruPaginator(maxSize = 3, capacity = 3)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        // Context window = 1..3, all 3 pages are protected, maxSize=3
        assertEquals(3, paginator.cache.size)
        assertNotNull(paginator.cache.getStateOf(1))
        assertNotNull(paginator.cache.getStateOf(2))
        assertNotNull(paginator.cache.getStateOf(3))
    }

    // -- 5. release() fully resets strategy --

    @Test
    fun `release resets LRU strategy completely`() = runTest {
        val paginator = createLruPaginator(maxSize = 3, capacity = 3)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        paginator.release()
        assertEquals(0, paginator.cache.size)

        // Can use normally after release
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        assertNotNull(paginator.cache.getStateOf(1))
    }

    // -- 6. restoreState rebuilds tracking --

    @Test
    fun `restoreState rebuilds LRU tracking correctly`() = runTest {
        val eviction = MostRecentPagingCache<String>(maxSize = 10)
        val paginator = MutablePaginator(
            core = PagingCore(cache = eviction, initialCapacity = 3)
        ) { page: Int ->
            LoadResult(List(3) { "p${page}_item$it" })
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        for (i in 2..4) {
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        }

        val savedPages = paginator.cache.pages.toList()
        val snapshot = paginator.core.saveState()
        paginator.core.restoreState(snapshot, silently = true)

        // All pages should be restored
        assertEquals(savedPages, paginator.cache.pages.toList())
    }

    // -- 7. resize rebuilds tracking --

    @Test
    fun `resize clears and rebuilds LRU tracking`() = runTest {
        val eviction = MostRecentPagingCache<String>(maxSize = 10)
        val paginator = MutablePaginator(
            core = PagingCore(cache = eviction, initialCapacity = 3)
        ) { page: Int ->
            LoadResult(List(3) { "p${page}_item$it" })
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        paginator.core.resize(capacity = 2, resize = true, silently = true)

        // After resize, cache should have pages
        assertTrue(paginator.cache.size > 0)
    }

    // -- 8. CRUD operations with strategy --

    @Test
    fun `setElement works with LRU strategy`() = runTest {
        val paginator = createLruPaginator(maxSize = 5, capacity = 3)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        paginator.setElement("modified", page = 1, index = 0)

        assertNotNull(paginator.cache.getStateOf(1))
        assertEquals("modified", paginator.cache.getElement(1, 0))
    }

    // -- 9. evictionListener during navigation --

    @Test
    fun `evictionListener called when pages evicted during navigation`() = runTest {
        val eviction = MostRecentPagingCache<String>(maxSize = 3)
        val evicted = mutableListOf<Int>()
        eviction.evictionListener =
            CacheEvictionListener { evicted.add((it as OffsetPageState).page) }

        val paginator = MutablePaginator(
            core = PagingCore(cache = eviction, initialCapacity = 3)
        ) { page: Int ->
            LoadResult(List(3) { "p${page}_item$it" })
        }

        // Load pages 1-3 in context
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        assertTrue(evicted.isEmpty()) // all in context, no eviction needed

        // Jump far away -- pages 1-3 leave context
        paginator.jump(BookmarkInt(10), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        // Some old pages should have been evicted
        assertTrue(evicted.isNotEmpty())
    }

    // -- 10. Eviction during goNextPage after jump --

    @Test
    fun `eviction during goNextPage after context window shift`() = runTest {
        val paginator = createLruPaginator(maxSize = 2, capacity = 3, protectContextWindow = false)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        assertEquals(2, paginator.cache.size)

        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        // With protectContextWindow=false, oldest page should be evicted
        assertTrue(paginator.cache.size <= 2)
        assertNotNull(paginator.cache.getStateOf(3))
        // The silent step already left the window whole: the part of the invariant that does not
        // need a snapshot (a visible step would repair the window itself and hide a regression here).
        assertEquals(3, paginator.core.endContextPage)
        for (page in paginator.core.startContextPage..paginator.core.endContextPage) {
            assertNotNull(paginator.cache.peekStateOf(page), "window page $page must be cached: ${paginator.cache.pages}")
        }
        // The visible step publishes a snapshot that holds exactly the window pages.
        paginator.goPreviousPage()
        assertWindowConsistent(paginator)
        assertTrue(paginator.cache.size <= 2)
    }

    // -- 10b. A fourth page under a limit of three: window and snapshot stay whole --

    @Test
    fun `fourth page under a limit of three with LRU without window protection keeps window and snapshot whole`() = runTest {
        assertFourthPageUnderLimitOfThree(MostRecentPagingCache(maxSize = 3, protectContextWindow = false))
    }

    @Test
    fun `fourth page under a limit of three with FIFO without window protection keeps window and snapshot whole`() = runTest {
        assertFourthPageUnderLimitOfThree(QueuedPagingCache(maxSize = 3, protectContextWindow = false))
    }

    @Test
    fun `fourth page under a limit of three with NearestPagingCache keeps window and snapshot whole`() = runTest {
        val cache = NearestPagingCache<String>(maxSize = 3)
        assertFourthPageUnderLimitOfThree(cache)
        assertEquals(listOf(2, 3, 4), cache.pages)
    }

    // -- 10c. Direction change inside a bounded window --

    @Test
    fun `direction change 1-3 then 2-4 then 1-3 with NearestPagingCache keeps window and snapshot whole`() = runTest {
        val snapshots = assertDirectionChangeKeepsWindowWhole(NearestPagingCache(maxSize = 3))

        assertEquals(listOf(listOf(1, 2, 3), listOf(2, 3, 4), listOf(1, 2, 3)), snapshots)
    }

    @Test
    fun `direction change 1-3 then 2-4 then 1-3 with LRU without window protection keeps window and snapshot without holes`() = runTest {
        val snapshots = assertDirectionChangeKeepsWindowWhole(
            MostRecentPagingCache(maxSize = 3, protectContextWindow = false)
        )
        // LRU evicts a page from the middle of the window; the window narrows around the loaded page (D-3)
        assertEquals(listOf(listOf(1, 2, 3), listOf(2, 3, 4), listOf(1, 2)), snapshots)
    }

    // -- 10d. Edge cases under a bounded cache (limit of two pages) --

    private class LimitedEnv(
        val paginator: MutablePaginator<String>,
        val gates: MutableMap<Int, CompletableDeferred<Unit>>,
        val failing: MutableSet<Int>,
        /** Number of items a page returns instead of the capacity: 2 makes it incomplete, 0 empty. */
        val lengths: MutableMap<Int, Int>,
    )

    private fun createLimitedEnv(cache: PagingCache<String>): LimitedEnv {
        val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        val failing = mutableSetOf<Int>()
        val lengths = mutableMapOf<Int, Int>()
        val paginator = MutablePaginator(
            core = PagingCore(cache = cache, initialCapacity = 3)
        ) { page: Int ->
            gates[page]?.await()
            if (page in failing) error("load of page $page failed")
            LoadResult(List(lengths[page] ?: this.core.capacity) { "p${page}_item$it" })
        }
        return LimitedEnv(paginator, gates, failing, lengths)
    }

    private suspend fun TestScope.assertCancelledGoNextPageKeepsWindowWhole(cache: PagingCache<String>): LimitedEnv {
        val env = createLimitedEnv(cache)
        val paginator = env.paginator
        paginator.jump(BookmarkInt(1))
        paginator.goNextPage()
        env.gates[3] = CompletableDeferred()

        val job = launch { paginator.goNextPage() }
        runCurrent()
        job.cancelAndJoin()

        assertTrue(paginator.cache.pages.none { paginator.cache.peekStateOf(it).isProgressState() }, "a Progress page is left behind")
        assertWindowConsistent(paginator)
        return env
    }

    private suspend fun assertIncompleteLastPageThenGoNextPage(cache: PagingCache<String>) {
        val env = createLimitedEnv(cache)
        val paginator = env.paginator
        env.lengths[3] = 2
        paginator.jump(BookmarkInt(1))
        paginator.goNextPage()

        val incomplete = paginator.goNextPage() // page 3 has two of three items
        assertTrue(incomplete.isSuccessState() && incomplete.data.size == 2)
        assertWindowConsistent(paginator, exact = false)
        assertTrue(3 in paginator.core.snapshot.first().map { it.page })

        val again = paginator.goNextPage() // the unfinished end page is reloaded in place

        assertEquals(3, again.page)
        assertNotNull(paginator.cache.peekStateOf(3))
        assertTrue(paginator.cache.size <= 2, "limit exceeded: ${paginator.cache.pages}")
        assertWindowConsistent(paginator, exact = false)
        assertTrue(3 in paginator.core.snapshot.first().map { it.page })
    }

    private suspend fun assertFailedNextPageThenRetry(cache: PagingCache<String>) {
        val env = createLimitedEnv(cache)
        val paginator = env.paginator
        paginator.jump(BookmarkInt(1))
        paginator.goNextPage()
        env.failing += 3

        val failed = paginator.goNextPage()

        assertTrue(failed.isErrorState())
        assertTrue(paginator.cache.peekStateOf(3).isErrorState(), "the error page is cached")
        assertWindowConsistent(paginator, exact = false)
        assertTrue(3 in paginator.core.snapshot.first().map { it.page }, "the snapshot shows the error page")

        env.failing -= 3
        val retried = paginator.goNextPage()

        assertEquals(3, retried.page)
        assertTrue(retried.isSuccessState())
        assertTrue(paginator.cache.size <= 2, "limit exceeded: ${paginator.cache.pages}")
        assertWindowConsistent(paginator)
        assertTrue(3 in paginator.core.snapshot.first().map { it.page })
    }

    private suspend fun assertEmptySourceKeepsWindowWhole(cache: PagingCache<String>) {
        val env = createLimitedEnv(cache)
        val paginator = env.paginator
        env.lengths[3] = 0
        paginator.jump(BookmarkInt(1))
        paginator.goNextPage()

        val result = paginator.goNextPage()

        assertTrue(result.isEmptyState())
        assertTrue(paginator.cache.peekStateOf(3).isEmptyState(), "the empty page is cached")
        assertNotNull(paginator.cache.peekStateOf(2), "the page next to the empty one must stay")
        assertTrue(paginator.cache.size <= 2, "limit exceeded: ${paginator.cache.pages}")
        assertWindowConsistent(paginator, exact = false)
        assertTrue(3 in paginator.core.snapshot.first().map { it.page }, "the snapshot shows the empty page")
    }

    @Test
    fun `cancelled goNextPage with LRU without window protection leaves no progress page and a valid window`() = runTest {
        assertCancelledGoNextPageKeepsWindowWhole(MostRecentPagingCache(maxSize = 2, protectContextWindow = false))
    }

    @Test
    fun `cancelled goNextPage with NearestPagingCache keeps the previous window`() = runTest {
        val env = assertCancelledGoNextPageKeepsWindowWhole(NearestPagingCache(maxSize = 2))

        assertEquals(listOf(1, 2), env.paginator.cache.pages)
        assertEquals(1, env.paginator.core.startContextPage)
        assertEquals(2, env.paginator.core.endContextPage)
    }

    @Test
    fun `incomplete last page then goNextPage with LRU without window protection keeps state and snapshot valid`() = runTest {
        assertIncompleteLastPageThenGoNextPage(MostRecentPagingCache(maxSize = 2, protectContextWindow = false))
    }

    @Test
    fun `incomplete last page then goNextPage with NearestPagingCache keeps state and snapshot valid`() = runTest {
        assertIncompleteLastPageThenGoNextPage(NearestPagingCache(maxSize = 2))
    }

    @Test
    fun `failed next page then retry with LRU without window protection ends with a whole window`() = runTest {
        assertFailedNextPageThenRetry(MostRecentPagingCache(maxSize = 2, protectContextWindow = false))
    }

    @Test
    fun `failed next page then retry with NearestPagingCache ends with a whole window`() = runTest {
        assertFailedNextPageThenRetry(NearestPagingCache(maxSize = 2))
    }

    @Test
    fun `emptied source with LRU without window protection publishes the empty page and keeps the window whole`() = runTest {
        assertEmptySourceKeepsWindowWhole(MostRecentPagingCache(maxSize = 2, protectContextWindow = false))
    }

    @Test
    fun `emptied source with NearestPagingCache publishes the empty page and keeps the window whole`() = runTest {
        assertEmptySourceKeepsWindowWhole(NearestPagingCache(maxSize = 2))
    }

    // -- 11. Eviction during jump --

    @Test
    fun `eviction during jump to distant page`() = runTest {
        val paginator = createLruPaginator(maxSize = 2, capacity = 3)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        // Jump to page 10 -- old pages leave context
        paginator.jump(BookmarkInt(10), silentlyLoading = true, silentlyResult = true)

        // maxSize=2, pages 1 & 2 outside context, page 10 inside
        assertTrue(paginator.cache.size <= 2)
        assertNotNull(paginator.cache.getStateOf(10))
    }

    // -- 12. Serialization roundtrip --

    @Test
    fun `serialization roundtrip works with LRU core`() = runTest {
        val eviction = MostRecentPagingCache<String>(maxSize = 10)
        val paginator = MutablePaginator(
            core = PagingCore(cache = eviction, initialCapacity = 3)
        ) { page: Int ->
            LoadResult(List(3) { "p${page}_item$it" })
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        val savedPages = paginator.cache.pages.toList()
        val snapshot = paginator.core.saveState()
        paginator.core.restoreState(snapshot, silently = true)

        assertEquals(savedPages, paginator.cache.pages.toList())
    }
}
