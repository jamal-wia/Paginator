package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.core.extension.isErrorState
import com.jamal_aliev.paginator.core.extension.isSuccessState
import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.cache.PagingCache
import com.jamal_aliev.paginator.offset.cache.eviction.ContextWindowPagingCache
import com.jamal_aliev.paginator.offset.cache.eviction.MostRecentPagingCache
import com.jamal_aliev.paginator.offset.cache.eviction.NearestPagingCache
import com.jamal_aliev.paginator.offset.cache.eviction.QueuedPagingCache
import com.jamal_aliev.paginator.offset.cache.eviction.TimeLimitedPagingCache
import com.jamal_aliev.paginator.offset.extension.observePages
import com.jamal_aliev.paginator.offset.extension.plus
import com.jamal_aliev.paginator.offset.load.LoadResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** Regression tests from the second review of observePages: asFlow() with LRU / TTL caches, an emptied table, resize during refresh, a source failing with a CancellationException, transactions. */
@OptIn(ExperimentalCoroutinesApi::class)
class ObservePagesInteractionTest {

    private class FakeTable(rowCount: Int, private val capacity: Int = 3) {
        val rows = MutableStateFlow(List(rowCount) { "r$it" })
        val activePages = mutableListOf<Int>()
        var subscriptions = 0
            private set
        val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()

        /** Gates a load of a page AFTER it has read the table. */
        val loadGates = mutableMapOf<Int, CompletableDeferred<Unit>>()

        suspend fun loadPage(page: Int, limit: Int = capacity): LoadResult<String> {
            val data = rows.value.drop((page - 1) * limit).take(limit)
            loadGates.remove(page)?.await()
            return LoadResult(data)
        }

        fun observePage(page: Int, limit: Int = capacity): Flow<List<String>> {
            val offset = (page - 1) * limit
            return rows.map { it.drop(offset).take(limit) }
                .onStart {
                    subscriptions++
                    activePages += page
                    gates.remove(page)?.await()
                }
                .onCompletion { activePages -= page }
        }

        fun update(index: Int, value: String) {
            rows.value = rows.value.toMutableList().also { it[index] = value }
        }
    }

    private fun livePaginator(core: PagingCore<String> = PagingCore()): MutablePaginator<String> =
        MutablePaginator(core = core) { error("no load configured") }
            .apply { this.core.resize(capacity = 3, resize = false, silently = true) }

    private fun TestScope.observe(paginator: MutablePaginator<String>, table: FakeTable) =
        paginator.apply { load = { page -> table.loadPage(page, core.capacity) } }
            .observePages(backgroundScope) { page -> table.observePage(page, core.capacity) }

    private fun TestScope.observeWith(cache: PagingCache<String>, table: FakeTable) =
        livePaginator(PagingCore(cache = cache)).also { observe(it, table) }

    // ── an emptied table shows the empty state ───────────────────────────────

    @Test
    fun emptying_the_table_publishes_an_empty_snapshot() = runTest {
        val table = FakeTable(rowCount = 6)
        val paginator = livePaginator()
        observe(paginator, table)
        paginator.jump(BookmarkInt(1))
        paginator.goNextPage()
        runCurrent()
        var last: List<String> = listOf("nothing yet")
        backgroundScope.launch { paginator.core.snapshot.collect { s -> last = s.flatMap { it.data } } }
        runCurrent()
        assertEquals(List(6) { "r$it" }, last)

        table.rows.value = emptyList() // "DELETE FROM users"
        runCurrent()

        assertTrue(paginator.cache.peekStateOf(1)!!.data.isEmpty(), "cache is updated")
        assertEquals(emptyList(), last, "the UI still shows deleted rows: $last")
    }

    // ── asFlow() active: live writes and bookkeeping are not page use ───────────

