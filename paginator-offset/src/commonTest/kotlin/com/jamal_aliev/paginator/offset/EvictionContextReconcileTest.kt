package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.core.cache.eviction.CacheEvictionListener
import com.jamal_aliev.paginator.core.extension.isProgressState
import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.cache.InMemoryPagingCache
import com.jamal_aliev.paginator.offset.cache.eviction.ContextWindowPagingCache
import com.jamal_aliev.paginator.offset.cache.eviction.MostRecentPagingCache
import com.jamal_aliev.paginator.offset.cache.eviction.QueuedPagingCache
import com.jamal_aliev.paginator.offset.cache.eviction.TimeLimitedPagingCache
import com.jamal_aliev.paginator.offset.extension.plus
import com.jamal_aliev.paginator.offset.load.LoadResult
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * Regression tests for the context window invariant: while the paginator is started, every page
 * of `startContextPage..endContextPage` is cached, so the published snapshot is never frozen on a
 * missing boundary and never has a hole (`PagingCore.reconcileContextWindow`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EvictionContextReconcileTest {

    private class Env(
        val paginator: MutablePaginator<String>,
        val gates: MutableMap<Int, CompletableDeferred<Unit>>,
        val failing: MutableSet<Int>,
    )

    /**
     * `capacity = 5`, `QueuedPagingCache(maxSize, protectContextWindow) + ContextWindowPagingCache()`.
     * A page listed in [Env.gates] suspends in `load` until its gate completes.
     */
    private fun createEnv(
        maxSize: Int = 3,
        protectContextWindow: Boolean = false,
        capacity: Int = 5,
    ): Env {
        val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        val failing = mutableSetOf<Int>()
        val cache = QueuedPagingCache<String>(
            maxSize = maxSize,
            protectContextWindow = protectContextWindow,
        ) + ContextWindowPagingCache()
        val paginator = MutablePaginator(
            core = PagingCore(cache = cache, initialCapacity = capacity)
        ) { page: Int ->
            gates[page]?.await()
            if (page in failing) error("load of page $page failed")
            LoadResult(List(this.core.capacity) { "p${page}_item$it" })
        }
        return Env(paginator, gates, failing)
    }

    private suspend fun MutablePaginator<String>.snapshotPages(): List<Int> =
        core.snapshot.first().map { it.page }

    /**
     * I1/I2: every page of the window is cached, and the published snapshot is a contiguous list
     * that covers the window and extends past it only by non-filled pages.
     */
    private suspend fun assertWindowInvariant(paginator: MutablePaginator<String>) {
        val core = paginator.core
        assertTrue(core.isStarted, "paginator must be started")
        val window = core.startContextPage..core.endContextPage
        for (page in window) {
            assertNotNull(core.cache.peekStateOf(page), "window page $page must be cached")
        }
        val snapshot = core.snapshot.first()
        val pages = snapshot.map { it.page }
        assertTrue(pages.isNotEmpty(), "snapshot must not be empty")
        assertEquals((pages.first()..pages.last()).toList(), pages, "snapshot must be contiguous")
        for (page in window) assertTrue(page in pages, "snapshot must cover window page $page: $pages")
        for (state in snapshot) {
            if (state.page !in window) {
                assertTrue(
                    !core.isFilledSuccessState(state),
                    "page ${state.page} outside the window must not be a filled success page"
                )
            }
        }
    }

    // ── scenario A: the window boundary is evicted ───────────────────────────

    @Test
    fun `scenario A goNextPage publishes a snapshot after the start page is evicted`() = runTest {
        val env = createEnv()
        val paginator = env.paginator
        val emissions = mutableListOf<List<Int>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            paginator.core.snapshot.collect { emissions.add(it.map { page -> page.page }) }
        }

        paginator.jump(BookmarkInt(1))
        paginator.goNextPage()
        paginator.goNextPage()
        assertEquals(listOf(1, 2, 3), paginator.snapshotPages())
        val emissionsBefore = emissions.size

        paginator.goNextPage()

        assertEquals(listOf(2, 3, 4), paginator.cache.pages)
        assertEquals(listOf(2, 3, 4), paginator.snapshotPages())
        assertEquals(2, paginator.core.startContextPage)
        assertEquals(4, paginator.core.endContextPage)
        assertTrue(emissions.size > emissionsBefore, "a new snapshot must be emitted")
        assertWindowInvariant(paginator)
    }

    @Test
    fun `scenario A goPreviousPage after start eviction does not throw and loads page 1`() = runTest {
        val env = createEnv()
        val paginator = env.paginator

        paginator.jump(BookmarkInt(1))
        paginator.goNextPage()
        paginator.goNextPage()
        paginator.goNextPage()
        assertEquals(listOf(2, 3, 4), paginator.cache.pages)

        val loaded = paginator.goPreviousPage()

        assertEquals(1, loaded.page)
        assertTrue(paginator.core.isFilledSuccessState(loaded))
        assertNotNull(paginator.cache.peekStateOf(1))
        assertWindowInvariant(paginator)
        assertTrue(1 in paginator.snapshotPages())
    }

    // ── scenario B: a page inside the window is evicted ──────────────────────

    @Test
    fun `scenario B snapshot has no hole after a middle page is evicted`() = runTest {
        val env = createEnv()
        val paginator = env.paginator

        paginator.jump(BookmarkInt(2))
        paginator.goNextPage()
        paginator.goNextPage()
        assertEquals(listOf(2, 3, 4), paginator.snapshotPages())

        // the progress state of page 1 evicts page 2, the oldest one
        val loaded = paginator.goPreviousPage()
        assertEquals(1, loaded.page)

        val snapshot = paginator.snapshotPages()
        assertEquals((snapshot.first()..snapshot.last()).toList(), snapshot, "no hole: $snapshot")
        // D-3: the window shrinks to the run that contains the page navigation just loaded
        assertEquals(listOf(1), snapshot)
        assertEquals(1, paginator.core.startContextPage)
        assertEquals(1, paginator.core.endContextPage)
        assertWindowInvariant(paginator)
    }

    @Test
    fun `progress snapshot is published while the evicting page is loading`() = runTest {
        val env = createEnv()
        val paginator = env.paginator

        paginator.jump(BookmarkInt(1))
        paginator.goNextPage()
        paginator.goNextPage()

        val gate = CompletableDeferred<Unit>()
        env.gates[4] = gate
        val job = launch { paginator.goNextPage() }
        testScheduler.advanceUntilIdle()

        // page 1 was evicted by the progress state of page 4; the loading snapshot must show it
        val loadingSnapshot = paginator.core.snapshot.first()
        assertEquals(listOf(2, 3, 4), loadingSnapshot.map { it.page })
        assertTrue(loadingSnapshot.last().isProgressState())
        assertEquals(2, paginator.core.startContextPage)
        assertEquals(3, paginator.core.endContextPage)

        gate.complete(Unit)
        job.join()
        assertEquals(listOf(2, 3, 4), paginator.snapshotPages())
        assertTrue(paginator.core.isFilledSuccessState(paginator.cache.peekStateOf(4)))
    }

    // ── a window damaged from outside the navigation ─────────────────────────

    @Test
    fun `goPreviousPage repairs a window whose start was removed externally`() = runTest {
        val env = createEnv(maxSize = 10)
        val paginator = env.paginator

        paginator.jump(BookmarkInt(1))
        paginator.goNextPage()
        assertEquals(1, paginator.core.startContextPage)

        // bypasses navigation: the start boundary now points at a page that is not cached
        paginator.core.removeFromCache(1)

        // before the fix this threw IllegalStateException: previousPage is 0
        val loaded = paginator.goPreviousPage()

        assertEquals(1, loaded.page)
        assertEquals(listOf(1, 2), paginator.cache.pages)
        assertEquals(1, paginator.core.startContextPage)
        assertEquals(2, paginator.core.endContextPage)
        assertWindowInvariant(paginator)
    }

    @Test
    fun `goPreviousPage with a fully removed window and a failing load keeps the paginator started`() = runTest {
        val env = createEnv(maxSize = 10)
        val paginator = env.paginator

        paginator.jump(BookmarkInt(3))
        paginator.goNextPage()
        // every page of the window is gone and nothing is cached
        paginator.core.removeFromCache(3)
        paginator.core.removeFromCache(4)
        assertEquals(emptyList(), paginator.cache.pages)
        env.failing += 2

        val loaded = paginator.goPreviousPage()

        assertEquals(2, loaded.page)
        assertTrue(loaded is OffsetPageState.Error)
        assertTrue(paginator.core.isStarted, "the paginator must stay started")
        assertEquals(2, paginator.core.startContextPage)
        assertEquals(2, paginator.core.endContextPage)
        assertEquals(listOf(2), paginator.snapshotPages())
        assertWindowInvariant(paginator)
    }

    @Test
    fun `goNextPage repairs a window whose end was removed externally`() = runTest {
        val env = createEnv(maxSize = 10)
        val paginator = env.paginator

        paginator.jump(BookmarkInt(1))
        paginator.goNextPage()
        paginator.goNextPage()
        paginator.core.removeFromCache(3)

        val loaded = paginator.goNextPage()

        assertEquals(3, loaded.page)
        assertEquals(listOf(1, 2, 3), paginator.cache.pages)
        assertEquals(1, paginator.core.startContextPage)
        assertEquals(3, paginator.core.endContextPage)
        assertEquals(listOf(1, 2, 3), paginator.snapshotPages())
        assertWindowInvariant(paginator)
    }

    // ── refresh ──────────────────────────────────────────────────────────────

    @Test
    fun `refresh with TimeLimited cache without window protection keeps the window contiguous`() = runTest {
        val timeSource = TestTimeSource()
        val cache = TimeLimitedPagingCache<String>(
            ttl = 1000.milliseconds,
            protectContextWindow = false,
            timeSource = timeSource,
        )
        val paginator = MutablePaginator(
            core = PagingCore(cache = cache, initialCapacity = 5)
        ) { page: Int ->
            LoadResult(List(this.core.capacity) { "p${page}_item$it" })
        }

        // page 2 is the oldest one, pages 1 and 3 are 600 ms younger
        paginator.jump(BookmarkInt(2))
        timeSource += 600.milliseconds
        paginator.goPreviousPage()
        paginator.goNextPage()
        assertEquals(1, paginator.core.startContextPage)
        assertEquals(3, paginator.core.endContextPage)
        timeSource += 500.milliseconds // page 2 is 1100 ms old and expired, pages 1 and 3 are not

        paginator.refresh(pages = listOf(1, 3))

        assertEquals(listOf(1, 3), paginator.cache.pages, "the expired middle page is gone")
        assertEquals(1, paginator.core.startContextPage)
        assertEquals(1, paginator.core.endContextPage)
        assertEquals(listOf(1), paginator.snapshotPages())
        assertWindowInvariant(paginator)
    }

    // ── cancellation ─────────────────────────────────────────────────────────

    @Test
    fun `cancelled goNextPage after eviction publishes a valid window`() = runTest {
        val env = createEnv()
        val paginator = env.paginator

        paginator.jump(BookmarkInt(1))
        paginator.goNextPage()
        paginator.goNextPage()

        env.gates[4] = CompletableDeferred() // never completes
        val job = launch { paginator.goNextPage() }
        testScheduler.advanceUntilIdle()
        job.cancel()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(2, 3), paginator.cache.pages, "page 4 is rolled back, page 1 stays evicted")
        assertEquals(listOf(2, 3), paginator.snapshotPages())
        assertEquals(2, paginator.core.startContextPage)
        assertEquals(3, paginator.core.endContextPage)
        assertWindowInvariant(paginator)
    }

    @Test
    fun `cancelled goPreviousPage after eviction publishes a valid window`() = runTest {
        val env = createEnv()
        val paginator = env.paginator

        paginator.jump(BookmarkInt(3))
        paginator.goNextPage()
        paginator.goNextPage()
        assertEquals(listOf(3, 4, 5), paginator.cache.pages)

        env.gates[2] = CompletableDeferred() // never completes
        val job = launch { paginator.goPreviousPage() }
        testScheduler.advanceUntilIdle()
        job.cancel()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(4, 5), paginator.cache.pages, "page 2 is rolled back, page 3 stays evicted")
        assertEquals(listOf(4, 5), paginator.snapshotPages())
        assertEquals(4, paginator.core.startContextPage)
        assertEquals(5, paginator.core.endContextPage)
        assertWindowInvariant(paginator)
    }

    // ── regression: protected strategies are untouched ───────────────────────

    @Test
    fun `default protected caches do not change window behaviour`() = runTest {
        val env = createEnv(maxSize = 3, protectContextWindow = true)
        val paginator = env.paginator
        val trace = mutableListOf<Triple<Int, Int, List<Int>>>()
        suspend fun record() {
            trace += Triple(
                paginator.core.startContextPage,
                paginator.core.endContextPage,
                paginator.snapshotPages()
            )
        }

        paginator.jump(BookmarkInt(1)); record()
        paginator.goNextPage(); record()
        paginator.goNextPage(); record()
        paginator.goNextPage(); record() // the protected window may exceed maxSize
        paginator.jump(BookmarkInt(10)); record()
        paginator.goPreviousPage(); record()

        assertEquals(
            listOf(
                Triple(1, 1, listOf(1)),
                Triple(1, 2, listOf(1, 2)),
                Triple(1, 3, listOf(1, 2, 3)),
                Triple(1, 4, listOf(1, 2, 3, 4)),
                Triple(10, 10, listOf(10)),
                Triple(9, 10, listOf(9, 10)),
            ),
            trace
        )
        assertEquals(listOf(9, 10), paginator.cache.pages)
        assertWindowInvariant(paginator)
    }

    // ── reconcileContextWindow unit tests ────────────────────────────────────

    private fun coreOf(cache: com.jamal_aliev.paginator.offset.cache.PagingCache<String> = InMemoryPagingCache()) =
        PagingCore(cache = cache, initialCapacity = 2)

    private fun PagingCore<String>.putPages(vararg pages: Int) {
        for (page in pages) {
            cache.setState(initializerSuccessPage(page, mutableListOf("a$page", "b$page"), null), silently = true)
        }
    }

    private fun PagingCore<String>.window(start: Int, end: Int) {
        startContextPage = start
        endContextPage = end
    }

    @Test
    fun `reconcileContextWindow does nothing when not started`() {
        val core = coreOf()
        core.putPages(2, 3)
        core.reconcileContextWindow(anchor = 2)
        assertEquals(0, core.startContextPage)
        assertEquals(0, core.endContextPage)
    }

    @Test
    fun `reconcileContextWindow keeps an intact window`() {
        val core = coreOf()
        core.putPages(1, 2, 3, 4)
        core.window(2, 4)
        core.reconcileContextWindow(anchor = 3)
        assertEquals(2, core.startContextPage)
        assertEquals(4, core.endContextPage)
    }

    @Test
    fun `reconcileContextWindow picks the run that contains the anchor`() {
        val core = coreOf()
        core.putPages(1, 2, 4, 5, 6, 8, 9) // window 1..9 has holes at 3 and 7
        core.window(1, 9)
        core.reconcileContextWindow(anchor = 5)
        assertEquals(4, core.startContextPage)
        assertEquals(6, core.endContextPage)
    }

    @Test
    fun `reconcileContextWindow picks the nearest run when the anchor page is missing`() {
        val core = coreOf()
        core.putPages(1, 2, 3, 7, 8, 9, 10) // hole 4..6, anchor 6 is nearer to run 7..10
        core.window(1, 10)
        core.reconcileContextWindow(anchor = 6)
        assertEquals(7, core.startContextPage)
        assertEquals(10, core.endContextPage)
    }

    @Test
    fun `reconcileContextWindow picks the nearest run on the other side too`() {
        val core = coreOf()
        core.putPages(1, 2, 3, 8, 9) // hole 4..7, anchor 5 is nearer to run 1..3
        core.window(1, 9)
        core.reconcileContextWindow(anchor = 5)
        assertEquals(1, core.startContextPage)
        assertEquals(3, core.endContextPage)
    }

    @Test
    fun `reconcileContextWindow with the anchor outside the window picks the nearest run`() {
        val core = coreOf()
        core.putPages(3, 4, 7, 8) // window 2..8: page 2, 5 and 6 are missing
        core.window(2, 8)
        core.reconcileContextWindow(anchor = 20)
        assertEquals(7, core.startContextPage)
        assertEquals(8, core.endContextPage)
        core.window(2, 8)
        core.reconcileContextWindow(anchor = 1)
        assertEquals(3, core.startContextPage)
        assertEquals(4, core.endContextPage)
    }

    @Test
    fun `reconcileContextWindow breaks an equal distance tie towards the start side when the anchor is inside the window`() {
        val core = coreOf()
        core.putPages(1, 2, 6, 7) // hole 3..5, anchor 4 is 2 away from page 2 and 2 away from page 6
        core.window(1, 7)
        core.reconcileContextWindow(anchor = 4)
        assertEquals(1, core.startContextPage)
        assertEquals(2, core.endContextPage)
    }

    @Test
    fun `reconcileContextWindow falls back to the nearest island when the window is empty`() {
        val core = coreOf()
        core.putPages(10, 11, 12) // the window 2..3 is gone completely
        core.window(2, 3)
        core.reconcileContextWindow(anchor = 3)
        assertEquals(10, core.startContextPage)
        assertEquals(12, core.endContextPage)
    }

    @Test
    fun `reconcileContextWindow keeps a started paginator started on a cached anchor outside the window`() {
        val core = coreOf()
        core.putPages(2) // an error or progress page that is being loaded would look the same
        core.window(5, 6) // the whole window is gone
        core.reconcileContextWindow(anchor = 2)
        // page 2 is the only cached success page: the re-anchoring finds it on its own
        assertEquals(2, core.startContextPage)
        assertEquals(2, core.endContextPage)
    }

    @Test
    fun `reconcileContextWindow turns a cached non success anchor into the window when nothing else is cached`() {
        val core = coreOf()
        core.cache.setState(
            core.initializerErrorPage(RuntimeException("boom"), 2, mutableListOf(), null),
            silently = true
        )
        core.window(5, 6) // the whole window is gone
        core.reconcileContextWindow(anchor = 2)
        assertEquals(2, core.startContextPage)
        assertEquals(2, core.endContextPage)
    }

    @Test
    fun `reconcileContextWindow leaves the paginator unstarted when nothing is cached`() {
        val core = coreOf()
        core.window(2, 3)
        core.reconcileContextWindow(anchor = 3)
        assertEquals(0, core.startContextPage)
        assertEquals(0, core.endContextPage)
    }

    @Test
    fun `reconcileContextWindow does not change the LRU order`() {
        val evicted = mutableListOf<Int>()
        val lru = MostRecentPagingCache<String>(maxSize = 5, protectContextWindow = false)
        lru.evictionListener = CacheEvictionListener { evicted.add((it as OffsetPageState).page) }
        val core = coreOf(lru)
        core.putPages(1, 2, 3, 4, 5)
        core.window(1, 5)
        core.removeFromCache(3) // order is now 1, 2, 4, 5

        core.reconcileContextWindow(anchor = 5)
        assertEquals(4, core.startContextPage)
        assertEquals(5, core.endContextPage)

        // two more pages push the cache over maxSize once: the least recent page is still 1
        core.putPages(6, 7)
        assertEquals(listOf(1), evicted)
    }

    @Test
    fun `reconcileContextWindow fast path does not change the LRU order`() {
        val evicted = mutableListOf<Int>()
        val lru = MostRecentPagingCache<String>(maxSize = 5, protectContextWindow = false)
        lru.evictionListener = CacheEvictionListener { evicted.add((it as OffsetPageState).page) }
        val core = coreOf(lru)
        core.putPages(5, 4, 3, 2, 1) // page 5 is the least recent one
        core.window(1, 5)

        core.reconcileContextWindow(anchor = 3) // intact window: the fast path

        // a tracked read of the window in ascending order would have made page 1 the least recent
        core.putPages(6)
        assertEquals(listOf(5), evicted)
    }

    @Test
    fun `goPreviousPage on an unstarted paginator still throws`() = runTest {
        val paginator = createEnv().paginator
        assertFailsWith<IllegalStateException> { paginator.goPreviousPage() }
    }
}
