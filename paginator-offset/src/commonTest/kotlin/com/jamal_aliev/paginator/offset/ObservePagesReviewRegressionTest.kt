package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.core.extension.isErrorState
import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
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

/**
 * Regression tests from the deep review of observePages: cancellation, silent operations,
 * relabelling, window invariants, dirty flags and failing callbacks.
 */
class ObservePagesReviewRegressionTest {

    private class FakeTable(rowCount: Int, private val capacity: Int = 3) {
        val rows = MutableStateFlow(List(rowCount) { "r$it" })
        val activePages = mutableListOf<Int>()
        var subscriptions = 0
        val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()

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

    private fun livePaginator(load: suspend Paginator<String>.(Int) -> LoadResult<String> = {
        error("the original load must not be called while observing")
    }): MutablePaginator<String> =
        MutablePaginator(core = PagingCore(), load = load)
            .apply { core.resize(capacity = 3, resize = false, silently = true) }

    private suspend fun MutablePaginator<String>.loadPages(count: Int) {
        jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        repeat(count - 1) { goNextPage(silentlyLoading = true, silentlyResult = true) }
    }

    private fun TestScope.observe(paginator: MutablePaginator<String>, table: FakeTable) =
        paginator.observePages(backgroundScope) { page -> table.observePage(page, core.capacity) }

    private fun MutablePaginator<String>.dataOf(page: Int): List<String> = cache.peekStateOf(page)!!.data

    @Test
    fun cancelling_the_job_during_a_pending_load_does_not_produce_an_Error_page() = runTest {
        val table = FakeTable(rowCount = 20)
        var originalLoads = 0
        val paginator = livePaginator { page ->
            originalLoads++
            LoadResult(MutableList(3) { "load_p${page}_$it" })
        }
        val job = observe(paginator, table)
        table.gates[1] = CompletableDeferred() // the first emission of page 1 never comes

        val jump = launch { paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true) }
        runCurrent()
        job.cancel()
        runCurrent()
        jump.join()

        val state = paginator.cache.peekStateOf(1)
        assertFalse(
            state.isErrorState(),
            "cancelling observePages turned an in-flight load into an Error page: $state",
        )
    }

    @Test
    fun a_cancelled_refresh_leaves_the_restored_page_live() = runTest {
        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator()
        observe(paginator, table)
        paginator.loadPages(2)
        runCurrent()

        table.gates[2] = CompletableDeferred() // the refresh's new query of page 2 hangs
        val refresh = launch { paginator.refresh(pages = listOf(2)) }
        runCurrent()
        refresh.cancel()
        runCurrent()
        // refresh rolled page 2 back to its old Success state

        table.update(index = 4, value = "edited")
        runCurrent()

        assertTrue(2 in table.activePages, "page 2 is cached but has no live query: ${table.activePages}")
        assertEquals(listOf("r3", "edited", "r5"), paginator.dataOf(2))
    }

    @Test
    fun a_cancelled_goNextPage_reload_leaves_the_partial_page_live() = runTest {
        val table = FakeTable(rowCount = 5) // page 2 = r3, r4 (partial)
        val paginator = livePaginator()
        observe(paginator, table)
        paginator.loadPages(2)
        runCurrent()

        table.gates[2] = CompletableDeferred()
        val next = launch { paginator.goNextPage(silentlyLoading = true, silentlyResult = true) }
        runCurrent()
        next.cancel()
        runCurrent()

        table.update(index = 4, value = "edited")
        runCurrent()

        assertEquals(listOf("r3", "edited"), paginator.dataOf(2), "page 2 stopped being live")
    }

