package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.core.cache.reactive.InitialSyncPolicy
import com.jamal_aliev.paginator.core.cache.reactive.UnknownItemPolicy
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.cache.reactive.CursorInsertPosition
import com.jamal_aliev.paginator.cursor.cache.reactive.CursorPaginatorReactiveCache
import com.jamal_aliev.paginator.cursor.cache.reactive.CursorReactiveEvent
import com.jamal_aliev.paginator.cursor.extension.observe
import com.jamal_aliev.paginator.cursor.load.CursorLoadResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cursor-paginator counterpart of `PaginatorReactiveObserveTest`. Covers the
 * `MutableCursorPaginator.observe(scope, source)` extension and the
 * [CursorPaginatorReactiveCache] → CRUD dispatch path.
 *
 * Each test wires a [MutableCursorPaginator] of [Item] (id + label) to a fake
 * source whose `changes()` flow is a [MutableSharedFlow] the test drives
 * directly. Identity is the item's `id`. The fake backend hands out 3-item
 * pages keyed by `self` ∈ {"p0", "p1", "p2", ...} linked head-to-tail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CursorPaginatorReactiveObserveTest {

    private data class Item(val id: Int, val label: String)

    private class FakeReactiveCache : CursorPaginatorReactiveCache<Item, Int> {
        // `replay = 64` is for test reliability only: the test body emits
        // events before runTest dispatches the observe() coroutine, so without
        // replay the events would be dropped. Real adapters (Room, SQLDelight)
        // don't need this — their flows are subscribed eagerly from the
        // collector side.
        private val events = MutableSharedFlow<CursorReactiveEvent<Item, Int>>(replay = 64)
        override fun identity(item: Item): Int = item.id
        override fun changes(): Flow<CursorReactiveEvent<Item, Int>> = events.asSharedFlow()
        suspend fun emit(event: CursorReactiveEvent<Item, Int>) = events.emit(event)
    }

    /**
     * A linked, paged backend whose page contents can be re-versioned across
     * `reloadVersion` to make `refreshAll()` effects observable.
     *
     *  - page count = `pageCount`, items per page = 3
     *  - page `i` `self` = "p$i", ids ∈ [i*3+1 .. i*3+3]
     *  - prev/next link to the neighbours
     */
    private class IntBackend(
        val pageCount: Int = 3,
        var reloadVersion: Int = 0,
    ) {
        private fun pageOf(self: String): Int = self.removePrefix("p").toInt()

        private fun pageData(page: Int): List<Item> {
            val base = page * 3
            val ver = reloadVersion
            return List(3) { i -> Item(id = base + i + 1, label = "v$ver-${base + i + 1}") }
        }

        fun load(cursor: CursorBookmark<String>?): CursorLoadResult<String, Item> {
            val self = cursor?.self as? String
            val prev = cursor?.prev as? String
            val next = cursor?.next as? String
            val targetSelf: String = when {
                cursor == null -> "p0"
                self != null && self.startsWith("p") -> self
                prev != null && prev.startsWith("p") -> "p${pageOf(prev) + 1}"
                next != null && next.startsWith("p") -> "p${pageOf(next) - 1}"
                else -> error("unknown cursor: $cursor")
            }
            val page = pageOf(targetSelf)
            return CursorLoadResult(
                data = pageData(page),
                bookmark = CursorBookmark<String>(
                    prev = if (page == 0) null else "p${page - 1}",
                    self = targetSelf,
                    next = if (page == pageCount - 1) null else "p${page + 1}",
                ),
            )
        }
    }

    private suspend fun populatedPaginator(
        backend: IntBackend = IntBackend(pageCount = 3),
    ): MutableCursorPaginator<String, Item> {
        val core = CursorPagingCore<String, Item>(initialCapacity = 3)
        val paginator = MutableCursorPaginator<String, Item>(core = core) { cursor ->
            backend.load(cursor)
        }
        // Bootstrap: load p0, then walk forward to p1 so the cache holds 2 pages.
        paginator.jump(
            bookmark = CursorBookmark<String>(prev = null, self = "p0", next = null),
            silentlyLoading = true,
            silentlyResult = true,
        )
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        return paginator
    }

    @Test
    fun `Updated event replaces matching item by identity`() = runTest {
        val paginator = populatedPaginator()
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
        )

        source.emit(CursorReactiveEvent.Updated(Item(id = 5, label = "patched")))
        runCurrent()

        val page1 = paginator.cache.getStateOf("p1")
        assertNotNull(page1)
        val patched = page1.data.first { it.id == 5 }
        assertEquals("patched", patched.label)

        job.cancel()
    }

    @Test
    fun `Removed event removes matching item and rebalances`() = runTest {
        val paginator = populatedPaginator()
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
        )

        source.emit(CursorReactiveEvent.Removed(identity = 2))
        runCurrent()

        // Page p0 had ids 1,2,3 with capacity 3. Removing id 2 should pull id 4
        // from page p1 into page p0, so p0 ends up as 1,3,4 and p1 has 5,6.
        val p0 = paginator.cache.getStateOf("p0")
        val p1 = paginator.cache.getStateOf("p1")
        assertNotNull(p0); assertNotNull(p1)
        assertEquals(listOf(1, 3, 4), p0.data.map { it.id })
        assertEquals(listOf(5, 6), p1.data.map { it.id })

        job.cancel()
    }

    @Test
    fun `Inserted Head places item at front of head page with overflow`() = runTest {
        val paginator = populatedPaginator()
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
        )

        source.emit(CursorReactiveEvent.Inserted(Item(0, "head"), CursorInsertPosition.Head))
        runCurrent()

        val p0 = paginator.cache.getStateOf("p0")!!
        val p1 = paginator.cache.getStateOf("p1")!!
        assertEquals("head", p0.data.first().label)
        assertEquals(listOf(0, 1, 2), p0.data.map { it.id })
        // id=3 overflowed into p1 head.
        assertEquals(3, p1.data.first().id)

        job.cancel()
    }

    @Test
    fun `Inserted Tail appends to tail page when there is room`() = runTest {
        // Single-page paginator at capacity 5; tail page p0 has only 3 items
        // so the Tail insert lands without triggering overflow. (At full
        // capacity with no cached `next` page and no bookmarkFactory the
        // observe path drops the overflow — there is no factory hook on the
        // reactive surface to mint a new tail page.)
        val backend = IntBackend(pageCount = 3)
        val core = CursorPagingCore<String, Item>(initialCapacity = 5)
        val paginator = MutableCursorPaginator<String, Item>(core = core) { cursor ->
            backend.load(cursor)
        }
        paginator.jump(
            bookmark = CursorBookmark<String>(prev = null, self = "p0", next = null),
            silentlyLoading = true,
            silentlyResult = true,
        )

        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
        )

        source.emit(CursorReactiveEvent.Inserted(Item(999, "tail"), CursorInsertPosition.Tail))
        runCurrent()

        val p0 = paginator.cache.getStateOf("p0")!!
        assertEquals(listOf(1, 2, 3, 999), p0.data.map { it.id })
        assertEquals("tail", p0.data.last().label)

        job.cancel()
    }

    @Test
    fun `Inserted AfterIdentity places item right after the anchor`() = runTest {
        val paginator = populatedPaginator()
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
        )

        source.emit(
            CursorReactiveEvent.Inserted(
                item = Item(99, "after-1"),
                position = CursorInsertPosition.AfterIdentity(1),
            ),
        )
        runCurrent()

        val p0 = paginator.cache.getStateOf("p0")!!
        // 1, 99, 2  → id 3 overflows into p1
        assertEquals(listOf(1, 99, 2), p0.data.map { it.id })

        job.cancel()
    }

    @Test
    fun `Inserted with already-cached identity updates in place under default dedup`() = runTest {
        val paginator = populatedPaginator()
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
        )

        // id 2 is already in page p0; re-inserting it must not duplicate.
        source.emit(CursorReactiveEvent.Inserted(Item(2, "dup"), CursorInsertPosition.Head))
        runCurrent()

        val p0 = paginator.cache.getStateOf("p0")!!
        assertEquals(listOf(1, 2, 3), p0.data.map { it.id })          // order unchanged
        assertEquals(1, p0.data.count { it.id == 2 })                 // no duplicate
        assertEquals("dup", p0.data.first { it.id == 2 }.label)       // content updated

        job.cancel()
    }

    @Test
    fun `Inserted with already-cached identity duplicates when dedup disabled`() = runTest {
        val paginator = populatedPaginator()
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
            deduplicateInserts = false,
        )

        source.emit(CursorReactiveEvent.Inserted(Item(2, "dup"), CursorInsertPosition.Head))
        runCurrent()

        // Literal insert: id 2 now appears twice across the cache.
        val allIds = paginator.cache.cursors
            .flatMap { paginator.cache.getStateOf(it.self)!!.data.map { i -> i.id } }
        assertEquals(2, allIds.count { it == 2 })

        job.cancel()
    }

    @Test
    fun `Inserted BeforeIdentity places item right before the anchor`() = runTest {
        val paginator = populatedPaginator()
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
        )

        source.emit(
            CursorReactiveEvent.Inserted(
                item = Item(77, "before-2"),
                position = CursorInsertPosition.BeforeIdentity(2),
            ),
        )
        runCurrent()

        val p0 = paginator.cache.getStateOf("p0")!!
        // [1, 77, 2] — id 3 overflows into p1
        assertEquals(listOf(1, 77, 2), p0.data.map { it.id })

        job.cancel()
    }

    @Test
    fun `Inserted At cursor coordinate inserts at the given self`() = runTest {
        val paginator = populatedPaginator()
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
        )

        source.emit(
            CursorReactiveEvent.Inserted(
                item = Item(55, "at-p1-0"),
                position = CursorInsertPosition.At(self = "p1", index = 0),
            ),
        )
        runCurrent()

        val p1 = paginator.cache.getStateOf("p1")!!
        assertEquals(listOf(55, 4, 5), p1.data.map { it.id })

        job.cancel()
    }

    @Test
    fun `Inserted At with unknown self and Drop policy is a no-op`() = runTest {
        val paginator = populatedPaginator()
        val before: List<List<Int>> = paginator.cache.cursors.map { c ->
            paginator.cache.getStateOf(c.self)!!.data.map { it.id }
        }
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
            unknownItem = UnknownItemPolicy.Drop,
        )

        source.emit(
            CursorReactiveEvent.Inserted(
                item = Item(99, "x"),
                position = CursorInsertPosition.At(self = "p404", index = 0),
            ),
        )
        runCurrent()

        val after: List<List<Int>> = paginator.cache.cursors.map { c ->
            paginator.cache.getStateOf(c.self)!!.data.map { it.id }
        }
        assertEquals(before, after)

        job.cancel()
    }

    @Test
    fun `Inserted AfterIdentity with unknown anchor and Drop policy is a no-op`() = runTest {
        val paginator = populatedPaginator()
        val before: List<List<Int>> = paginator.cache.cursors.map { c ->
            paginator.cache.getStateOf(c.self)!!.data.map { it.id }
        }
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
            unknownItem = UnknownItemPolicy.Drop,
        )

        source.emit(
            CursorReactiveEvent.Inserted(
                item = Item(99, "x"),
                position = CursorInsertPosition.AfterIdentity(identity = 9999),
            ),
        )
        runCurrent()

        val after: List<List<Int>> = paginator.cache.cursors.map { c ->
            paginator.cache.getStateOf(c.self)!!.data.map { it.id }
        }
        assertEquals(before, after)

        job.cancel()
    }

    @Test
    fun `Updated with unknown identity and RefreshAll policy reloads pages`() = runTest {
        val backend = IntBackend(pageCount = 3)
        val paginator = populatedPaginator(backend)

        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
            unknownItem = UnknownItemPolicy.RefreshAll,
        )

        // Bump source version so a refreshAll() will be observable.
        backend.reloadVersion = 7
        source.emit(CursorReactiveEvent.Updated(Item(id = 9999, label = "ghost")))
        runCurrent()

        // Pages reloaded with v7.
        assertEquals("v7-1", paginator.cache.getStateOf("p0")!!.data.first().label)
        assertEquals("v7-4", paginator.cache.getStateOf("p1")!!.data.first().label)

        job.cancel()
    }

    @Test
    fun `Invalidate event triggers refreshAll`() = runTest {
        val backend = IntBackend(pageCount = 3)
        val paginator = populatedPaginator(backend)

        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
        )

        backend.reloadVersion = 42
        source.emit(CursorReactiveEvent.Invalidate)
        runCurrent()

        assertEquals("v42-1", paginator.cache.getStateOf("p0")!!.data.first().label)
        job.cancel()
    }

    @Test
    fun `Batch event applies child events atomically`() = runTest {
        val paginator = populatedPaginator()
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
        )

        source.emit(
            CursorReactiveEvent.Batch<Item, Int>(
                events = listOf(
                    CursorReactiveEvent.Updated(Item(1, "one!")),
                    CursorReactiveEvent.Updated(Item(4, "four!")),
                    CursorReactiveEvent.Removed(identity = 2),
                ),
            ),
        )
        runCurrent()

        val p0 = paginator.cache.getStateOf("p0")!!
        val p1 = paginator.cache.getStateOf("p1")!!
        // p0 starts [1,2,3]; after remove id=2 + rebalance pulls id=4 from p1.
        assertEquals(listOf(1, 3, 4), p0.data.map { it.id })
        assertEquals("one!", p0.data[0].label)
        assertEquals("four!", p0.data[2].label)
        assertEquals(listOf(5, 6), p1.data.map { it.id })

        job.cancel()
    }

    @Test
    fun `initial sync RefreshAll reconciles cached pages on subscribe`() = runTest {
        val backend = IntBackend(pageCount = 3)
        val paginator = populatedPaginator(backend)
        // Source-of-truth changed while paginator wasn't subscribed.
        backend.reloadVersion = 1

        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.RefreshAll,
        )
        runCurrent()

        assertEquals("v1-1", paginator.cache.getStateOf("p0")!!.data.first().label)
        job.cancel()
    }

    @Test
    fun `cancelling job stops collecting and further emits have no effect`() = runTest {
        val paginator = populatedPaginator()
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
        )

        job.cancel()
        runCurrent()

        // This emit happens after cancellation; observer is gone.
        source.emit(CursorReactiveEvent.Updated(Item(1, "ghost-update")))
        runCurrent()

        val p0 = paginator.cache.getStateOf("p0")!!
        // Original label on p0 item 1 is "v0-1", not "ghost-update".
        assertEquals("v0-1", p0.data.first().label)
        assertTrue(job.isCancelled)
        assertNull(p0.data.firstOrNull { it.label == "ghost-update" })
    }

    @Test
    fun `Moved event removes from old location and inserts at new position atomically`() =
        runTest {
            val paginator = populatedPaginator()
            val source = FakeReactiveCache()
            val job = paginator.observe(
                scope = this,
                source = source,
                initialSync = InitialSyncPolicy.None,
            )

            // Move id=5 (currently on p1) to right after id=1 on p0. The
            // item carries a new label too — Moved doubles as Updated.
            source.emit(
                CursorReactiveEvent.Moved(
                    item = Item(id = 5, label = "moved!"),
                    position = CursorInsertPosition.AfterIdentity(identity = 1),
                ),
            )
            runCurrent()

            val p0 = paginator.cache.getStateOf("p0")!!
            val p1 = paginator.cache.getStateOf("p1")!!
            // p0 was [1,2,3]; insert-after(1) → [1,5,2] with 3 cascading into p1.
            // p1 was [4,5,6] → remove id=5 → [4,6], then cascade adds 3 at front → [3,4,6].
            assertEquals(listOf(1, 5, 2), p0.data.map { it.id })
            assertEquals("moved!", p0.data[1].label)
            assertEquals(listOf(3, 4, 6), p1.data.map { it.id })

            job.cancel()
        }

    @Test
    fun `Moved for unknown identity falls through to UnknownItemPolicy`() = runTest {
        val paginator = populatedPaginator()
        val before: List<List<Int>> = paginator.cache.cursors.map { c ->
            paginator.cache.getStateOf(c.self)!!.data.map { it.id }
        }
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
            unknownItem = UnknownItemPolicy.Drop,
        )

        source.emit(
            CursorReactiveEvent.Moved(
                item = Item(id = 9999, label = "ghost"),
                position = CursorInsertPosition.Head,
            ),
        )
        runCurrent()

        val after: List<List<Int>> = paginator.cache.cursors.map { c ->
            paginator.cache.getStateOf(c.self)!!.data.map { it.id }
        }
        assertEquals(before, after)

        job.cancel()
    }

    @Test
    fun `observe twice on the same paginator throws while previous subscription is active`() =
        runTest {
            val paginator = populatedPaginator()
            val source = FakeReactiveCache()
            val first = paginator.observe(
                scope = this,
                source = source,
                initialSync = InitialSyncPolicy.None,
            )

            assertFailsWith<IllegalStateException> {
                paginator.observe(
                    scope = this,
                    source = source,
                    initialSync = InitialSyncPolicy.None,
                )
            }

            first.cancel()
        }

    @Test
    fun `observe can be re-subscribed after previous Job completes`() = runTest {
        val paginator = populatedPaginator()
        val source = FakeReactiveCache()
        val first = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
        )
        first.cancel()
        runCurrent()

        // Slot is freed on completion; second subscribe must succeed and accept events.
        val second = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
        )
        source.emit(CursorReactiveEvent.Updated(Item(id = 1, label = "after-resub")))
        runCurrent()

        assertEquals("after-resub", paginator.cache.getStateOf("p0")!!.data.first().label)
        assertNotEquals(first, second)

        second.cancel()
    }

    // =========================================================================
    // bookmarkFactory / initPageState: tail-overflow page creation on the
    // reactive path. Cursors are server-issued, so creation is opt-in via a
    // bookmarkFactory — observe() now exposes it (the offset analogue of
    // observe(initPageState = …)).
    // =========================================================================

    @Test
    fun `Inserted Tail overflow creates a tail page when a bookmarkFactory is supplied`() = runTest {
        val paginator = populatedPaginator(IntBackend(pageCount = 2)) // p0,p1 cached; p1 is the genuine tail
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
            bookmarkFactory = { idx, previous ->
                CursorBookmark(prev = previous.self, self = "ovf$idx", next = null)
            },
        )

        source.emit(CursorReactiveEvent.Inserted(Item(99, "tail"), CursorInsertPosition.Tail))
        runCurrent()

        // p1 = [4,5,6]; appending 99 overflows it into a freshly minted tail page.
        val tail = paginator.cache.getStateOf("ovf0")
        assertNotNull(tail)
        assertEquals(listOf(99), tail.data.map { it.id })
        assertEquals("ovf0", tail.bookmark.self)

        job.cancel()
    }

    @Test
    fun `Inserted Tail overflow is dropped by default (no bookmarkFactory)`() = runTest {
        val paginator = populatedPaginator(IntBackend(pageCount = 2))
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
        )

        source.emit(CursorReactiveEvent.Inserted(Item(99, "tail"), CursorInsertPosition.Tail))
        runCurrent()

        // The documented cursor behaviour: without a bookmarkFactory the client cannot mint a `self`,
        // so the tail overflow is dropped and no page is created.
        assertNull(paginator.cache.getStateOf("ovf0"))
        assertEquals(listOf(4, 5, 6), paginator.cache.getStateOf("p1")!!.data.map { it.id })

        job.cancel()
    }
}
