package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.core.extension.isErrorState
import com.jamal_aliev.paginator.core.extension.isSuccessState
import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.cache.PagingCache
import com.jamal_aliev.paginator.offset.cache.eviction.MostRecentPagingCache
import com.jamal_aliev.paginator.offset.cache.eviction.TimeLimitedPagingCache
import com.jamal_aliev.paginator.offset.extension.observePages
import com.jamal_aliev.paginator.offset.load.LoadResult
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** Regression tests from the second review of observePages: asFlow() with LRU / TTL caches, an emptied table, resize during refresh, a source failing with a CancellationException, transactions. */
class ObservePagesInteractionTest {

    private class FakeTable(rowCount: Int, private val capacity: Int = 3) {
        val rows = MutableStateFlow(List(rowCount) { "r$it" })
        val activePages = mutableListOf<Int>()
        val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()

        fun observePage(page: Int, limit: Int = capacity): Flow<List<String>> {
            val offset = (page - 1) * limit
            return rows.map { it.drop(offset).take(limit) }
                .onStart {
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
        MutablePaginator(core = core) { error("original load") }
            .apply { this.core.resize(capacity = 3, resize = false, silently = true) }

    private fun TestScope.observe(paginator: MutablePaginator<String>, table: FakeTable) =
        paginator.observePages(backgroundScope) { page -> table.observePage(page, core.capacity) }

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

        val gate = CompletableDeferred<Unit>().also { table.gates[2] = it } // page 2's new query is slow
        val refresh = launch { paginator.refresh(pages = listOf(2)) }
        runCurrent()
        paginator.core.resize(capacity = 2, resize = false, silently = true)
        runCurrent()
        gate.complete(Unit)
        refresh.join()
        runCurrent()

        val state = paginator.cache.peekStateOf(2)
        assertFalse(state.isErrorState(), "refresh committed $state")
        // Once the waited-for query delivered, the page is re-queried with the new capacity.
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
}
