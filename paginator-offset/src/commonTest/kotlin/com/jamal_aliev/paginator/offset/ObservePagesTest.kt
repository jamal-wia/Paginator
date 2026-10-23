package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.core.cache.reactive.PaginatorReactiveCache
import com.jamal_aliev.paginator.core.cache.reactive.ReactiveEvent
import com.jamal_aliev.paginator.core.extension.isErrorState
import com.jamal_aliev.paginator.core.extension.isSuccessState
import com.jamal_aliev.paginator.core.load.Metadata
import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.cache.eviction.QueuedPagingCache
import com.jamal_aliev.paginator.offset.cache.eviction.TimeLimitedPagingCache
import com.jamal_aliev.paginator.offset.cache.eviction.MostRecentPagingCache
import com.jamal_aliev.paginator.offset.cache.PagingCache
import com.jamal_aliev.paginator.offset.extension.observe
import com.jamal_aliev.paginator.offset.extension.observePageResults
import com.jamal_aliev.paginator.offset.extension.observePages
import com.jamal_aliev.paginator.offset.load.LoadResult
import com.jamal_aliev.paginator.offset.serialization.restoreStateFromJson
import com.jamal_aliev.paginator.offset.serialization.saveStateToJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

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
        var loads = 0
            private set

        /** Gates a load of a page AFTER it has read the table. */
        val loadGates = mutableMapOf<Int, CompletableDeferred<Unit>>()

        /** A one-shot read, the `load` of a page. */
        suspend fun loadPage(page: Int, limit: Int = capacity): LoadResult<String> {
            loads++
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

    /** A paginator whose load is configured by the observing helpers below. */
    private fun livePaginator(
        core: PagingCore<String> = PagingCore(),
    ): MutablePaginator<String> =
        MutablePaginator(core = core) { error("no load configured") }
            .apply { this.core.resize(capacity = 3, resize = false, silently = true) }

    /** observePages over [source], loading a page from the same source (its first emission). */
    private fun MutablePaginator<String>.live(
        scope: CoroutineScope,
        onError: ((Int, Throwable) -> Unit)? = null,
        settle: Duration = Duration.ZERO,
        source: Paginator<String>.(page: Int) -> Flow<List<String>>,
    ) = apply { load = { page -> LoadResult(source(page).first()) } }
        .observePages(scope, onError, settle, source)

    private suspend fun MutablePaginator<String>.loadPages(count: Int) {
        jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        repeat(count - 1) { goNextPage(silentlyLoading = true, silentlyResult = true) }
    }

    private fun TestScope.observe(
        paginator: MutablePaginator<String>,
        table: FakeTable,
        onError: ((Int, Throwable) -> Unit)? = null,
    ) = paginator.apply { load = { page -> table.loadPage(page, core.capacity) } }
        .observePages(backgroundScope, onError) { page -> table.observePage(page, core.capacity) }

    // peekStateOf: reading a page in an assertion must not count as a use for LRU / TTL caches.
    private fun MutablePaginator<String>.dataOf(page: Int): List<String> = cache.peekStateOf(page)!!.data

    @Test
    fun `each page is loaded by load and subscribed once it is cached`() = runTest {
        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator()
        observe(paginator, table)

        paginator.loadPages(2)
        runCurrent()

        assertEquals(listOf("r0", "r1", "r2"), paginator.dataOf(1))
        assertEquals(listOf("r3", "r4", "r5"), paginator.dataOf(2))
        assertEquals(2, table.loads)
        assertEquals(listOf(1, 2), table.activePages.sorted())
    }

    @Test
    fun `a first emission equal to the loaded data publishes no snapshot`() = runTest {
        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator()
        paginator.load = { page -> table.loadPage(page, core.capacity) }
        paginator.loadPages(1)
        var snapshots = 0
        backgroundScope.launch { paginator.core.snapshot.collect { snapshots++ } }
        runCurrent()
        val before = snapshots

        paginator.observePages(backgroundScope) { page -> table.observePage(page, core.capacity) }
        runCurrent()

        assertEquals(listOf(1), table.activePages)
        assertEquals(before, snapshots, "an equal first emission must not publish")
    }

    @Test
    fun `a first emission that differs from the loaded data is applied`() = runTest {
        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator()
        // load returns what the network said; the database holds something else
        paginator.load = { _ -> LoadResult(listOf("net0", "net1", "net2")) }
        paginator.observePages(backgroundScope) { page -> table.observePage(page, core.capacity) }

        paginator.loadPages(1)
        runCurrent()

        assertEquals(listOf("r0", "r1", "r2"), paginator.dataOf(1))
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
    fun `refresh reloads through load and the live query adopts the result`() = runTest {
        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator()
        observe(paginator, table)
        paginator.loadPages(2)
        runCurrent()
        val subscriptionsBefore = table.subscriptions
        val loadsBefore = table.loads

        paginator.refresh(pages = listOf(2))
        runCurrent()

        assertEquals(loadsBefore + 1, table.loads)
        assertEquals(subscriptionsBefore, table.subscriptions, "an agreeing reload is adopted, not re-queried")
        assertEquals(listOf(1, 2), table.activePages.sorted())
        assertTrue(paginator.cache.getStateOf(2).isSuccessState())
    }

    @Test
    fun `an update that lands while refresh is loading ends up in the page`() = runTest {
        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator()
        observe(paginator, table)
        paginator.loadPages(3)
        runCurrent()

        // Page 3's load hangs, so the refresh stays in its loading phase; page 2's load has read
        // the table before the update, so the refresh commits the older rows.
        val gate = CompletableDeferred<Unit>().also { table.loadGates[3] = it }
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
    fun `a source failing before its first emission reports the error and marks the page dirty`() = runTest {
        val errors = mutableListOf<Int>()
        val paginator = livePaginator()
        paginator.load = { page -> LoadResult(List(3) { "load_p${page}_$it" }) }
        paginator.observePages(backgroundScope, onError = { page, _ -> errors += page }) { _ ->
            flow<List<String>> { throw IllegalStateException("db closed") }
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        runCurrent()

        assertEquals(listOf(1), errors)
        assertTrue(paginator.cache.getStateOf(1).isSuccessState(), "the loaded page stays")
        assertTrue(paginator.core.isDirty(1))
    }

    @Test
    fun `an Error page shows the database rows and stays an Error page`() = runTest {
        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator()
        paginator.load = { _ -> throw IllegalStateException("offline") }
        paginator.observePages(backgroundScope) { page -> table.observePage(page, core.capacity) }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        runCurrent()

        assertTrue(paginator.cache.peekStateOf(1).isErrorState())
        assertEquals(listOf("r0", "r1", "r2"), paginator.dataOf(1))
        table.update(index = 1, value = "edited")
        runCurrent()
        assertEquals(listOf("r0", "edited", "r2"), paginator.dataOf(1))
        assertTrue(paginator.cache.peekStateOf(1).isErrorState())
    }

    @Test
    fun `a refresh error is kept when the page emits while it loads`() = runTest {
        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator()
        observe(paginator, table)
        paginator.loadPages(1)
        runCurrent()

        val gate = CompletableDeferred<Unit>()
        paginator.load = { _ -> gate.await(); throw IllegalStateException("network down") }
        val refresh = launch { paginator.refresh(pages = listOf(1)) }
        runCurrent()
        table.update(index = 1, value = "edited") // folded into the loading page
        runCurrent()
        gate.complete(Unit)
        refresh.join()
        runCurrent()

        assertTrue(paginator.cache.peekStateOf(1).isErrorState(), "the refresh error is not swallowed")
        assertEquals(listOf("r0", "edited", "r2"), paginator.dataOf(1))
    }

    @Test
    fun `a source failing after its first emission reports the error and marks the page dirty`() =
        runTest {
            val errors = mutableListOf<Pair<Int, Throwable>>()
            val paginator = livePaginator()
            paginator.live(backgroundScope, onError = { page, e -> errors += page to e }) { page ->
                flow {
                    emit(List(3) { "p${page}_$it" })
                    throw IllegalStateException("db closed")
                }
            }

            paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
            runCurrent()

            // One report; the page is marked dirty once jump has committed it, so it is reloaded
            // (and re-subscribed) by the next navigation that reaches it, not retried right away.
            assertEquals(listOf(1), errors.map { it.first })
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
    fun `cancelling the job unsubscribes every page and load keeps working`() = runTest {
        val table = FakeTable(rowCount = 20)
        var originalLoads = 0
        val paginator = MutablePaginator<String> { page ->
            originalLoads++
            LoadResult(MutableList(3) { "load_p${page}_$it" })
        }.apply { core.resize(capacity = 3, resize = false, silently = true) }
        val job = paginator.observePages(backgroundScope) { page -> table.observePage(page, core.capacity) }
        paginator.loadPages(2)
        runCurrent()

        job.cancel()
        runCurrent()
        assertEquals(emptyList(), table.activePages)

        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        assertEquals(3, originalLoads)
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
        val source: Paginator<String>.(Int) -> Flow<LoadResult<String>> = { page ->
            table.observePage(page).map { LoadResult(it, Total(table.rows.value.size)) }
        }
        paginator.load = { page -> source(page).first() }
        paginator.observePageResults(backgroundScope, source = source)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        runCurrent()
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

    // ── Eviction strategies: live bookkeeping and updates are not user access ──────────────────

    private fun TestScope.observeWith(cache: PagingCache<String>, table: FakeTable): MutablePaginator<String> =
        livePaginator(core = PagingCore(cache = cache)).also { observe(it, table) }

    @Test
    fun `LRU evicts the least recently visited page while pages are observed`() = runTest {
        val table = FakeTable(rowCount = 60)
        val paginator = observeWith(MostRecentPagingCache(maxSize = 3, protectContextWindow = false), table)

        for (page in listOf(1, 5, 9, 1)) {
            paginator.jump(BookmarkInt(page))
            runCurrent()
        }
        paginator.jump(BookmarkInt(13))
        runCurrent()

        // Page 1 was just revisited; page 5 is the least recently used one. (The jumps publish
        // snapshots, which run observePages' bookkeeping — it must not count as a use.)
        assertEquals(listOf(1, 9, 13), paginator.cache.pages)
        assertEquals(listOf(1, 9, 13), table.activePages.sorted())
    }

    @Test
    fun `a database change does not make an off-screen page recent for LRU`() = runTest {
        val table = FakeTable(rowCount = 60)
        val paginator = observeWith(MostRecentPagingCache(maxSize = 3, protectContextWindow = false), table)
        // Visited 9, then 5, then 1: page 9 is the least recently used one.
        for (page in listOf(9, 5, 1)) {
            paginator.jump(BookmarkInt(page))
            runCurrent()
        }

        table.update(index = 24, value = "edited") // first row of page 9, off screen
        runCurrent()
        assertEquals("edited", paginator.dataOf(9).first())
        paginator.jump(BookmarkInt(13))
        runCurrent()

        assertEquals(listOf(1, 5, 13), paginator.cache.pages)
    }

    @Test
    fun `re-anchoring the window after a live update does not reorder LRU`() = runTest {
        val sources = mutableMapOf<Int, MutableStateFlow<List<String>>>()
        fun src(page: Int) = sources.getOrPut(page) { MutableStateFlow(List(3) { "p${page}_$it" }) }
        val paginator = livePaginator(core = PagingCore(cache = MostRecentPagingCache(maxSize = 3, protectContextWindow = false)))
        paginator.live(backgroundScope) { page -> src(page) }
        // Visited 9, then 5, then 1: page 9 is the least recently used one.
        for (page in listOf(9, 5, 1)) {
            paginator.jump(BookmarkInt(page))
            runCurrent()
        }

        src(1).value = listOf("only one") // the visible page shrinks: the window is re-anchored
        runCurrent()
        paginator.jump(BookmarkInt(13))
        runCurrent()

        assertEquals(listOf(1, 5, 13), paginator.cache.pages)
    }

    @Test
    fun `a database change does not restart the TTL of a page`() = runTest {
        val time = TestTimeSource()
        val table = FakeTable(rowCount = 60)
        val paginator = observeWith(
            TimeLimitedPagingCache(ttl = 10.seconds, protectContextWindow = false, timeSource = time),
            table,
        )
        paginator.jump(BookmarkInt(1))
        paginator.jump(BookmarkInt(5))
        runCurrent()
        time += 11.seconds

        table.update(index = 0, value = "edited") // page 1 changes after its TTL ran out
        runCurrent()
        assertEquals("edited", paginator.dataOf(1).first())
        paginator.jump(BookmarkInt(9))
        runCurrent()

        assertEquals(listOf(9), paginator.cache.pages)
        assertEquals(listOf(9), table.activePages)
    }

    // ── settle: consistent rounds across pages ──────────────────────────────────────────────────

    /** Room-like: every page query re-runs on a table change; page 2's answer lands [lag] later. */
    private fun TestScope.observeWithLag(
        paginator: MutablePaginator<String>,
        rows: MutableStateFlow<List<String>>,
        settle: Duration,
        lag: Long = 5,
    ) = paginator.live(backgroundScope, settle = settle) { page ->
        var first = true
        rows.map { all ->
            if (page == 2 && !first) delay(lag)
            first = false
            all.drop((page - 1) * 3).take(3)
        }
    }

    private fun TestScope.collectSnapshots(paginator: MutablePaginator<String>): List<List<String>> {
        val snapshots = mutableListOf<List<String>>()
        backgroundScope.launch { paginator.core.snapshot.collect { s -> snapshots += s.flatMap { it.data } } }
        return snapshots
    }

    @Test
    fun `without settle a row moving across pages can show up twice`() = runTest {
        val rows = MutableStateFlow(List(20) { "r$it" })
        val paginator = livePaginator()
        observeWithLag(paginator, rows, settle = Duration.ZERO)
        paginator.loadPages(2)
        paginator.core.snapshot()
        runCurrent()
        val snapshots = collectSnapshots(paginator)
        runCurrent()

        rows.value = rows.value.drop(1) // r3 moves from page 2 to page 1
        // The page queries run in backgroundScope, whose delays advanceUntilIdle() does not wait for.
        advanceTimeBy(200)
        runCurrent()

        // Documents the default trade-off that settle removes.
        assertTrue(snapshots.any { it.size != it.toSet().size })
    }

    @Test
    fun `with settle a row moving across pages is never shown twice`() = runTest {
        val rows = MutableStateFlow(List(20) { "r$it" })
        val paginator = livePaginator()
        observeWithLag(paginator, rows, settle = 100.milliseconds)
        paginator.loadPages(2)
        paginator.core.snapshot()
        runCurrent()
        val snapshots = collectSnapshots(paginator)
        runCurrent()

        rows.value = rows.value.drop(1)
        advanceTimeBy(200)
        runCurrent()

        assertTrue(snapshots.none { it.size != it.toSet().size }, "duplicates: $snapshots")
        assertEquals(listOf("r1", "r2", "r3"), paginator.dataOf(1))
        assertEquals(listOf("r4", "r5", "r6"), paginator.dataOf(2))
    }

    @Test
    fun `a round closes as soon as every live page has emitted`() = runTest {
        val rows = MutableStateFlow(List(20) { "r$it" })
        val paginator = livePaginator()
        observeWithLag(paginator, rows, settle = 1.seconds, lag = 5)
        paginator.loadPages(2)
        runCurrent()
        val start = currentTime

        rows.value = rows.value.drop(1)
        runCurrent()
        advanceTimeBy(6)
        runCurrent()

        // Both pages answered after ~5 ms, far below the 1 s timeout.
        assertEquals(listOf("r1", "r2", "r3"), paginator.dataOf(1))
        assertTrue(currentTime - start < 1_000)
    }

    @Test
    fun `a round whose other pages never emit is applied after settle`() = runTest {
        val sources = mutableMapOf<Int, MutableStateFlow<List<String>>>()
        fun src(page: Int) = sources.getOrPut(page) { MutableStateFlow(List(3) { "p${page}_$it" }) }
        val paginator = livePaginator()
        paginator.live(backgroundScope, settle = 100.milliseconds) { page -> src(page) }
        paginator.loadPages(2)
        runCurrent()

        src(1).value = listOf("x", "y", "z") // page 2's source has nothing new to say
        advanceTimeBy(99)
        runCurrent()
        assertEquals(listOf("p1_0", "p1_1", "p1_2"), paginator.dataOf(1), "held for the round")

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("x", "y", "z"), paginator.dataOf(1))
    }

    @Test
    fun `a first emission does not wait for a settle round`() = runTest {
        val sources = mutableMapOf<Int, MutableStateFlow<List<String>>>()
        fun src(page: Int) = sources.getOrPut(page) { MutableStateFlow(List(3) { "db${page}_$it" }) }
        val paginator = livePaginator()
        paginator.load = { page -> LoadResult(List(3) { "load${page}_$it" }) } // differs from the database
        paginator.observePages(backgroundScope, settle = 1.seconds) { page -> src(page) }
        paginator.loadPages(2)
        runCurrent()

        // Both pages were subscribed after their loads; their first emissions are applied at once,
        // not held for a round that no table change opened.
        assertEquals(listOf("db1_0", "db1_1", "db1_2"), paginator.dataOf(1))
        assertEquals(listOf("db2_0", "db2_1", "db2_2"), paginator.dataOf(2))
    }

    @Test
    fun `the metadata of load survives live updates without metadata`() = runTest {
        class Total(val count: Int) : Metadata()

        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator()
        paginator.load = { page -> LoadResult(table.loadPage(page, core.capacity).data, Total(20)) }
        paginator.observePages(backgroundScope) { page -> table.observePage(page, core.capacity) }
        paginator.loadPages(1)
        runCurrent()

        table.update(index = 1, value = "edited")
        runCurrent()

        assertEquals("edited", paginator.dataOf(1)[1])
        assertEquals(20, (paginator.cache.peekStateOf(1)!!.metadata as Total).count)
    }

    @Test
    fun `a cancelled refresh that a live update was folded into re-queries the page`() = runTest {
        val table = FakeTable(rowCount = 20)
        val paginator = livePaginator()
        observe(paginator, table)
        paginator.loadPages(2)
        runCurrent()
        val subscriptionsBefore = table.subscriptions

        table.loadGates[2] = CompletableDeferred() // the refresh of page 2 hangs
        val refresh = launch { paginator.refresh(pages = listOf(2)) }
        runCurrent()
        table.update(index = 4, value = "edited") // folded into the loading page
        runCurrent()
        refresh.cancel() // rolls page 2 back to the state it had before the refresh
        runCurrent()

        assertEquals(listOf("r3", "edited", "r5"), paginator.dataOf(2))
        assertTrue(paginator.cache.peekStateOf(2).isSuccessState())
        assertEquals(subscriptionsBefore + 1, table.subscriptions, "the rolled-back page is re-queried")
    }

    @Test
    fun `settle must not be negative`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            livePaginator().observePages(backgroundScope, settle = (-1).milliseconds) { _ -> flowOf(emptyList()) }
        }
    }

    @Test
    fun `a live update that moves the window realigns the bookmark cursor`() = runTest {
        val sources = mutableMapOf<Int, MutableStateFlow<List<String>>>()
        fun src(page: Int) = sources.getOrPut(page) {
            val hasData = page in 1..3 || page == 5 || page in 11..13
            MutableStateFlow(if (hasData) List(3) { "p${page}_$it" } else emptyList())
        }
        val paginator = livePaginator()
        paginator.live(backgroundScope) { page -> src(page) }
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(listOf(BookmarkInt(2), BookmarkInt(5), BookmarkInt(12)))
        paginator.loadPages(3)
        paginator.jump(BookmarkInt(11), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        runCurrent()
        assertEquals(11, paginator.cache.startContextPage)

        // The viewed range is deleted in the database: the live update re-anchors to 1..3.
        for (page in 11..13) src(page).value = emptyList()
        runCurrent()
        assertEquals(1, paginator.cache.startContextPage)
        assertEquals(3, paginator.cache.endContextPage)

        // Like after refresh, jumpForward resumes from the new window: the next bookmark is 5,
        // not the deleted bookmark 12 the stale cursor still points at.
        val forward = paginator.jumpForward(silentlyLoading = true, silentlyResult = true)
        assertEquals(5, forward?.first?.page)
    }

    @Test
    fun `goNextPage reloads a partial last page through load without re-querying it`() = runTest {
        val table = FakeTable(rowCount = 5) // page 2 = r3, r4: the partial end of the feed
        val paginator = livePaginator()
        observe(paginator, table)
        paginator.loadPages(2)
        runCurrent()
        val subscriptionsBefore = table.subscriptions
        val loadsBefore = table.loads

        // Scrolling to the end (prefetch) keeps calling goNextPage on the partial last page; like
        // without observePages it is reloaded (a network-backed load may bring new rows).
        repeat(3) { paginator.goNextPage(silentlyLoading = true, silentlyResult = true) }
        runCurrent()

        assertEquals(loadsBefore + 3, table.loads)
        assertEquals(subscriptionsBefore, table.subscriptions, "an agreeing reload is adopted, not re-queried")
        assertEquals(listOf("r3", "r4"), paginator.dataOf(2))
        assertTrue(paginator.cache.peekStateOf(2).isSuccessState())
    }

    @Test
    fun `a live partial last page that fills up lets goNextPage continue`() = runTest {
        val table = FakeTable(rowCount = 5)
        val paginator = livePaginator()
        observe(paginator, table)
        paginator.loadPages(2)
        runCurrent()

        table.rows.value = List(9) { "r$it" } // new rows arrive: page 2 fills, page 3 exists
        runCurrent()
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        runCurrent()

        assertEquals(listOf("r6", "r7", "r8"), paginator.dataOf(3))
    }
}