    @Test
    fun LRU_off_screen_update_with_cacheFlow_does_not_reorder_pages() = runTest {
        val table = FakeTable(rowCount = 60)
        val paginator = observeWith(MostRecentPagingCache(maxSize = 3, protectContextWindow = false), table)
        backgroundScope.launch { paginator.core.asFlow().collect { } }
        runCurrent()
        for (page in listOf(9, 5, 1)) {
            paginator.jump(BookmarkInt(page))
            runCurrent()
        }

        table.update(index = 24, value = "edited") // page 9, off screen
        runCurrent()
        paginator.jump(BookmarkInt(13))
        runCurrent()

        assertEquals(listOf(1, 5, 13), paginator.cache.pages, "page 9 was made recent by a DB write")
    }

    @Test
    fun TTL_refreshOnAccess_live_write_with_cacheFlow_does_not_restart_timers() = runTest {
        val time = TestTimeSource()
        val table = FakeTable(rowCount = 60)
        val paginator = observeWith(
            TimeLimitedPagingCache(ttl = 10.seconds, refreshOnAccess = true, protectContextWindow = false, timeSource = time),
            table,
        )
        backgroundScope.launch { paginator.core.asFlow().collect { } }
        runCurrent()
        paginator.jump(BookmarkInt(1))
        paginator.jump(BookmarkInt(5))
        runCurrent()
        time += 11.seconds

        table.update(index = 12, value = "edited") // page 5 (visible) changes
        runCurrent()
        paginator.jump(BookmarkInt(9))
        runCurrent()

        assertFalse(1 in paginator.cache.pages, "off-screen page 1 got its TTL refreshed by a DB write")
    }

    @Test
    fun control_TTL_refreshOnAccess_live_write_without_cacheFlow() = runTest {
        val time = TestTimeSource()
        val table = FakeTable(rowCount = 60)
        val paginator = observeWith(
            TimeLimitedPagingCache(ttl = 10.seconds, refreshOnAccess = true, protectContextWindow = false, timeSource = time),
            table,
        )
        paginator.jump(BookmarkInt(1))
        paginator.jump(BookmarkInt(5))
        runCurrent()
        time += 11.seconds

        table.update(index = 12, value = "edited")
        runCurrent()
        paginator.jump(BookmarkInt(9))
        runCurrent()

        assertFalse(1 in paginator.cache.pages, "control")
    }

    private suspend fun TestScope.ttlNeighbourScenario(update: Boolean): List<Int> {
        val time = TestTimeSource()
        val table = FakeTable(rowCount = 8) // page 3 = r6, r7 (partial, outside the window)
        val paginator = observeWith(
            TimeLimitedPagingCache(ttl = 10.seconds, refreshOnAccess = true, protectContextWindow = false, timeSource = time),
            table,
        )
        paginator.jump(BookmarkInt(1))
        paginator.goNextPage()
        paginator.goNextPage()
        runCurrent()
        time += 11.seconds
        if (update) {
            table.update(index = 0, value = "edited") // page 1, visible
            runCurrent()
        }
        paginator.jump(BookmarkInt(20))
        runCurrent()
        return paginator.cache.pages
    }

    @Test
    fun TTL_refreshOnAccess_visible_update_refreshes_off_window_neighbour() = runTest {
        val control = ttlNeighbourScenario(update = false)
        val withUpdate = ttlNeighbourScenario(update = true)
        assertEquals(control, withUpdate, "a DB write changed which pages expired")
    }

    @Test
    fun source_ending_with_a_timeout_after_first_emission_is_reported() = runTest {
        val errors = mutableListOf<Int>()
        val paginator = livePaginator()
        paginator.load = { page -> LoadResult(List(3) { "p${page}_$it" }) }
        paginator.observePages(backgroundScope, onError = { page, _ -> errors += page }) { page ->
            kotlinx.coroutines.flow.flow {
                emit(List(3) { "p${page}_$it" })
                // e.g. a `.timeout(…)` operator / withTimeout inside the query pipeline
                kotlinx.coroutines.withTimeout(1) { kotlinx.coroutines.awaitCancellation() }
            }
        }
        paginator.jump(BookmarkInt(1))
        runCurrent()
        testScheduler.advanceTimeBy(10)
        runCurrent()
        assertTrue(errors == listOf(1) || paginator.core.isDirty(1), "page 1 silently stopped being live")
    }

