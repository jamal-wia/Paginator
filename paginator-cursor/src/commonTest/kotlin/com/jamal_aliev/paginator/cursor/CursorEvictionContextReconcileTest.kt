// Exercises the deprecated L2 persistent cache, which stays tested until it is removed.
@file:Suppress("DEPRECATION")

package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.core.cache.eviction.CacheEvictionListener
import com.jamal_aliev.paginator.core.extension.isProgressState
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.cache.CursorInMemoryPagingCache
import com.jamal_aliev.paginator.cursor.cache.CursorPagingCache
import com.jamal_aliev.paginator.cursor.cache.eviction.CursorContextWindowPagingCache
import com.jamal_aliev.paginator.cursor.cache.eviction.CursorMostRecentPagingCache
import com.jamal_aliev.paginator.cursor.cache.eviction.CursorQueuedPagingCache
import com.jamal_aliev.paginator.cursor.cache.eviction.CursorTimeLimitedPagingCache
import com.jamal_aliev.paginator.cursor.cache.persistent.CursorPersistentPagingCache
import com.jamal_aliev.paginator.cursor.extension.plus
import com.jamal_aliev.paginator.cursor.load.CursorLoadResult
import com.jamal_aliev.paginator.cursor.page.CursorPageState
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
 * Regression tests for the cursor context window invariant: while the paginator is started, every
 * page on the chain from `startContextCursor` to `endContextCursor` is cached, so the published
 * snapshot is never frozen on a missing boundary and never cut at a hole
 * (`CursorPagingCore.reconcileContextWindow`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CursorEvictionContextReconcileTest {

    private class Env(
        val paginator: CursorPaginator<String, String>,
        val gates: MutableMap<String, CompletableDeferred<Unit>>,
        val failing: MutableSet<String>,
    )

    private fun cb(self: String) = CursorBookmark<String>(prev = null, self = self, next = null)

    /**
     * Chain `p0..p7`, capacity 3, `CursorQueuedPagingCache(maxSize, protectContextWindow) +
     * CursorContextWindowPagingCache()`. A page listed in [Env.gates] suspends in `load` until its
     * gate completes.
     */
    private fun createEnv(
        maxSize: Int = 3,
        protectContextWindow: Boolean = false,
    ): Env {
        val backend = FakeCursorBackend(FakeCursorBackend.defaultPages(pageCount = 8, capacity = 3))
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val failing = mutableSetOf<String>()
        val cache = CursorQueuedPagingCache<String, String>(
            maxSize = maxSize,
            protectContextWindow = protectContextWindow,
        ) + CursorContextWindowPagingCache()
        val core = CursorPagingCore<String, String>(cache = cache, initialCapacity = 3)
        val paginator = CursorPaginator<String, String>(core = core) { cursor ->
            cursor?.self?.let { gates[it] }?.await()
            if (cursor?.self in failing) error("load of ${cursor?.self} failed")
            backend.loadResult(cursor)
        }
        return Env(paginator, gates, failing)
    }

    private suspend fun CursorPaginator<String, String>.snapshotSelves(): List<String> =
        core.snapshot.first().map { it.bookmark.self }

    private fun CursorPaginator<String, String>.window(): Pair<String?, String?> =
        core.startContextCursor?.self to core.endContextCursor?.self

    /**
     * I1/I2: every page on the chain from start to end is cached, and the published snapshot is a
     * linked list of pages that covers the window and extends past it only by non-filled pages.
     */
    private suspend fun assertWindowInvariant(paginator: CursorPaginator<String, String>) {
        val core = paginator.core
        assertTrue(core.isStarted, "paginator must be started")
        val start = core.startContextCursor!!
        val end = core.endContextCursor!!
        val chain = mutableListOf<String>()
        var current: CursorBookmark<String>? = start
        while (current != null) {
            assertNotNull(core.cache.peekStateOf(current.self), "window page ${current.self} must be cached")
            chain += current.self
            if (current.self == end.self) break
            current = core.cache.walkForward(current)
        }
        assertEquals(end.self, chain.last(), "the chain from start must reach end: $chain")

        val snapshot = core.snapshot.first()
        val selves = snapshot.map { it.bookmark.self }
        assertTrue(selves.isNotEmpty(), "snapshot must not be empty")
        for (i in 0 until selves.lastIndex) {
            assertEquals(
                selves[i + 1], core.cache.getCursorOf(selves[i])?.next,
                "snapshot must be a linked chain: $selves"
            )
        }
        for (self in chain) assertTrue(self in selves, "snapshot must cover window page $self: $selves")
        for (state in snapshot) {
            if (state.bookmark.self !in chain) {
                assertTrue(
                    !core.isFilledSuccessState(state),
                    "page ${state.bookmark.self} outside the window must not be a filled success page"
                )
            }
        }
    }

    // ── scenario A: the window boundary is evicted ───────────────────────────

    @Test
    fun `scenario A goNextPage publishes a snapshot after the head page is evicted`() = runTest {
        val env = createEnv()
        val paginator = env.paginator
        val emissions = mutableListOf<List<String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            paginator.core.snapshot.collect { emissions.add(it.map { page -> page.bookmark.self }) }
        }

        paginator.jump(cb("p0"))
        paginator.goNextPage()
        paginator.goNextPage()
        assertEquals(listOf("p0", "p1", "p2"), paginator.snapshotSelves())
        val emissionsBefore = emissions.size

        paginator.goNextPage()

        assertEquals(setOf("p1", "p2", "p3"), paginator.cache.cursors.map { it.self }.toSet())
        assertEquals(listOf("p1", "p2", "p3"), paginator.snapshotSelves())
        assertEquals("p1" to "p3", paginator.window())
        assertTrue(emissions.size > emissionsBefore, "a new snapshot must be emitted")
        assertWindowInvariant(paginator)
    }

    @Test
    fun `scenario A goPreviousPage after head eviction does not throw`() = runTest {
        val env = createEnv()
        val paginator = env.paginator

        paginator.jump(cb("p0"))
        paginator.goNextPage()
        paginator.goNextPage()
        paginator.goNextPage()
        assertEquals(setOf("p1", "p2", "p3"), paginator.cache.cursors.map { it.self }.toSet())

        // before the fix the stale head boundary p0 made this throw EndOfCursorFeedException
        val loaded = paginator.goPreviousPage()

        assertEquals("p0", loaded.bookmark.self)
        assertTrue(paginator.core.isFilledSuccessState(loaded))
        assertNotNull(paginator.cache.peekStateOf("p0"))
        assertWindowInvariant(paginator)
        assertTrue("p0" in paginator.snapshotSelves())
    }

    // ── scenario B: a page inside the window is evicted ──────────────────────

    @Test
    fun `scenario B snapshot is not truncated after a middle page is evicted`() = runTest {
        val env = createEnv()
        val paginator = env.paginator

        paginator.jump(cb("p1"))
        paginator.goNextPage()
        paginator.goNextPage()
        assertEquals(listOf("p1", "p2", "p3"), paginator.snapshotSelves())

        // the progress state of p0 evicts p1, the oldest one
        val loaded = paginator.goPreviousPage()
        assertEquals("p0", loaded.bookmark.self)

        // D-3: the window shrinks to the run that contains the page navigation just loaded
        assertEquals("p0" to "p0", paginator.window())
        assertEquals(listOf("p0"), paginator.core.snapshotSelves())
        assertEquals(listOf("p0"), paginator.snapshotSelves())
        // p2 and p3 stay cached, but outside the window
        assertNotNull(paginator.cache.peekStateOf("p2"))
        assertNotNull(paginator.cache.peekStateOf("p3"))
        assertWindowInvariant(paginator)
    }

    @Test
    fun `progress snapshot is published while the evicting page is loading`() = runTest {
        val env = createEnv()
        val paginator = env.paginator

        paginator.jump(cb("p0"))
        paginator.goNextPage()
        paginator.goNextPage()

        val gate = CompletableDeferred<Unit>()
        env.gates["p3"] = gate
        val job = launch { paginator.goNextPage() }
        testScheduler.advanceUntilIdle()

        // p0 was evicted by the progress state of p3; the loading snapshot must show it
        val loadingSnapshot = paginator.core.snapshot.first()
        assertEquals(listOf("p1", "p2", "p3"), loadingSnapshot.map { it.bookmark.self })
        assertTrue(loadingSnapshot.last().isProgressState())
        assertEquals("p1" to "p2", paginator.window())

        gate.complete(Unit)
        job.join()
        assertEquals(listOf("p1", "p2", "p3"), paginator.snapshotSelves())
        assertTrue(paginator.core.isFilledSuccessState(paginator.cache.peekStateOf("p3")))
    }

    // ── a window damaged from outside the navigation ─────────────────────────

    @Test
    fun `goPreviousPage repairs a window whose start was removed externally`() = runTest {
        val env = createEnv(maxSize = 10)
        val paginator = env.paginator

        paginator.jump(cb("p0"))
        paginator.goNextPage()
        assertEquals("p0", paginator.core.startContextCursor?.self)

        // bypasses navigation: the start boundary now points at a page that is not cached
        paginator.core.removeFromCache("p0")

        // before the fix the stale head boundary made this throw EndOfCursorFeedException
        val loaded = paginator.goPreviousPage()

        assertEquals("p0", loaded.bookmark.self)
        assertEquals("p0" to "p1", paginator.window())
        assertWindowInvariant(paginator)
    }

    @Test
    fun `goPreviousPage with a fully removed window and a failing load keeps the paginator started`() = runTest {
        val env = createEnv(maxSize = 10)
        val paginator = env.paginator

        paginator.jump(cb("p3"))
        paginator.goNextPage()
        // every page of the window is gone and nothing is cached
        paginator.core.removeFromCache("p3")
        paginator.core.removeFromCache("p4")
        assertEquals(emptySet(), paginator.cache.cursors.map { it.self }.toSet())
        env.failing += "p2"

        val loaded = paginator.goPreviousPage()

        assertEquals("p2", loaded.bookmark.self)
        assertTrue(loaded is CursorPageState.Error)
        assertTrue(paginator.core.isStarted, "the paginator must stay started")
        assertEquals("p2" to "p2", paginator.window())
        assertEquals(listOf("p2"), paginator.snapshotSelves())
        assertWindowInvariant(paginator)
    }

    @Test
    fun `goNextPage repairs a window whose end was removed externally`() = runTest {
        val env = createEnv(maxSize = 10)
        val paginator = env.paginator

        paginator.jump(cb("p0"))
        paginator.goNextPage()
        paginator.goNextPage()
        paginator.core.removeFromCache("p2")

        val loaded = paginator.goNextPage()

        assertEquals("p2", loaded.bookmark.self)
        assertEquals("p0" to "p2", paginator.window())
        assertEquals(listOf("p0", "p1", "p2"), paginator.snapshotSelves())
        assertWindowInvariant(paginator)
    }

    @Test
    fun `goNextPage repairs a window with a hole in the middle`() = runTest {
        val env = createEnv(maxSize = 10)
        val paginator = env.paginator

        paginator.jump(cb("p0"))
        repeat(3) { paginator.goNextPage() } // p0..p3
        paginator.core.removeFromCache("p1")

        val loaded = paginator.goNextPage()

        assertEquals("p4", loaded.bookmark.self)
        // the window follows the end the navigation walks from: the run p2..p4
        assertEquals("p2" to "p4", paginator.window())
        assertEquals(listOf("p2", "p3", "p4"), paginator.snapshotSelves())
        assertWindowInvariant(paginator)
    }

    // ── refresh ──────────────────────────────────────────────────────────────

    @Test
    fun `refresh with TimeLimited cache without window protection keeps the window contiguous`() = runTest {
        val timeSource = TestTimeSource()
        val backend = FakeCursorBackend(FakeCursorBackend.defaultPages(pageCount = 8, capacity = 3))
        val cache = CursorTimeLimitedPagingCache<String, String>(
            ttl = 1000.milliseconds,
            protectContextWindow = false,
            timeSource = timeSource,
        )
        val core = CursorPagingCore<String, String>(cache = cache, initialCapacity = 3)
        val paginator = CursorPaginator<String, String>(core = core) { cursor -> backend.loadResult(cursor) }

        // p1 is the oldest page, p0 and p2 are 600 ms younger
        paginator.jump(cb("p1"))
        timeSource += 600.milliseconds
        paginator.goPreviousPage()
        paginator.goNextPage()
        assertEquals("p0" to "p2", paginator.window())
        timeSource += 500.milliseconds // p1 is 1100 ms old and expired, p0 and p2 are not

        paginator.refresh(cursors = listOf(cache.getCursorOf("p0")!!, cache.getCursorOf("p2")!!))

        assertEquals(setOf("p0", "p2"), paginator.cache.cursors.map { it.self }.toSet(), "the expired page is gone")
        assertEquals("p0" to "p0", paginator.window())
        assertEquals(listOf("p0"), paginator.snapshotSelves())
        assertWindowInvariant(paginator)
    }

    // ── cache hits that promote a page from the L2 persistent cache ──────────

    private class FakeCursorPersistentCache : CursorPersistentPagingCache<String, String> {
        val store = mutableMapOf<String, Pair<CursorBookmark<String>, CursorPageState<String, String>>>()
        override suspend fun save(cursor: CursorBookmark<String>, state: CursorPageState<String, String>) {
            store[cursor.self] = cursor to state
        }

        override suspend fun load(self: String) = store[self]
        override suspend fun loadAll() = store.values.toList()
        override suspend fun remove(self: String) {
            store.remove(self)
        }

        override suspend fun clear() {
            store.clear()
        }
    }

    /** `Queued(2, protect = false) + ContextWindow`, chain `p0..p7`, page [persistedSelf] only in L2. */
    private fun createL2Paginator(persistedSelf: String): CursorPaginator<String, String> {
        val backend = FakeCursorBackend(FakeCursorBackend.defaultPages(pageCount = 8, capacity = 3))
        val persistedPage = backend.pages.first { it.self == persistedSelf }
        val bookmark = CursorBookmark<String>(persistedPage.prev, persistedPage.self, persistedPage.next)
        val persistent = FakeCursorPersistentCache()
        persistent.store[persistedSelf] = bookmark to CursorPageState.Success(bookmark, persistedPage.items.toMutableList())
        val cache = CursorQueuedPagingCache<String, String>(maxSize = 2, protectContextWindow = false) +
                CursorContextWindowPagingCache()
        val core = CursorPagingCore<String, String>(cache = cache, persistentCache = persistent, initialCapacity = 3)
        return CursorPaginator<String, String>(core = core) { cursor -> backend.loadResult(cursor) }
    }

    @Test
    fun `goNextPage cache hit from L2 that evicts the start publishes a valid window`() = runTest {
        val paginator = createL2Paginator(persistedSelf = "p2")
        paginator.jump(cb("p0"))
        paginator.goNextPage() // pages p0, p1

        // p2 is promoted from L2 and evicts p0, the oldest one
        val loaded = paginator.goNextPage()

        assertEquals("p2", loaded.bookmark.self)
        assertEquals(setOf("p1", "p2"), paginator.cache.cursors.map { it.self }.toSet())
        assertEquals("p1" to "p2", paginator.window())
        assertEquals(listOf("p1", "p2"), paginator.snapshotSelves())
        assertWindowInvariant(paginator)
    }

    @Test
    fun `goPreviousPage cache hit from L2 that evicts a window page publishes a valid window`() = runTest {
        val paginator = createL2Paginator(persistedSelf = "p2")
        paginator.jump(cb("p3"))
        paginator.goNextPage() // pages p3, p4

        // p2 is promoted from L2 and evicts p3, the oldest one: a hole inside the window
        val loaded = paginator.goPreviousPage()

        assertEquals("p2", loaded.bookmark.self)
        assertEquals(setOf("p2", "p4"), paginator.cache.cursors.map { it.self }.toSet())
        assertEquals("p2" to "p2", paginator.window())
        assertEquals(listOf("p2"), paginator.snapshotSelves())
        assertWindowInvariant(paginator)
    }

    // ── cancellation ─────────────────────────────────────────────────────────

    @Test
    fun `cancelled goNextPage after eviction publishes a valid window`() = runTest {
        val env = createEnv()
        val paginator = env.paginator

        paginator.jump(cb("p0"))
        paginator.goNextPage()
        paginator.goNextPage()

        env.gates["p3"] = CompletableDeferred() // never completes
        val job = launch { paginator.goNextPage() }
        testScheduler.advanceUntilIdle()
        job.cancel()
        testScheduler.advanceUntilIdle()

        assertEquals(setOf("p1", "p2"), paginator.cache.cursors.map { it.self }.toSet(), "p3 is rolled back, p0 stays evicted")
        assertEquals(listOf("p1", "p2"), paginator.snapshotSelves())
        assertEquals("p1" to "p2", paginator.window())
        assertWindowInvariant(paginator)
    }

    @Test
    fun `cancelled goPreviousPage after eviction publishes a valid window`() = runTest {
        val env = createEnv()
        val paginator = env.paginator

        paginator.jump(cb("p2"))
        paginator.goNextPage()
        paginator.goNextPage()
        assertEquals(setOf("p2", "p3", "p4"), paginator.cache.cursors.map { it.self }.toSet())

        env.gates["p1"] = CompletableDeferred() // never completes
        val job = launch { paginator.goPreviousPage() }
        testScheduler.advanceUntilIdle()
        job.cancel()
        testScheduler.advanceUntilIdle()

        assertEquals(setOf("p3", "p4"), paginator.cache.cursors.map { it.self }.toSet(), "p1 is rolled back, p2 stays evicted")
        assertEquals(listOf("p3", "p4"), paginator.snapshotSelves())
        assertEquals("p3" to "p4", paginator.window())
        assertWindowInvariant(paginator)
    }

    // ── regression: protected strategies are untouched ───────────────────────

    @Test
    fun `default protected caches do not change window behaviour`() = runTest {
        val env = createEnv(maxSize = 3, protectContextWindow = true)
        val paginator = env.paginator
        val trace = mutableListOf<Pair<Pair<String?, String?>, List<String>>>()
        suspend fun record() {
            trace += paginator.window() to paginator.snapshotSelves()
        }

        paginator.jump(cb("p0")); record()
        paginator.goNextPage(); record()
        paginator.goNextPage(); record()
        paginator.goNextPage(); record() // the protected window may exceed maxSize
        paginator.jump(cb("p6")); record()
        paginator.goPreviousPage(); record()

        assertEquals(
            listOf<Pair<Pair<String?, String?>, List<String>>>(
                ("p0" to "p0") to listOf("p0"),
                ("p0" to "p1") to listOf("p0", "p1"),
                ("p0" to "p2") to listOf("p0", "p1", "p2"),
                ("p0" to "p3") to listOf("p0", "p1", "p2", "p3"),
                ("p6" to "p6") to listOf("p6"),
                ("p5" to "p6") to listOf("p5", "p6"),
            ),
            trace
        )
        assertEquals(setOf("p5", "p6"), paginator.cache.cursors.map { it.self }.toSet())
        assertWindowInvariant(paginator)
    }

    // ── reconcileContextWindow unit tests ────────────────────────────────────

    private fun coreOf(
        cache: CursorPagingCache<String, String> = CursorInMemoryPagingCache(),
    ) = CursorPagingCore<String, String>(cache = cache, initialCapacity = 2)

    /** Puts pages `p<from>..p<to>` into the cache as one linked chain `p0 -> p1 -> ... -> p<chainEnd>`. */
    private fun CursorPagingCore<String, String>.putChain(from: Int, to: Int, chainEnd: Int = 9) {
        for (i in from..to) {
            val bookmark = CursorBookmark<String>(
                prev = if (i == 0) null else "p${i - 1}",
                self = "p$i",
                next = if (i == chainEnd) null else "p${i + 1}",
            )
            cache.setState(bookmark, CursorPageState.Success(bookmark, mutableListOf("a$i", "b$i")), silently = true)
        }
    }

    private fun CursorPagingCore<String, String>.window(start: Int, end: Int) {
        startContextCursor = cache.getCursorOf("p$start")
        endContextCursor = cache.getCursorOf("p$end")
    }

    private fun CursorPagingCore<String, String>.bounds(): Pair<String?, String?> =
        startContextCursor?.self to endContextCursor?.self

    @Test
    fun `reconcileContextWindow does nothing when not started`() {
        val core = coreOf()
        core.putChain(2, 3)
        core.reconcileContextWindow(anchor = "p2")
        assertEquals(Pair<String?, String?>(null, null), core.bounds())
    }

    @Test
    fun `reconcileContextWindow keeps an intact window`() {
        val core = coreOf()
        core.putChain(0, 5)
        core.window(1, 4)
        core.reconcileContextWindow(anchor = "p2")
        assertEquals("p1" to "p4", core.bounds())
    }

    @Test
    fun `reconcileContextWindow keeps an intact single page window`() {
        val core = coreOf()
        core.putChain(0, 5)
        core.window(3, 3)
        core.reconcileContextWindow(anchor = "p3")
        assertEquals("p3" to "p3", core.bounds())
    }

    @Test
    fun `reconcileContextWindow picks the run on the end side that contains the anchor`() {
        val core = coreOf()
        core.putChain(0, 6)
        core.window(0, 6)
        core.removeFromCache("p3")
        core.reconcileContextWindow(anchor = "p5")
        assertEquals("p4" to "p6", core.bounds())
    }

    @Test
    fun `reconcileContextWindow picks the run on the start side that contains the anchor`() {
        val core = coreOf()
        core.putChain(0, 6)
        core.window(0, 6)
        core.removeFromCache("p3")
        core.reconcileContextWindow(anchor = "p1")
        assertEquals("p0" to "p2", core.bounds())
    }

    @Test
    fun `reconcileContextWindow finds a middle run reachable only through the anchor`() {
        val core = coreOf()
        core.putChain(0, 6)
        core.window(0, 6)
        core.removeFromCache("p2")
        core.removeFromCache("p4")
        core.reconcileContextWindow(anchor = "p3")
        assertEquals("p3" to "p3", core.bounds())
    }

    @Test
    fun `reconcileContextWindow with an evicted start anchor takes the run behind the old start`() {
        val core = coreOf()
        core.putChain(0, 5)
        core.window(0, 5)
        core.removeFromCache("p0")
        core.reconcileContextWindow(anchor = "p0")
        assertEquals("p1" to "p5", core.bounds())
    }

    @Test
    fun `reconcileContextWindow with an evicted end anchor takes the run before the old end`() {
        val core = coreOf()
        core.putChain(0, 5)
        core.window(0, 5)
        core.removeFromCache("p5")
        core.reconcileContextWindow(anchor = "p5")
        assertEquals("p0" to "p4", core.bounds())
    }

    @Test
    fun `reconcileContextWindow with an evicted end anchor prefers the end side over the start side`() {
        val core = coreOf()
        core.putChain(0, 6)
        core.window(0, 6)
        core.removeFromCache("p3")
        core.removeFromCache("p6")
        // runs: p0..p2 (start side) and p4..p5 (end side, reached through the stale end boundary)
        core.reconcileContextWindow(anchor = "p6")
        assertEquals("p4" to "p5", core.bounds())
    }

    @Test
    fun `reconcileContextWindow never walks past the old window`() {
        val core = coreOf()
        core.putChain(0, 9)
        core.window(3, 5)
        core.removeFromCache("p4")
        core.reconcileContextWindow(anchor = "p3")
        assertEquals("p3" to "p3", core.bounds())
        core.window(3, 5)
        core.reconcileContextWindow(anchor = "p5")
        assertEquals("p5" to "p5", core.bounds())
    }

    @Test
    fun `reconcileContextWindow falls back to a nearby chain when the window is gone`() {
        val core = coreOf()
        core.putChain(0, 9)
        core.window(2, 3)
        core.removeFromCache("p2")
        core.removeFromCache("p3")
        core.reconcileContextWindow(anchor = "p3")
        // the stale boundary objects still have links, but their neighbours p1 and p4 are not
        // part of the old window: re-anchoring picks the nearest cached filled page group
        val (start, end) = core.bounds()
        assertTrue(start != null && end != null, "a filled page is cached, the window must be re-anchored")
        assertTrue(core.cache.peekStateOf(start) != null)
        assertTrue(core.cache.peekStateOf(end) != null)
    }

    @Test
    fun `reconcileContextWindow takes a cached anchor outside the window when the whole window is gone`() {
        val core = coreOf()
        core.putChain(5, 6)
        core.window(5, 6)
        core.removeFromCache("p5")
        core.removeFromCache("p6")
        core.putChain(2, 2)
        core.reconcileContextWindow(anchor = "p2")
        assertEquals("p2" to "p2", core.bounds())
    }

    @Test
    fun `reconcileContextWindow leaves the paginator unstarted when nothing is cached`() {
        val core = coreOf()
        core.putChain(2, 3)
        core.window(2, 3)
        core.removeFromCache("p2")
        core.removeFromCache("p3")
        core.reconcileContextWindow(anchor = "p3")
        assertEquals(Pair<String?, String?>(null, null), core.bounds())
        assertTrue(!core.isStarted)
    }

    @Test
    fun `reconcileContextWindow does not change the LRU order`() {
        val evicted = mutableListOf<String>()
        val lru = CursorMostRecentPagingCache<String, String>(maxSize = 5, protectContextWindow = false)
        lru.evictionListener = CacheEvictionListener { evicted.add((it as CursorPageState<*, *>).bookmark.self as String) }
        val core = coreOf(lru)
        core.putChain(0, 4)
        core.window(0, 4)
        core.removeFromCache("p2") // order is now p0, p1, p3, p4

        core.reconcileContextWindow(anchor = "p4")
        assertEquals("p3" to "p4", core.bounds())

        // two more pages push the cache over maxSize once: the least recent page is still p0
        core.putChain(5, 6)
        assertEquals(listOf("p0"), evicted)
    }

    @Test
    fun `reconcileContextWindow fast path does not change the LRU order`() {
        val evicted = mutableListOf<String>()
        val lru = CursorMostRecentPagingCache<String, String>(maxSize = 5, protectContextWindow = false)
        lru.evictionListener = CacheEvictionListener { evicted.add((it as CursorPageState<*, *>).bookmark.self as String) }
        val core = coreOf(lru)
        core.putChain(0, 4)
        core.window(0, 4)
        // make p4 the least recent page: only tracked reads in ascending order would change that
        for (self in listOf("p3", "p2", "p1", "p0")) lru.getStateOf(self)

        core.reconcileContextWindow(anchor = "p2") // intact window: the fast path

        core.putChain(5, 5)
        assertEquals(listOf("p4"), evicted)
    }

    @Test
    fun `goPreviousPage on an unstarted paginator still throws`() = runTest {
        val paginator = createEnv().paginator
        assertFailsWith<IllegalStateException> { paginator.goPreviousPage() }
    }
}