    @Test
    fun a_live_update_never_buries_a_non_filled_page_inside_the_window() = runTest {
        val sources = mutableMapOf<Int, MutableStateFlow<List<String>>>()
        fun src(page: Int) = sources.getOrPut(page) { MutableStateFlow(List(3) { "p${page}_$it" }) }
        val paginator = livePaginator()
        paginator.observePages(backgroundScope) { page -> src(page) }
        paginator.loadPages(3)
        runCurrent()
        assertEquals(1..3, paginator.cache.startContextPage..paginator.cache.endContextPage)

        src(2).value = listOf("p2_0") // page 2 shrinks, its neighbours stay full
        runCurrent()

        val start = paginator.cache.startContextPage
        val end = paginator.cache.endContextPage
        assertFalse(
            2 in (start + 1) until end || (start < 2 && end > 2),
            "non-filled page 2 is buried inside the window $start..$end",
        )
    }

    @Test
    fun a_silent_removeState_unsubscribes_the_dropped_page() = runTest {
        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator()
        observe(paginator, table)
        paginator.loadPages(3)
        runCurrent()

        paginator.removeState(3, silently = true)
        runCurrent()

        assertEquals(listOf(1, 2), paginator.cache.pages)
        assertEquals(listOf(1, 2), table.activePages.sorted(), "page 3 left the cache but its query lives on")
    }

    @Test
    fun removeState_relabelling_re_queries_the_shifted_pages() = runTest {
        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator()
        observe(paginator, table)
        paginator.loadPages(3)
        runCurrent()

        paginator.removeState(2)
        runCurrent()

        // page 2 now holds the old page 3 (r6..r8) but is backed by source(2) = r3..r5
        val shown = paginator.dataOf(2)
        table.update(index = 0, value = "unrelated change on page 1")
        runCurrent()
        assertEquals(shown, paginator.dataOf(2), "an unrelated table change rewrote page 2: $shown -> ${paginator.dataOf(2)}")
    }

    @Test
    fun one_table_change_touching_three_visible_pages_publishes_one_snapshot() = runTest {
        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator()
        observe(paginator, table)
        paginator.loadPages(3)
        paginator.core.snapshot()
        runCurrent()
        var emissions = 0
        backgroundScope.launch { paginator.core.snapshot.collect { emissions++ } }
        runCurrent()
        val before = emissions

        table.rows.value = table.rows.value.drop(1) // shifts every page
        runCurrent()

        assertEquals(1, emissions - before)
        assertEquals(listOf("r1", "r2", "r3"), paginator.dataOf(1))
        assertEquals(listOf("r4", "r5", "r6"), paginator.dataOf(2))
    }

    @Test
    fun a_live_update_winning_over_refresh_does_not_leave_the_page_dirty() = runTest {
        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator()
        observe(paginator, table)
        paginator.loadPages(3)
        runCurrent()
        paginator.core.markDirty(2)

        val gate = CompletableDeferred<Unit>().also { table.gates[3] = it }
        val refresh = launch { paginator.refresh(pages = listOf(2, 3)) }
        runCurrent()
        table.update(index = 4, value = "edited")
        runCurrent()
        gate.complete(Unit)
        refresh.join()
        runCurrent()

        assertEquals(listOf("r3", "edited", "r5"), paginator.dataOf(2))
        assertFalse(paginator.core.isDirty(2), "page 2 got fresh DB data yet stays dirty -> redundant reload")
    }

    @Test
    fun a_throwing_onError_does_not_kill_every_page_subscription() = runTest {
        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator()
        val parent = kotlinx.coroutines.SupervisorJob()
        var escaped: Throwable? = null
        val scope = kotlinx.coroutines.CoroutineScope(
            coroutineContext + parent + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> escaped = e }
        )
        val job = paginator.observePages(scope, onError = { _, e -> throw e }) { page ->
            if (page == 2) kotlinx.coroutines.flow.flow { emit(listOf("a", "b", "c")); error("page 2 query died") }
            else table.observePage(page)
        }
        paginator.loadPages(2)
        runCurrent()

        val alive = job.isActive
        parent.cancel()
        assertTrue(alive, "one page's failure (via onError) cancelled the whole observePages job; escaped=$escaped")
    }
}