    // ── resize during a refresh ────────────

    @Test
    fun resize_during_refresh_does_not_produce_an_Error_page() = runTest {
        val table = FakeTable(rowCount = 30)
        val paginator = livePaginator()
        observe(paginator, table)
        paginator.jump(BookmarkInt(1))
        paginator.goNextPage()
        runCurrent()

        val gate = CompletableDeferred<Unit>().also { table.loadGates[2] = it } // page 2's reload is slow
        val refresh = launch { paginator.refresh(pages = listOf(2)) }
        runCurrent()
        paginator.core.resize(capacity = 2, resize = false, silently = true)
        runCurrent()
        gate.complete(Unit)
        refresh.join()
        runCurrent()

        val state = paginator.cache.peekStateOf(2)
        assertFalse(state.isErrorState(), "refresh committed $state")
        // The live query was opened for the old capacity: it is re-queried with the new one.
        assertEquals(listOf("r2", "r3"), state!!.data)
    }

    // ── live update inside a successful transaction ─────────────────────

    @Test
    fun live_update_inside_successful_transaction_is_kept() = runTest {
        val table = FakeTable(rowCount = 30)
        val paginator = livePaginator()
        observe(paginator, table)
        paginator.jump(BookmarkInt(1))
        runCurrent()
        paginator.transaction {
            table.update(index = 0, value = "edited")
            runCurrent()
        }
        runCurrent()
        assertEquals("edited", paginator.cache.peekStateOf(1)!!.data.first())
        assertTrue(paginator.cache.peekStateOf(1).isSuccessState())
    }

    // ── live subscriptions follow the cache through eviction strategies, silent navigation, rollback ──

    /**
     * After the pending work has run, the live queries are exactly the cached pages and no page
     * has more than one query (a duplicate would show up as a repeated page in the sorted list).
     */
    private fun TestScope.assertSubscriptionsMatchCache(paginator: MutablePaginator<String>, table: FakeTable) {
        runCurrent()
        assertEquals(paginator.cache.pages.sorted(), table.activePages.sorted(), "live queries differ from the cache")
    }

    @Test
    fun `ContextWindowPagingCache silent jump back unsubscribes the old location and a silent goNextPage subscribes the next page`() = runTest {
        val table = FakeTable(rowCount = 60)
        val paginator = observeWith(ContextWindowPagingCache(margin = 0), table)

        paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        assertSubscriptionsMatchCache(paginator, table)
        assertEquals(listOf(5, 6), table.activePages.sorted())

        // The window moves to page 1: the write of the jump target evicts pages 5 and 6 at once.
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        assertSubscriptionsMatchCache(paginator, table)
        assertEquals(listOf(1), table.activePages, "pages 5 and 6 must not keep a live query")

        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        assertSubscriptionsMatchCache(paginator, table)
        assertEquals(listOf(1, 2), paginator.cache.pages)
        assertEquals(listOf(1, 2), table.activePages.sorted())
    }

    @Test
    fun `ContextWindowPagingCache silent jump far away cancels every earlier query`() = runTest {
        val table = FakeTable(rowCount = 60)
        val paginator = observeWith(ContextWindowPagingCache(margin = 0), table)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        assertSubscriptionsMatchCache(paginator, table)
        assertEquals(listOf(1, 2, 3), table.activePages.sorted())

        paginator.jump(BookmarkInt(10), silentlyLoading = true, silentlyResult = true)

        assertSubscriptionsMatchCache(paginator, table)
        assertEquals(listOf(10), paginator.cache.pages)
        assertEquals(listOf(10), table.activePages, "pages 1..3 must not keep a live query")
    }

