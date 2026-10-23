package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.core.cache.reactive.InitialSyncPolicy
import com.jamal_aliev.paginator.core.cache.reactive.UnknownItemPolicy
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.cache.CursorPagingCache
import com.jamal_aliev.paginator.cursor.cache.eviction.CursorMostRecentPagingCache
import com.jamal_aliev.paginator.cursor.cache.eviction.CursorTimeLimitedPagingCache
import com.jamal_aliev.paginator.cursor.cache.reactive.CursorInsertPosition
import com.jamal_aliev.paginator.cursor.cache.reactive.CursorPaginatorReactiveCache
import com.jamal_aliev.paginator.cursor.cache.reactive.CursorReactiveEvent
import com.jamal_aliev.paginator.cursor.cache.reactive.cursorSnapshotReactiveCache
import com.jamal_aliev.paginator.cursor.extension.observe
import com.jamal_aliev.paginator.cursor.load.CursorLoadResult
import com.jamal_aliev.paginator.cursor.page.CursorPageState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * Regression tests for the consistency of `MutableCursorPaginator.observe()`: source failures,
 * races with refresh and navigation, the initial sync, wholesale replacement and eviction
 * bookkeeping. The cursor twin of the offset `ReactiveObserveRegressionTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CursorReactiveObserveRegressionTest {

    private data class Item(val id: Int, val label: String)

    private class Bus : CursorPaginatorReactiveCache<Item, Int> {
        val events = MutableSharedFlow<CursorReactiveEvent<Item, Int>>(replay = 64)
        override fun identity(item: Item): Int = item.id
        override fun changes(): Flow<CursorReactiveEvent<Item, Int>> = events.asSharedFlow()
    }

    /** A "database" of pages `p0`, `p1`, …: load reads a slice of [db] and, for [gatedSelf], waits on [gate] AFTER reading. */
    private class Db(n: Int) {
        val db = MutableStateFlow(List(n) { Item(it + 1, "v0-${it + 1}") })
        val pageCount = (n + 2) / 3
        var gate: CompletableDeferred<Unit>? = null
        var gatedSelf: String? = null
        var loads = 0
        private fun pageOf(self: String) = self.removePrefix("p").toInt()
        fun paginator(cache: CursorPagingCache<String, Item>? = null): MutableCursorPaginator<String, Item> {
            val core = if (cache != null) CursorPagingCore(cache = cache, initialCapacity = 3)
            else CursorPagingCore<String, Item>(initialCapacity = 3)
            return MutableCursorPaginator(core = core) { cursor ->
                loads++
                val self = cursor?.self
                val prev = cursor?.prev
                val next = cursor?.next
                val target = when {
                    cursor == null -> "p0"
                    self != null -> self
                    prev != null -> "p${pageOf(prev) + 1}"
                    next != null -> "p${pageOf(next) - 1}"
                    else -> error("bad cursor")
                }
                val page = pageOf(target)
                val slice = db.value.drop(page * 3).take(3)
                val g = gate
                if (target == gatedSelf && g != null) g.await()
                CursorLoadResult(
                    data = slice.toMutableList(),
                    bookmark = CursorBookmark(
                        prev = if (page == 0) null else "p${page - 1}",
                        self = target,
                        next = if (page == pageCount - 1) null else "p${page + 1}",
                    ),
                )
            }
        }

        fun patch(id: Int, label: String) {
            db.value = db.value.map { if (it.id == id) it.copy(label = label) else it }
        }
    }

    private fun labels(p: MutableCursorPaginator<String, Item>): List<String> =
        p.cache.cursors.flatMap { p.cache.peekStateOf(it.self)!!.data.map { i -> i.label } }

    private fun bookmark(page: Int) = CursorBookmark(prev = null, self = "p$page", next = null)

    private suspend fun MutableCursorPaginator<String, Item>.start(pages: Int) {
        jump(bookmark(0))
        repeat(pages - 1) { goNextPage() }
    }

    // ── Exceptions ───────────────────────────────────────────────────────────────────────────────

    /** A failing source is reported and re-subscribed after a backoff; events flow again afterwards. */
    @Test
    fun a_failing_source_is_reported_and_resubscribed() = runTest {
        val d = Db(6)
        val p = d.paginator(); p.start(1)
        var subscriptions = 0
        val upstream = MutableSharedFlow<CursorReactiveEvent<Item, Int>>()
        val source = object : CursorPaginatorReactiveCache<Item, Int> {
            override fun identity(item: Item) = item.id
            override fun changes(): Flow<CursorReactiveEvent<Item, Int>> = flow {
                subscriptions++
                if (subscriptions == 1) throw IllegalStateException("db read failed")
                emitAll(upstream)
            }
        }
        val errors = mutableListOf<Throwable>()
        val job = p.observe(this, source, initialSync = InitialSyncPolicy.None, onError = { errors += it })
        runCurrent()
        assertEquals(1, errors.size)
        assertTrue(job.isActive, "subscription died after one source error (job=$job)")

        // Re-subscribed after the backoff (and reconciled with refreshAll); events flow again.
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, subscriptions)
        upstream.emit(CursorReactiveEvent.Updated(Item(2, "after")))
        runCurrent()
        assertEquals("after", p.cache.peekStateOf("p0")!!.data[1].label)
        job.cancel()
    }

    /** A CancellationException thrown by the source itself (withTimeout) is a failure: it is reported. */
    @Test
    fun a_cancellation_thrown_by_the_source_is_reported() = runTest {
        val d = Db(6)
        val p = d.paginator(); p.start(1)
        val source = object : CursorPaginatorReactiveCache<Item, Int> {
            override fun identity(item: Item) = item.id
            override fun changes(): Flow<CursorReactiveEvent<Item, Int>> = flow {
                withTimeout(1.seconds) { awaitCancellation() }
            }
        }
        val errors = mutableListOf<Throwable>()
        val job = p.observe(this, source, initialSync = InitialSyncPolicy.None, onError = { errors += it })
        advanceTimeBy(1_200) // the source times out at 1 s; it is re-subscribed after a 500 ms backoff
        runCurrent()
        assertEquals(1, errors.size, "TimeoutCancellationException from the source was not reported; job=$job")
        assertTrue(errors.single() is CancellationException)
        assertTrue(job.isActive, "the subscription ended instead of re-subscribing")
        job.cancel()
    }

    /** onError = { throw it } is invoked once per failure, and its exception fails the Job. */
    @Test
    fun a_throwing_onError_is_invoked_once_and_fails_the_job() = runTest {
        val d = Db(6)
        val p = d.paginator(); p.start(1)
        val bus = Bus()
        var calls = 0
        val escaped = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> escaped += e }
        )
        val job = p.observe(scope, bus, initialSync = InitialSyncPolicy.None, onError = { calls++; throw it })
        // Moved to an uncached explicit coordinate: the transaction's check() throws.
        bus.events.emit(CursorReactiveEvent.Moved(Item(1, "x"), CursorInsertPosition.At(self = "nope", index = 0)))
        runCurrent()
        assertEquals(1, calls, "onError invoked $calls times for a single failure")
        assertEquals(1, escaped.size)
        assertTrue(job.isCancelled)
    }

    // ── Races with refresh ───────────────────────────────────────────────────────────────────────

    /** An Updated applied while refresh loads that page is not overwritten by the older refresh result. */
    @Test
    fun an_update_during_refresh_survives_the_refresh_result() = runTest {
        val d = Db(6)
        val p = d.paginator(); p.start(2)
        val bus = Bus()
        val job = p.observe(this, bus, initialSync = InitialSyncPolicy.None)
        runCurrent()
        d.gate = CompletableDeferred(); d.gatedSelf = "p1"
        val refresh = launch { p.refresh(listOf(p.cache.cursors[1])) }
        runCurrent() // refresh read the db (old label) and waits
        d.patch(5, "patched")
        bus.events.emit(CursorReactiveEvent.Updated(Item(5, "patched")))
        runCurrent()
        d.gate!!.complete(Unit)
        refresh.join()
        assertEquals(d.db.value.map { it.label }, labels(p), "live update lost to the older refresh result")
        job.cancel()
    }

    // ── Initial sync ─────────────────────────────────────────────────────────────────────────────

    /** A write that lands while the initial RefreshAll loads still reaches the cache (subscribe first). */
    @Test
    fun a_write_during_the_initial_sync_reaches_the_cache() = runTest {
        val d = Db(6)
        val p = d.paginator(); p.start(2)
        d.gate = CompletableDeferred(); d.gatedSelf = "p0"
        val job = p.observe(this, cursorSnapshotReactiveCache(d.db, identity = { it.id }))
        runCurrent() // initial refreshAll read p0 and waits on the gate
        d.patch(2, "patched") // a database write during the initial sync
        runCurrent()
        d.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(d.db.value.map { it.label }, labels(p), "write during initial sync never reached the cache")
        job.cancel()
    }

    // ── Lifecycle: restoreState ──────────────────────────────────────────────────────────────────

    /** observe() on an empty (recreated) paginator, then restoreState: the stale saved rows are reconciled. */
    @Test
    fun restoreState_while_observing_is_reconciled() = runTest {
        val d = Db(6)
        val first = d.paginator(); first.start(2)
        val saved = first.saveState(selfEncoder = { JsonPrimitive(it) })
        d.patch(2, "changed-while-dead")
        val p = d.paginator()
        val job = p.observe(this, cursorSnapshotReactiveCache(d.db, identity = { it.id }))
        runCurrent()
        p.restoreState(saved, selfDecoder = { it.jsonPrimitive.content })
        advanceUntilIdle()
        assertEquals(d.db.value.map { it.label }, labels(p), "restored stale rows never reconciled")
        job.cancel()
    }

    // ── LRU / TTL ────────────────────────────────────────────────────────────────────────────────

    /** An Updated event for an item that is NOT cached (dropped) does not renew any page's TTL. */
    @Test
    fun a_dropped_event_does_not_renew_ttl() = runTest {
        suspend fun run(withEvent: Boolean): List<String> {
            val clock = TestTimeSource()
            val ttlCache = CursorTimeLimitedPagingCache<String, Item>(
                ttl = 10.seconds, refreshOnAccess = true, protectContextWindow = false, timeSource = clock,
            )
            val d = Db(30)
            val p = d.paginator(ttlCache); p.start(3)
            val bus = Bus()
            val job = p.observe(this, bus, initialSync = InitialSyncPolicy.None)
            clock += 6.seconds
            if (withEvent) bus.events.emit(CursorReactiveEvent.Updated(Item(999, "not cached")))
            runCurrent()
            clock += 6.seconds // 12 s since p0..p2 were last used by the user
            val extra = CursorBookmark<String>(prev = null, self = "p9", next = null)
            p.cache.setState(extra, CursorPageState.Success(extra, mutableListOf(Item(100, "x"))), silently = true)
            job.cancel()
            return p.cache.cursors.map { it.self }.sorted()
        }
        assertEquals(run(withEvent = false), run(withEvent = true), "a dropped reactive event renewed TTLs")
    }

    /** Scanning every page for an event's identity does not reshape the LRU order. */
    @Test
    fun an_event_scan_does_not_reshape_lru_order() = runTest {
        suspend fun run(withEvent: Boolean): List<String> {
            val lru = CursorMostRecentPagingCache<String, Item>(maxSize = 3, protectContextWindow = false)
            val d = Db(30)
            val p = d.paginator(lru); p.start(3)
            p.cache.getStateOf("p0") // the user looks at p0 again: p1 is now the LRU page
            val bus = Bus()
            val job = p.observe(this, bus, initialSync = InitialSyncPolicy.None)
            if (withEvent) bus.events.emit(CursorReactiveEvent.Updated(Item(999, "not cached")))
            runCurrent()
            val extra = CursorBookmark<String>(prev = "p2", self = "p3", next = null)
            p.cache.setState(extra, CursorPageState.Success(extra, mutableListOf(Item(100, "x"))), silently = true)
            job.cancel()
            return p.cache.cursors.map { it.self }.sorted()
        }
        assertEquals(run(withEvent = false), run(withEvent = true), "a dropped reactive event changed which page LRU evicts")
    }

    // ── Batch rollback / cancellation vs concurrent navigation ───────────────────────────────────

    /** A Batch with an Invalidate that then fails does not roll back navigation the user did meanwhile. */
    @Test
    fun a_failing_batch_does_not_roll_back_concurrent_navigation() = runTest {
        val d = Db(30)
        val p = d.paginator(); p.start(2)
        val bus = Bus()
        val job = p.observe(this, bus, initialSync = InitialSyncPolicy.None)
        d.gate = CompletableDeferred(); d.gatedSelf = "p0"
        bus.events.emit(
            CursorReactiveEvent.Batch(
                listOf(
                    CursorReactiveEvent.Invalidate,
                    CursorReactiveEvent.Moved(Item(4, "moved"), CursorInsertPosition.At(self = "nope", index = 0)),
                )
            )
        )
        runCurrent()
        p.jump(bookmark(5)) // the user jumps on
        assertTrue(p.cache.cursors.any { it.self == "p5" }, "precondition")
        d.gate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(
            p.cache.cursors.any { it.self == "p5" } && p.core.startContextCursor?.self == "p5",
            "batch rollback wiped the user's jump: ${p.cache.cursors.map { it.self }}",
        )
        job.cancel()
    }

    /** Cancelling the observe Job mid-Batch does not roll back navigation the user did meanwhile. */
    @Test
    fun cancelling_observe_mid_batch_keeps_concurrent_navigation() = runTest {
        val d = Db(30)
        val p = d.paginator(); p.start(2)
        val bus = Bus()
        val job = p.observe(this, bus, initialSync = InitialSyncPolicy.None)
        d.gate = CompletableDeferred(); d.gatedSelf = "p0"
        bus.events.emit(
            CursorReactiveEvent.Batch(listOf(CursorReactiveEvent.Updated(Item(4, "u")), CursorReactiveEvent.Invalidate))
        )
        runCurrent()
        p.jump(bookmark(5))
        assertTrue(p.cache.cursors.any { it.self == "p5" }, "precondition")
        job.cancel() // e.g. the screen goes away
        advanceUntilIdle()
        assertTrue(
            p.cache.cursors.any { it.self == "p5" } && p.core.startContextCursor?.self == "p5",
            "observe cancellation wiped the user's jump: ${p.cache.cursors.map { it.self }}",
        )
    }

    // ── misc ─────────────────────────────────────────────────────────────────────────────────────

    /** UnknownItemPolicy.RefreshAll inside a Batch refreshes once, not once per unknown child. */
    @Test
    fun unknown_items_in_a_batch_refresh_once() = runTest {
        val d = Db(6)
        val p = d.paginator(); p.start(1)
        val bus = Bus()
        val job = p.observe(this, bus, initialSync = InitialSyncPolicy.None, unknownItem = UnknownItemPolicy.RefreshAll)
        val before = d.loads
        bus.events.emit(CursorReactiveEvent.Batch(List(5) { CursorReactiveEvent.Updated(Item(100 + it, "off-window")) }))
        advanceUntilIdle()
        assertEquals(1, d.loads - before, "one batch triggered ${d.loads - before} full refreshes")
        job.cancel()
    }

    /** Removed events that delete the viewed range re-anchor the window and realign the bookmark cursor. */
    @Test
    fun removed_viewed_range_realigns_bookmark_cursor() = runTest {
        val d = Db(30)
        val p = d.paginator()
        p.bookmarks.clear()
        p.bookmarks.addAll(listOf(bookmark(0), bookmark(3), bookmark(6)))
        p.start(3)
        p.jump(bookmark(5)); p.goNextPage(); p.goNextPage()
        assertEquals("p5", p.core.startContextCursor?.self)
        val bus = Bus()
        val job = p.observe(this, bus, initialSync = InitialSyncPolicy.None)
        for (page in 5..7) for (i in 0 until 3) bus.events.emit(CursorReactiveEvent.Removed(page * 3 + i + 1))
        runCurrent()
        assertEquals("p0", p.core.startContextCursor?.self, "window: ${p.cache.cursors.map { it.self }}")
        val forward = p.jumpForward(silentlyLoading = true, silentlyResult = true)
        assertEquals("p3", forward?.first?.self, "jumpForward went to ${forward?.first?.self}")
        job.cancel()
    }
}
