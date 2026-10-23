package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.core.cache.reactive.PaginatorReactiveCache
import com.jamal_aliev.paginator.core.cache.reactive.ReactiveEvent
import com.jamal_aliev.paginator.core.extension.isErrorState
import com.jamal_aliev.paginator.core.extension.isSuccessState
import com.jamal_aliev.paginator.core.load.Metadata
import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.cache.eviction.QueuedPagingCache
import com.jamal_aliev.paginator.offset.extension.observe
import com.jamal_aliev.paginator.offset.extension.observePageResults
import com.jamal_aliev.paginator.offset.extension.observePages
import com.jamal_aliev.paginator.offset.load.LoadResult
import com.jamal_aliev.paginator.offset.serialization.restoreStateFromJson
import com.jamal_aliev.paginator.offset.serialization.saveStateToJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObservePagesTest {

    /**
     * A table observed the way Room does it: every `LIMIT/OFFSET` query re-emits its window
     * whenever the table changes. Tracks which pages are subscribed right now.
     */
    private class FakeTable(rowCount: Int, private val capacity: Int = 3) {
        val rows = MutableStateFlow(List(rowCount) { "r$it" })
        val activePages = mutableListOf<Int>()
        var subscriptions = 0
            private set
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

    private fun livePaginator(
        core: PagingCore<String> = PagingCore(),
    ): MutablePaginator<String> =
        MutablePaginator(core = core) { error("the original load must not be called while observing") }
            .apply { this.core.resize(capacity = 3, resize = false, silently = true) }

    private suspend fun MutablePaginator<String>.loadPages(count: Int) {
        jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        repeat(count - 1) { goNextPage(silentlyLoading = true, silentlyResult = true) }
    }

    private fun TestScope.observe(
        paginator: MutablePaginator<String>,
        table: FakeTable,
        onError: ((Int, Throwable) -> Unit)? = null,
    ) = paginator.observePages(backgroundScope, onError) { page -> table.observePage(page, core.capacity) }

    private fun MutablePaginator<String>.dataOf(page: Int): List<String> = cache.getStateOf(page)!!.data

    @Test
    fun `each loaded page takes its first emission and only cached pages are subscribed`() = runTest {
        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator()
        observe(paginator, table)

        paginator.loadPages(2)
        runCurrent()

        assertEquals(listOf("r0", "r1", "r2"), paginator.dataOf(1))
        assertEquals(listOf("r3", "r4", "r5"), paginator.dataOf(2))
        assertEquals(listOf(1, 2), table.activePages.sorted())
    }

    @Test
    fun `a table change updates the cached pages in place`() = runTest {
        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator()
        observe(paginator, table)
        paginator.loadPages(2)
        runCurrent()
        val idBefore = paginator.cache.getStateOf(2)!!.id
        var lastSnapshot: List<String> = emptyList()
        backgroundScope.launch { paginator.core.snapshot.collect { s -> lastSnapshot = s.flatMap { it.data } } }
        runCurrent()

        table.update(index = 4, value = "edited")
        runCurrent()

        assertEquals(listOf("r3", "edited", "r5"), paginator.dataOf(2))
        assertEquals(idBefore, paginator.cache.getStateOf(2)!!.id, "the page keeps its id")
        assertTrue("edited" in lastSnapshot, "the update is published to the snapshot")
    }

    @Test
    fun `an evicted page is unsubscribed`() = runTest {
        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator(
            core = PagingCore(cache = QueuedPagingCache(maxSize = 2, protectContextWindow = false)),
        )
        observe(paginator, table)

        paginator.loadPages(3)
        runCurrent()

        assertEquals(listOf(2, 3), paginator.cache.pages)
        assertEquals(listOf(2, 3), table.activePages.sorted())
    }

    @Test
    fun `restart keeps only page 1 subscribed and release unsubscribes everything`() = runTest {
        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator()
        observe(paginator, table)
        paginator.loadPages(3)
        runCurrent()

        paginator.restart()
        runCurrent()
        assertEquals(listOf(1), table.activePages)

        paginator.release()
        runCurrent()
        assertEquals(emptyList(), table.activePages)
    }

    @Test
    fun `refresh re-subscribes the refreshed page`() = runTest {
        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator()
        observe(paginator, table)
        paginator.loadPages(2)
        runCurrent()
        val before = table.subscriptions

        paginator.refresh(pages = listOf(2))
        runCurrent()

        assertEquals(before + 1, table.subscriptions)
        assertEquals(listOf(1, 2), table.activePages.sorted())
        assertTrue(paginator.cache.getStateOf(2).isSuccessState())
    }

    @Test
    fun `an update that lands while refresh is loading is not overwritten by it`() = runTest {
        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator()
        observe(paginator, table)
        paginator.loadPages(3)
        runCurrent()

        // Page 3's new subscription hangs, so the refresh stays in its loading phase.
        val gate = CompletableDeferred<Unit>().also { table.gates[3] = it }
        val refresh = launch { paginator.refresh(pages = listOf(2, 3)) }
        runCurrent()

        table.update(index = 4, value = "edited")
        runCurrent()
        gate.complete(Unit)
        refresh.join()
        runCurrent()

        assertEquals(listOf("r3", "edited", "r5"), paginator.dataOf(2))
        assertTrue(paginator.cache.getStateOf(2).isSuccessState())
    }

    @Test
    fun `a source failing before its first emission produces an Error page`() = runTest {
        val paginator = livePaginator()
        paginator.observePages(backgroundScope) { _ -> flow<List<String>> { throw IllegalStateException("db closed") } }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        assertTrue(paginator.cache.getStateOf(1).isErrorState())
    }

    @Test
    fun `a source failing after its first emission reports the error and marks the page dirty`() =
        runTest {
            val errors = mutableListOf<Pair<Int, Throwable>>()
            val paginator = livePaginator()
            paginator.observePages(backgroundScope, onError = { page, e -> errors += page to e }) { page ->
                flow {
                    emit(List(3) { "p${page}_$it" })
                    throw IllegalStateException("db closed")
                }
            }

            paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
            runCurrent()

            // The flow fails before jump commits, so jump's own dirty-page pass already retries
            // (re-subscribes) once — a retry per navigation, never a loop.
            assertEquals(listOf(1, 1), errors.map { it.first })
            assertTrue(paginator.core.isDirty(1))
            assertEquals(listOf("p1_0", "p1_1", "p1_2"), paginator.dataOf(1))
        }

    @Test
    fun `observe and observePages are mutually exclusive`() = runTest {
        val table = FakeTable(rowCount = 20)
        val reactive = object : PaginatorReactiveCache<String, String> {
            override fun identity(item: String): String = item
            override fun changes(): Flow<ReactiveEvent<String, String>> = emptyFlow()
        }

        val paginator = livePaginator()
        val pagesJob = observe(paginator, table)
        assertFailsWith<IllegalStateException> { paginator.observe(backgroundScope, reactive) }
        assertFailsWith<IllegalStateException> { observe(paginator, table) }

        pagesJob.cancel()
        runCurrent()
        val observeJob = paginator.observe(backgroundScope, reactive)
        assertFailsWith<IllegalStateException> { observe(paginator, table) }
        observeJob.cancel()
    }

    @Test
    fun `cancelling the job unsubscribes every page and restores the original load`() = runTest {
        val table = FakeTable(rowCount = 20)
        var originalLoads = 0
        val paginator = MutablePaginator<String> { page ->
            originalLoads++
            LoadResult(MutableList(3) { "load_p${page}_$it" })
        }.apply { core.resize(capacity = 3, resize = false, silently = true) }
        val job = observe(paginator, table)
        paginator.loadPages(2)
        runCurrent()

        job.cancel()
        runCurrent()
        assertEquals(emptyList(), table.activePages)

        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        assertEquals(1, originalLoads)
        assertEquals("load_p3_0", paginator.dataOf(3).first())
    }

    @Test
    fun `pages cached before observing become live`() = runTest {
        val table = FakeTable(rowCount = 20)
        val paginator = MutablePaginator<String> { page ->
            LoadResult(table.rows.value.drop((page - 1) * 3).take(3).toMutableList())
        }.apply { core.resize(capacity = 3, resize = false, silently = true) }
        paginator.loadPages(2)

        observe(paginator, table)
        runCurrent()
        assertEquals(listOf(1, 2), table.activePages.sorted())

        table.update(index = 0, value = "edited")
        runCurrent()
        assertEquals("edited", paginator.dataOf(1).first())
    }

    @Test
    fun `resize re-subscribes the pages with the new capacity`() = runTest {
        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator()
        observe(paginator, table)
        paginator.loadPages(2)
        runCurrent()

        paginator.core.resize(capacity = 2, resize = true)
        runCurrent()

        assertEquals(listOf("r0", "r1"), paginator.dataOf(1))
        assertEquals(listOf("r2", "r3"), paginator.dataOf(2))
        table.update(index = 3, value = "edited")
        runCurrent()
        assertEquals(listOf("r2", "edited"), paginator.dataOf(2))
    }

    @Test
    fun `a shrinking last page stays visible`() = runTest {
        val table = FakeTable(rowCount = 6)
        val paginator = livePaginator()
        observe(paginator, table)
        paginator.loadPages(2)
        runCurrent()
        var lastSnapshot: List<String> = emptyList()
        backgroundScope.launch { paginator.core.snapshot.collect { s -> lastSnapshot = s.flatMap { it.data } } }

        table.rows.value = table.rows.value.dropLast(2)
        runCurrent()

        assertEquals(listOf("r3"), paginator.dataOf(2))
        assertEquals(listOf("r0", "r1", "r2", "r3"), lastSnapshot)
    }

    @Test
    fun `observePageResults carries metadata into the page`() = runTest {
        class Total(val count: Int) : Metadata()

        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator()
        paginator.observePageResults(backgroundScope) { page ->
            table.observePage(page).map { LoadResult(it, Total(table.rows.value.size)) }
        }
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        assertEquals(20, (paginator.cache.getStateOf(1)!!.metadata as Total).count)

        table.rows.value = table.rows.value.drop(1)
        runCurrent()
        assertEquals(19, (paginator.cache.getStateOf(1)!!.metadata as Total).count)
        assertFalse(paginator.cache.getStateOf(1)!!.data.contains("r0"))
    }

    // ── Wholesale replacement: restoreState and transaction rollback ────────────────────────────

    /** Saves three pages of [table] the way a screen would before process death. */
    private suspend fun savedState(table: FakeTable): String {
        val source = MutablePaginator<String> { page ->
            LoadResult(table.rows.value.drop((page - 1) * 3).take(3).toMutableList())
        }.apply { core.resize(capacity = 3, resize = false, silently = true) }
        source.loadPages(3)
        return source.saveStateToJson(String.serializer())
    }

    @Test
    fun `a recreated paginator restored before observing subscribes every page at once`() = runTest {
        val table = FakeTable(rowCount = 20)
        val saved = savedState(table)
        table.update(index = 4, value = "changed while the process was dead")

        val paginator = livePaginator()
        paginator.restoreStateFromJson(saved, String.serializer())
        observe(paginator, table)
        runCurrent()

        assertEquals(listOf(1, 2, 3), table.activePages.sorted())
        assertEquals(listOf("r3", "changed while the process was dead", "r5"), paginator.dataOf(2))
    }

    @Test
    fun `restoring into an observed paginator subscribes every page at once`() = runTest {
        val table = FakeTable(rowCount = 20)
        val saved = savedState(table)
        table.update(index = 4, value = "changed while the process was dead")

        val paginator = livePaginator()
        observe(paginator, table)
        runCurrent()
        paginator.restoreStateFromJson(saved, String.serializer())
        runCurrent()

        assertEquals(listOf(1, 2, 3), table.activePages.sorted())
        assertEquals(listOf("r3", "changed while the process was dead", "r5"), paginator.dataOf(2))
    }

    @Test
    fun `a silent restore still subscribes every page`() = runTest {
        val table = FakeTable(rowCount = 20)
        val saved = savedState(table)
        table.update(index = 4, value = "changed while the process was dead")

        val paginator = livePaginator()
        observe(paginator, table)
        runCurrent()
        paginator.restoreStateFromJson(saved, String.serializer(), silently = true)
        runCurrent()

        assertEquals(listOf(1, 2, 3), table.activePages.sorted())
        assertEquals(listOf("r3", "changed while the process was dead", "r5"), paginator.dataOf(2))
    }

    @Test
    fun `restoring an older state over live pages re-queries them`() = runTest {
        val table = FakeTable(rowCount = 20)
        val saved = savedState(table)
        val paginator = livePaginator()
        observe(paginator, table)
        paginator.loadPages(3)
        runCurrent()
        table.update(index = 4, value = "newer")
        runCurrent()
        val subscriptionsBefore = table.subscriptions

        paginator.restoreStateFromJson(saved, String.serializer())
        runCurrent()

        assertEquals(listOf("r3", "newer", "r5"), paginator.dataOf(2), "the database wins over the snapshot")
        assertEquals(subscriptionsBefore + 3, table.subscriptions)
        assertEquals(listOf(1, 2, 3), table.activePages.sorted())
    }

    @Test
    fun `a transaction rollback re-queries the live pages`() = runTest {
        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator()
        observe(paginator, table)
        paginator.loadPages(2)
        runCurrent()

        // The database changes while the transaction runs; rolling back L1 must not resurrect the
        // pre-transaction data the queries already moved past.
        assertFailsWith<IllegalStateException> {
            paginator.transaction {
                table.update(index = 4, value = "written during the transaction")
                runCurrent()
                error("boom")
            }
        }
        runCurrent()

        assertEquals(listOf("r3", "written during the transaction", "r5"), paginator.dataOf(2))
        assertEquals(listOf(1, 2), table.activePages.sorted())
    }
}