    @Test
    fun `NearestPagingCache keeps live queries equal to a three page window in both directions`() = runTest {
        val table = FakeTable(rowCount = 60)
        val paginator = observeWith(NearestPagingCache(maxSize = 3), table)

        // visible navigation forward
        paginator.jump(BookmarkInt(1))
        repeat(3) { paginator.goNextPage() }
        assertSubscriptionsMatchCache(paginator, table)
        assertEquals(listOf(2, 3, 4), table.activePages.sorted())

        // visible navigation backward
        paginator.goPreviousPage()
        assertSubscriptionsMatchCache(paginator, table)
        assertEquals(listOf(1, 2, 3), table.activePages.sorted())

        // silent navigation forward and backward again
        repeat(2) { paginator.goNextPage(silentlyLoading = true, silentlyResult = true) }
        assertSubscriptionsMatchCache(paginator, table)
        assertEquals(listOf(3, 4, 5), table.activePages.sorted())
        paginator.goPreviousPage(silentlyLoading = true, silentlyResult = true)
        assertSubscriptionsMatchCache(paginator, table)
        assertEquals(listOf(2, 3, 4), table.activePages.sorted())
    }

    @Test
    fun `a protected context window wider than maxSize keeps every page of the window live`() = runTest {
        val table = FakeTable(rowCount = 60)
        val paginator = observeWith(QueuedPagingCache(maxSize = 2, protectContextWindow = true), table)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        repeat(2) { paginator.goNextPage(silentlyLoading = true, silentlyResult = true) }

        // documented: with protectContextWindow = true maxSize may be exceeded by the window
        assertSubscriptionsMatchCache(paginator, table)
        assertEquals(listOf(1, 2, 3), paginator.cache.pages)
        assertEquals(listOf(1, 2, 3), table.activePages.sorted())
    }

    @Test
    fun `FIFO composed with ContextWindowPagingCache keeps live queries equal to the cache after re-insertion`() = runTest {
        val table = FakeTable(rowCount = 60)
        // QueuedPagingCache is the outer strategy: ContextWindowPagingCache evicts first and the
        // queue keeps entries of pages it did not evict itself.
        val paginator = observeWith(
            QueuedPagingCache<String>(maxSize = 3, protectContextWindow = false) + ContextWindowPagingCache(margin = 0),
            table,
        )
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        repeat(2) { paginator.goNextPage(silentlyLoading = true, silentlyResult = true) }
        paginator.jump(BookmarkInt(10), silentlyLoading = true, silentlyResult = true) // window evicts 1..3
        assertSubscriptionsMatchCache(paginator, table)
        assertEquals(listOf(10), table.activePages)

        // pages 1..3 come back after the window evicted them, then the queue evicts the oldest
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        repeat(3) { paginator.goNextPage(silentlyLoading = true, silentlyResult = true) }

        assertSubscriptionsMatchCache(paginator, table)
        assertEquals(listOf(2, 3, 4), paginator.cache.pages)
        assertEquals(listOf(2, 3, 4), table.activePages.sorted())
    }

    @Test
    fun `a rolled back jump over a bounded cache re-subscribes exactly the restored pages`() = runTest {
        val table = FakeTable(rowCount = 60)
        val paginator = observeWith(NearestPagingCache(maxSize = 3), table)
        paginator.jump(BookmarkInt(1))
        repeat(2) { paginator.goNextPage() }
        assertSubscriptionsMatchCache(paginator, table)
        val subscriptionsBefore = table.subscriptions

        assertFailsWith<IllegalStateException> {
            paginator.transaction {
                paginator.jump(BookmarkInt(10)) // the bounded cache evicts page 1 (farthest from 10) and subscribes page 10
                runCurrent()
                assertEquals(listOf(2, 3, 10), paginator.cache.pages)
                assertEquals(listOf(2, 3, 10), table.activePages.sorted())
                table.update(index = 4, value = "written during the transaction") // row of page 2
                runCurrent()
                error("boom")
            }
        }

        assertSubscriptionsMatchCache(paginator, table)
        assertEquals(listOf(1, 2, 3), paginator.cache.pages)
        assertEquals(listOf(1, 2, 3), table.activePages.sorted())
        assertEquals(
            listOf("r3", "written during the transaction", "r5"), paginator.cache.peekStateOf(2)!!.data,
            "the restored page must be re-queried, the database wins over the rolled back copy",
        )
        // page 10 once, then every restored page re-queried once
        assertEquals(subscriptionsBefore + 1 + 3, table.subscriptions)
    }
}
