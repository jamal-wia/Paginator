package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.core.cache.reactive.InitialSyncPolicy
import com.jamal_aliev.paginator.core.cache.reactive.InsertPosition
import com.jamal_aliev.paginator.core.cache.reactive.PaginatorReactiveCache
import com.jamal_aliev.paginator.core.cache.reactive.ReactiveEvent
import com.jamal_aliev.paginator.core.cache.reactive.snapshotReactiveCache
import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.cache.eviction.MostRecentPagingCache
import com.jamal_aliev.paginator.offset.cache.eviction.TimeLimitedPagingCache
import com.jamal_aliev.paginator.offset.extension.observe
import com.jamal_aliev.paginator.offset.load.LoadResult
import com.jamal_aliev.paginator.offset.page.OffsetPageState
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * Regression tests for the consistency of `MutablePaginator.observe()`: source failures, races with
 * refresh and navigation, the initial sync, wholesale replacement and eviction bookkeeping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReactiveObserveRegressionTest {

    private data class Item(val id: Int, val label: String)

    private class Bus : PaginatorReactiveCache<Item, Int> {
        val events = MutableSharedFlow<ReactiveEvent<Item, Int>>(replay = 64)
        override fun identity(item: Item): Int = item.id
        override fun changes(): Flow<ReactiveEvent<Item, Int>> = events.asSharedFlow()
    }

    private fun rows(n: Int = 9) = List(n) { Item(it + 1, "v0-${it + 1}") }

    /** A "database": load reads a slice of [db] and, for gated pages, waits on [gate] AFTER reading. */
    private class Db(n: Int) {
        val db = MutableStateFlow(List(n) { Item(it + 1, "v0-${it + 1}") })
        var gate: CompletableDeferred<Unit>? = null
        var gatedPage: Int = -1
        var loads = 0
        fun paginator(cache: com.jamal_aliev.paginator.offset.cache.PagingCache<Item>? = null): MutablePaginator<Item> {
            val core = if (cache != null) PagingCore(cache = cache, initialCapacity = 3) else PagingCore(initialCapacity = 3)
            return MutablePaginator(core) { page ->
                loads++
                val slice = db.value.drop((page - 1) * 3).take(3)
                val g = gate
                if (page == gatedPage && g != null) g.await()
                LoadResult(slice.toMutableList())
            }
        }
        fun patch(id: Int, label: String) {
            db.value = db.value.map { if (it.id == id) it.copy(label = label) else it }
        }
    }

    private fun labels(p: MutablePaginator<Item>): List<String> =
        p.cache.pages.flatMap { p.cache.peekStateOf(it)!!.data.map { i -> i.label } }

    // ── Exceptions ────────────────────────────────────────────────────────────────────────────

    /** A failing source is reported and re-subscribed after a backoff; events flow again afterwards. */
    @Test
    fun a_failing_source_is_reported_and_resubscribed() = runTest {
        val d = Db(6)
        val p = d.paginator()
        p.jump(BookmarkInt(1))
        var subscriptions = 0
        val upstream = MutableSharedFlow<ReactiveEvent<Item, Int>>()
        val source = object : PaginatorReactiveCache<Item, Int> {
            override fun identity(item: Item) = item.id
            override fun changes(): Flow<ReactiveEvent<Item, Int>> = flow {
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
        upstream.emit(ReactiveEvent.Updated(Item(2, "after")))
        runCurrent()
        assertEquals("after", p.cache.peekStateOf(1)!!.data[1].label)
        job.cancel()
    }

    /** A CancellationException thrown by the source itself (withTimeout) is a failure: it is reported. */
    @Test
    fun a_cancellation_thrown_by_the_source_is_reported() = runTest {
        val d = Db(6)
        val p = d.paginator()
        p.jump(BookmarkInt(1))
        val source = object : PaginatorReactiveCache<Item, Int> {
            override fun identity(item: Item) = item.id
            override fun changes(): Flow<ReactiveEvent<Item, Int>> = flow {
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
        val p = d.paginator()
        p.jump(BookmarkInt(1))
        val bus = Bus()
        var calls = 0
        val escaped = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, e -> escaped += e })
        p.observe(scope, bus, initialSync = InitialSyncPolicy.None, onError = { calls++; throw it })
        // Moved to an uncached explicit coordinate: the transaction's check() throws.
        bus.events.emit(ReactiveEvent.Moved(Item(1, "x"), InsertPosition.At(page = 99, index = 0)))
        runCurrent()
        assertEquals(1, escaped.size)
        assertEquals(1, calls, "onError invoked $calls times for a single failure")
    }

    // ── Races with refresh ────────────────────────────────────────────────────────────────────

    /** An Updated applied while refresh loads that page is not overwritten by the older refresh result. */
    @Test
    fun an_update_during_refresh_survives_the_refresh_result() = runTest {
        val d = Db(6)
        val p = d.paginator()
        p.jump(BookmarkInt(1)); p.goNextPage()
        val bus = Bus()
        val job = p.observe(this, bus, initialSync = InitialSyncPolicy.None)
        runCurrent()
        d.gate = CompletableDeferred(); d.gatedPage = 2
        val refresh = launch { p.refresh(listOf(2)) }
        runCurrent() // refresh read the db (old label) and waits
        d.patch(5, "patched")
        bus.events.emit(ReactiveEvent.Updated(Item(5, "patched")))
        runCurrent()
        assertEquals("patched", p.cache.peekStateOf(2)!!.data[1].label) // applied into the Progress page
        d.gate!!.complete(Unit)
        refresh.join()
        assertEquals(d.db.value.map { it.label }, labels(p), "live update lost to the older refresh result")
        job.cancel()
    }

    // ── Initial sync ──────────────────────────────────────────────────────────────────────────

    /** A write that lands while the initial RefreshAll loads still reaches the cache (subscribe first). */
    @Test
    fun a_write_during_the_initial_sync_reaches_the_cache() = runTest {
        val d = Db(6)
        val p = d.paginator()
        p.jump(BookmarkInt(1)); p.goNextPage()
        d.gate = CompletableDeferred(); d.gatedPage = 1
        val job = p.observe(this, snapshotReactiveCache(d.db, identity = { it.id }))
        runCurrent() // initial refreshAll read page 1 and waits on the gate
        d.patch(2, "patched")   // a database write during the initial sync
        runCurrent()
        d.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(d.db.value.map { it.label }, labels(p), "write during initial sync never reached the cache")
        job.cancel()
    }

    // ── Lifecycle: restoreState ───────────────────────────────────────────────────────────────

    /** observe() on an empty (recreated) paginator, then restoreState: the stale saved rows are reconciled. */
    @Test
    fun restoreState_while_observing_is_reconciled() = runTest {
        val d = Db(6)
        val first = d.paginator()
        first.jump(BookmarkInt(1)); first.goNextPage()
        val saved = first.saveState()
        d.patch(2, "changed-while-dead")
        val p = d.paginator()
        val job = p.observe(this, snapshotReactiveCache(d.db, identity = { it.id }))
        runCurrent()
        p.restoreState(saved)
        advanceUntilIdle()
        assertEquals(d.db.value.map { it.label }, labels(p), "restored stale rows never reconciled")
        job.cancel()
    }

    // ── LRU / TTL ─────────────────────────────────────────────────────────────────────────────

    /** An Updated event for an item that is NOT cached (dropped) does not renew any page's TTL. */
    @Test
    fun a_dropped_event_does_not_renew_ttl() = runTest {
        suspend fun run(withEvent: Boolean): List<Int> {
            val clock = TestTimeSource()
            val ttlCache = TimeLimitedPagingCache<Item>(
                ttl = 10.seconds, refreshOnAccess = true, protectContextWindow = false, timeSource = clock,
            )
            val d = Db(30)
            val p = d.paginator(ttlCache)
            p.jump(BookmarkInt(1)); p.goNextPage(); p.goNextPage()
            val bus = Bus()
            val job = p.observe(this, bus, initialSync = InitialSyncPolicy.None)
            clock += 6.seconds
            if (withEvent) bus.events.emit(ReactiveEvent.Updated(Item(999, "not cached")))
            runCurrent()
            clock += 6.seconds // 12 s since pages 1..3 were last used by the user
            p.cache.setState(OffsetPageState.Success(page = 9, data = mutableListOf(Item(100, "x"))), silently = true)
            job.cancel()
            return p.cache.pages
        }
        val control = run(withEvent = false)
        val probe = run(withEvent = true)
        assertEquals(control, probe, "a dropped reactive event renewed TTLs")
    }

    /** Scanning every page for an event's identity does not reshape the LRU order. */
    @Test
    fun an_event_scan_does_not_reshape_lru_order() = runTest {
        suspend fun run(withEvent: Boolean): List<Int> {
            val lru = MostRecentPagingCache<Item>(maxSize = 3, protectContextWindow = false)
            val d = Db(12)
            val p = d.paginator(lru)
            p.jump(BookmarkInt(1)); p.goNextPage(); p.goNextPage()
            p.cache.getStateOf(1) // the user looks at page 1 again: page 2 is now the LRU page
            val bus = Bus()
            val job = p.observe(this, bus, initialSync = InitialSyncPolicy.None)
            if (withEvent) bus.events.emit(ReactiveEvent.Updated(Item(999, "not cached")))
            runCurrent()
            p.cache.setState(OffsetPageState.Success(page = 4, data = mutableListOf(Item(100, "x"))), silently = true)
            job.cancel()
            return p.cache.pages
        }
        val control = run(withEvent = false)
        val probe = run(withEvent = true)
        assertEquals(control, probe, "a dropped reactive event changed which page LRU evicts")
    }

    // ── Window invariants ─────────────────────────────────────────────────────────────────────

    /** Removing every cached row publishes an empty snapshot: the UI does not keep the deleted rows. */
    @Test
    fun removing_every_row_publishes_an_empty_snapshot() = runTest {
        val d = Db(3)
        val p = d.paginator()
        p.jump(BookmarkInt(1))
        val bus = Bus()
        val job = p.observe(this, bus, initialSync = InitialSyncPolicy.None)
        (1..3).forEach { bus.events.emit(ReactiveEvent.Removed(it)) }
        runCurrent()
        val shown = p.core.snapshot.first().flatMap { it.data }
        assertEquals(emptyList(), shown, "UI still shows deleted rows; window=${p.cache.startContextPage}..${p.cache.endContextPage}")
        job.cancel()
    }

    // ── Batch rollback / cancellation vs concurrent navigation ────────────────────────────────

    /** A Batch with an Invalidate that then fails does not roll back navigation the user did meanwhile. */
    @Test
    fun a_failing_batch_does_not_roll_back_concurrent_navigation() = runTest {
        val d = Db(12)
        val p = d.paginator()
        p.jump(BookmarkInt(1)); p.goNextPage()
        val bus = Bus()
        val job = p.observe(this, bus, initialSync = InitialSyncPolicy.None)
        d.gate = CompletableDeferred(); d.gatedPage = 1
        bus.events.emit(
            ReactiveEvent.Batch(
                listOf(
                    ReactiveEvent.Invalidate,
                    ReactiveEvent.Moved(Item(4, "moved"), InsertPosition.At(page = 99, index = 0)),
                )
            )
        )
        runCurrent() // batch's refreshAll waits on page 1
        p.jump(BookmarkInt(3)) // the user jumps on: page 3 loaded, window 3..3
        assertTrue(3 in p.cache.pages, "precondition ${p.cache.pages}")
        d.gate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(3 in p.cache.pages && p.cache.startContextPage == 3,
            "batch rollback wiped the user's jump: pages=${p.cache.pages} window=${p.cache.startContextPage}")
        job.cancel()
    }

    /** Cancelling the observe Job mid-Batch does not roll back navigation the user did meanwhile. */
    @Test
    fun cancelling_observe_mid_batch_keeps_concurrent_navigation() = runTest {
        val d = Db(12)
        val p = d.paginator()
        p.jump(BookmarkInt(1)); p.goNextPage()
        val bus = Bus()
        val job = p.observe(this, bus, initialSync = InitialSyncPolicy.None)
        d.gate = CompletableDeferred(); d.gatedPage = 1
        bus.events.emit(ReactiveEvent.Batch(listOf(ReactiveEvent.Updated(Item(4, "u")), ReactiveEvent.Invalidate)))
        runCurrent()
        p.jump(BookmarkInt(3))
        assertTrue(3 in p.cache.pages, "precondition ${p.cache.pages}")
        job.cancel() // e.g. the screen goes away
        advanceUntilIdle()
        assertTrue(3 in p.cache.pages && p.cache.startContextPage == 3,
            "observe cancellation wiped the user's jump: pages=${p.cache.pages} window=${p.cache.startContextPage}")
    }

    // ── misc ─────────────────────────────────────────────────────────────────────────────────────

    /** UnknownItemPolicy.RefreshAll inside a Batch refreshes once, not once per unknown child. */
    @Test
    fun unknown_items_in_a_batch_refresh_once() = runTest {
        val d = Db(6)
        val p = d.paginator()
        p.jump(BookmarkInt(1))
        val bus = Bus()
        val job = p.observe(
            this, bus, initialSync = InitialSyncPolicy.None,
            unknownItem = com.jamal_aliev.paginator.core.cache.reactive.UnknownItemPolicy.RefreshAll,
        )
        val before = d.loads
        bus.events.emit(ReactiveEvent.Batch(List(5) { ReactiveEvent.Updated(Item(100 + it, "off-window")) }))
        advanceUntilIdle()
        assertEquals(1, d.loads - before, "one batch triggered ${d.loads - before} full refreshes")
        job.cancel()
    }

    /** Removed events that delete the viewed range re-anchor the window and realign the bookmark cursor. */
    @Test
    fun removed_viewed_range_realigns_bookmark_cursor() = runTest {
        val p = MutablePaginator<Item> { page ->
            val has = page in 1..3 || page in 11..13
            LoadResult(if (has) MutableList(3) { Item(page * 100 + it, "p$page") } else mutableListOf())
        }.apply { core.resize(capacity = 3, resize = false, silently = true) }
        p.bookmarks.clear()
        p.bookmarks.addAll(listOf(BookmarkInt(2), BookmarkInt(5), BookmarkInt(12)))
        p.jump(BookmarkInt(1)); p.goNextPage(); p.goNextPage()
        p.jump(BookmarkInt(11)); p.goNextPage(); p.goNextPage()
        assertEquals(11, p.cache.startContextPage)
        val bus = Bus()
        val job = p.observe(this, bus, initialSync = InitialSyncPolicy.None)
        for (page in 11..13) for (i in 0 until 3) bus.events.emit(ReactiveEvent.Removed(page * 100 + i))
        runCurrent()
        assertEquals(1, p.cache.startContextPage, "window=${p.cache.startContextPage}..${p.cache.endContextPage} pages=${p.cache.pages}")
        val forward = p.jumpForward(silentlyLoading = true, silentlyResult = true)
        assertEquals(5, forward?.first?.page, "jumpForward went to ${forward?.first?.page}")
        job.cancel()
    }
}
