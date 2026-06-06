package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.core.cache.reactive.InitialSyncPolicy
import com.jamal_aliev.paginator.core.cache.reactive.InsertPosition
import com.jamal_aliev.paginator.core.cache.reactive.PaginatorReactiveCache
import com.jamal_aliev.paginator.core.cache.reactive.ReactiveEvent
import com.jamal_aliev.paginator.core.cache.reactive.UnknownItemPolicy
import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.extension.observe
import com.jamal_aliev.paginator.offset.load.LoadResult
import com.jamal_aliev.paginator.offset.page.OffsetPageState
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
 * Covers the `MutablePaginator.observe(scope, source)` extension and the
 * [PaginatorReactiveCache] → CRUD dispatch path.
 *
 * Each test wires a [MutablePaginator] of [Item] (id + label) to a fake
 * source whose `changes()` flow is a [MutableSharedFlow] the test drives
 * directly. Identity is the item's `id`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaginatorReactiveObserveTest {

    private data class Item(val id: Int, val label: String)

    private class FakeReactiveCache : PaginatorReactiveCache<Item, Int> {
        // `replay = 64` is for test reliability only: the test body emits
        // events before runTest dispatches the observe() coroutine, so without
        // replay the events would be dropped. Real adapters (Room, SQLDelight)
        // don't need this — their flows are subscribed eagerly from the
        // collector side.
        private val events = MutableSharedFlow<ReactiveEvent<Item, Int>>(replay = 64)
        override fun identity(item: Item): Int = item.id
        override fun changes(): Flow<ReactiveEvent<Item, Int>> = events.asSharedFlow()
        suspend fun emit(event: ReactiveEvent<Item, Int>) = events.emit(event)
    }

    private suspend fun populatedPaginator(): MutablePaginator<Item> {
        val paginator = MutablePaginator<Item> { page ->
            // 3 items per page, ids continue: page 1 → 1,2,3; page 2 → 4,5,6
            val base = (page - 1) * 3
            LoadResult(MutableList(3) { i ->
                Item(
                    id = base + i + 1,
                    label = "v0-${base + i + 1}"
                )
            })
        }
        paginator.core.resize(capacity = 3, resize = false, silently = true)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
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

        source.emit(ReactiveEvent.Updated(Item(id = 5, label = "patched")))

        // Let the collector run.
        runCurrent()

        val page2 = paginator.cache.getStateOf(2)
        assertNotNull(page2)
        val patched = page2.data.first { it.id == 5 }
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

        source.emit(ReactiveEvent.Removed(identity = 2))
        runCurrent()

        // Page 1 had ids 1,2,3 with capacity 3. Removing id 2 should pull id 4
        // from page 2 into page 1, so page 1 ends up as 1,3,4 and page 2 has 5,6.
        val page1 = paginator.cache.getStateOf(1)
        val page2 = paginator.cache.getStateOf(2)
        assertNotNull(page1); assertNotNull(page2)
        assertEquals(listOf(1, 3, 4), page1.data.map { it.id })
        assertEquals(listOf(5, 6), page2.data.map { it.id })

        job.cancel()
    }

    @Test
    fun `Inserted Head places item at front of first page with overflow`() = runTest {
        val paginator = populatedPaginator()
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
        )

        source.emit(ReactiveEvent.Inserted(Item(0, "head"), InsertPosition.Head))
        runCurrent()

        val page1 = paginator.cache.getStateOf(1)!!
        val page2 = paginator.cache.getStateOf(2)!!
        assertEquals("head", page1.data.first().label)
        assertEquals(listOf(0, 1, 2), page1.data.map { it.id })
        // Item 3 overflowed into page 2 head; 6 dropped into page 3 (created via cascade).
        assertEquals(3, page2.data.first().id)

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
            ReactiveEvent.Inserted(
                item = Item(99, "after-1"),
                position = InsertPosition.AfterIdentity(1),
            ),
        )
        runCurrent()

        val page1 = paginator.cache.getStateOf(1)!!
        // 1, 99, 2  → id 3 overflows into page 2
        assertEquals(listOf(1, 99, 2), page1.data.map { it.id })

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

        // id 2 is already in page 1; re-inserting it must not duplicate.
        source.emit(ReactiveEvent.Inserted(Item(2, "dup"), InsertPosition.Head))
        runCurrent()

        val page1 = paginator.cache.getStateOf(1)!!
        assertEquals(listOf(1, 2, 3), page1.data.map { it.id })           // order unchanged
        assertEquals(1, page1.data.count { it.id == 2 })                  // no duplicate
        assertEquals("dup", page1.data.first { it.id == 2 }.label)        // content updated

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

        source.emit(ReactiveEvent.Inserted(Item(2, "dup"), InsertPosition.Head))
        runCurrent()

        // Literal insert: id 2 now appears twice across the cache.
        val allIds =
            paginator.cache.pages.flatMap { paginator.cache.getStateOf(it)!!.data.map { i -> i.id } }
        assertEquals(2, allIds.count { it == 2 })

        job.cancel()
    }

    @Test
    fun `Inserted AfterIdentity with unknown anchor and Drop policy is a no-op`() = runTest {
        val paginator = populatedPaginator()
        val before: List<List<Int>> =
            paginator.cache.pages.map { paginator.cache.getStateOf(it)!!.data.map { i -> i.id } }
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
            unknownItem = UnknownItemPolicy.Drop,
        )

        source.emit(
            ReactiveEvent.Inserted(
                item = Item(99, "x"),
                position = InsertPosition.AfterIdentity(identity = 9999),
            ),
        )
        runCurrent()

        val after: List<List<Int>> =
            paginator.cache.pages.map { paginator.cache.getStateOf(it)!!.data.map { i -> i.id } }
        assertEquals(before, after)

        job.cancel()
    }

    @Test
    fun `Updated with unknown identity and RefreshAll policy reloads pages`() = runTest {
        var reloadVersion = 0
        val paginator = MutablePaginator<Item> { page ->
            val base = (page - 1) * 3
            val ver = reloadVersion
            LoadResult(MutableList(3) { i ->
                Item(
                    id = base + i + 1,
                    label = "v$ver-${base + i + 1}"
                )
            })
        }
        paginator.core.resize(capacity = 3, resize = false, silently = true)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
            unknownItem = UnknownItemPolicy.RefreshAll,
        )

        // Bump source version so a refreshAll() will be observable.
        reloadVersion = 7
        source.emit(ReactiveEvent.Updated(Item(id = 9999, label = "ghost")))
        runCurrent()

        // Pages reloaded with v7.
        assertEquals("v7-1", paginator.cache.getStateOf(1)!!.data.first().label)
        assertEquals("v7-4", paginator.cache.getStateOf(2)!!.data.first().label)

        job.cancel()
    }

    @Test
    fun `Invalidate event triggers refreshAll`() = runTest {
        var reloadVersion = 0
        val paginator = MutablePaginator<Item> { page ->
            val base = (page - 1) * 3
            val ver = reloadVersion
            LoadResult(MutableList(3) { i ->
                Item(
                    id = base + i + 1,
                    label = "v$ver-${base + i + 1}"
                )
            })
        }
        paginator.core.resize(capacity = 3, resize = false, silently = true)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
        )

        reloadVersion = 42
        source.emit(ReactiveEvent.Invalidate)
        runCurrent()

        assertEquals("v42-1", paginator.cache.getStateOf(1)!!.data.first().label)
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
            ReactiveEvent.Batch<Item, Int>(
                events = listOf(
                    ReactiveEvent.Updated(Item(1, "one!")),
                    ReactiveEvent.Updated(Item(4, "four!")),
                    ReactiveEvent.Removed(identity = 2),
                ),
            ),
        )
        runCurrent()

        val page1 = paginator.cache.getStateOf(1)!!
        val page2 = paginator.cache.getStateOf(2)!!
        // Page 1 starts as [1,2,3], gets [1!,3] after remove id=2 and rebalance pulls
        // id=4 from page 2 → [1!,3,4!] (note: page2 update of id=4 already applied).
        assertEquals(listOf(1, 3, 4), page1.data.map { it.id })
        assertEquals("one!", page1.data[0].label)
        assertEquals("four!", page1.data[2].label)
        assertEquals(listOf(5, 6), page2.data.map { it.id })

        job.cancel()
    }

    @Test
    fun `initial sync RefreshAll reconciles cached pages on subscribe`() = runTest {
        var reloadVersion = 0
        val paginator = MutablePaginator<Item> { page ->
            val base = (page - 1) * 3
            val ver = reloadVersion
            LoadResult(MutableList(3) { i ->
                Item(
                    id = base + i + 1,
                    label = "v$ver-${base + i + 1}"
                )
            })
        }
        paginator.core.resize(capacity = 3, resize = false, silently = true)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        // Source-of-truth changed while paginator wasn't subscribed.
        reloadVersion = 1

        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.RefreshAll,
        )
        runCurrent()

        assertEquals("v1-1", paginator.cache.getStateOf(1)!!.data.first().label)
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
        source.emit(ReactiveEvent.Updated(Item(1, "ghost-update")))
        runCurrent()

        val page1 = paginator.cache.getStateOf(1)!!
        // Original label on page 1 item 1 is "v0-1", not "ghost-update".
        assertEquals("v0-1", page1.data.first().label)
        assertTrue(job.isCancelled)
        assertNull(page1.data.firstOrNull { it.label == "ghost-update" })
    }

    @Test
    fun `Moved event removes from old location and inserts at new position atomically`() = runTest {
        val paginator = populatedPaginator()
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
        )

        // Move id=5 (currently on page 2) to right after id=1 on page 1. The
        // item carries a new label too — Moved doubles as Updated.
        source.emit(
            ReactiveEvent.Moved(
                item = Item(id = 5, label = "moved!"),
                position = InsertPosition.AfterIdentity(identity = 1),
            ),
        )
        runCurrent()

        val page1 = paginator.cache.getStateOf(1)!!
        val page2 = paginator.cache.getStateOf(2)!!
        // Page 1 was [1,2,3]; after insert-after(1) it becomes [1,5,2] with 3
        // cascading to page 2. Page 2 was [4,5,6] — remove of id=5 dropped to
        // [4,6], then cascade adds 3 at front → [3,4,6].
        assertEquals(listOf(1, 5, 2), page1.data.map { it.id })
        assertEquals("moved!", page1.data[1].label)
        assertEquals(listOf(3, 4, 6), page2.data.map { it.id })

        job.cancel()
    }

    @Test
    fun `Moved for unknown identity falls through to UnknownItemPolicy`() = runTest {
        val paginator = populatedPaginator()
        val before: List<List<Int>> = paginator.cache.pages
            .map { paginator.cache.getStateOf(it)!!.data.map { i -> i.id } }
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
            unknownItem = UnknownItemPolicy.Drop,
        )

        source.emit(
            ReactiveEvent.Moved(
                item = Item(id = 9999, label = "ghost"),
                position = InsertPosition.Head,
            ),
        )
        runCurrent()

        val after: List<List<Int>> = paginator.cache.pages
            .map { paginator.cache.getStateOf(it)!!.data.map { i -> i.id } }
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
        source.emit(ReactiveEvent.Updated(Item(id = 1, label = "after-resub")))
        runCurrent()

        assertEquals("after-resub", paginator.cache.getStateOf(1)!!.data.first().label)
        assertNotEquals(first, second)

        second.cancel()
    }

    // =========================================================================
    // initPageState: overflow-page factory for reactive inserts
    //
    // The same default-factory-or-null mechanism the CRUD family exposes is
    // threaded through observe(): an insert that overflows the last cached page
    // creates a trailing page by default, accepts a custom factory, or drops
    // the overflow when the caller passes initPageState = null.
    // =========================================================================

    private class LabeledSuccessPage(page: Int, data: List<Item>) :
        OffsetPageState.Success<Item>(page, data)

    @Test
    fun `Inserted Tail overflow creates a trailing page by default (item not dropped)`() = runTest {
        val paginator = populatedPaginator() // pages 1=[1,2,3], 2=[4,5,6], capacity 3 (both full)
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
        )

        source.emit(ReactiveEvent.Inserted(Item(7, "tail"), InsertPosition.Tail))
        runCurrent()

        // 7 appended to the full last page overflows into a freshly created page 3.
        val page3 = paginator.cache.getStateOf(3)
        assertNotNull(page3)
        assertTrue(page3 is OffsetPageState.Success)
        assertEquals(listOf(7), page3.data.map { it.id })

        job.cancel()
    }

    @Test
    fun `Inserted Tail overflow is dropped when initPageState is null (opt-out)`() = runTest {
        val paginator = populatedPaginator()
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
            initPageState = null, // opt out: no trailing page is created
        )

        source.emit(ReactiveEvent.Inserted(Item(7, "tail"), InsertPosition.Tail))
        runCurrent()

        // No page 3 created; the last page is unchanged and the overflow item is dropped.
        assertNull(paginator.cache.getStateOf(3))
        assertEquals(listOf(4, 5, 6), paginator.cache.getStateOf(2)!!.data.map { it.id })

        job.cancel()
    }

    @Test
    fun `Inserted Tail overflow uses a custom initPageState factory (subclass preserved)`() = runTest {
        val paginator = populatedPaginator()
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
            initPageState = { page, data -> LabeledSuccessPage(page, data) },
        )

        source.emit(ReactiveEvent.Inserted(Item(7, "tail"), InsertPosition.Tail))
        runCurrent()

        val page3 = paginator.cache.getStateOf(3)
        assertTrue(
            page3 is LabeledSuccessPage,
            "overflow page should be the custom subclass, was ${page3?.let { it::class.simpleName }}",
        )
        assertEquals(listOf(7), page3.data.map { it.id })

        job.cancel()
    }

    @Test
    fun `Inserted Head overflow honors the default factory (threaded to prependElement)`() = runTest {
        val paginator = populatedPaginator()
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
        )

        // Head insert pushes the tail of the cache forward: 0,1,2 | 3,4,5 | 6.
        source.emit(ReactiveEvent.Inserted(Item(0, "head"), InsertPosition.Head))
        runCurrent()

        assertEquals(listOf(0, 1, 2), paginator.cache.getStateOf(1)!!.data.map { it.id })
        assertEquals(listOf(3, 4, 5), paginator.cache.getStateOf(2)!!.data.map { it.id })
        // The cascade reaches the end: id 6 lands in a created page 3 instead of being dropped.
        val page3 = paginator.cache.getStateOf(3)
        assertNotNull(page3)
        assertEquals(listOf(6), page3.data.map { it.id })

        job.cancel()
    }

    @Test
    fun `Inserted AfterIdentity overflow is dropped when initPageState is null (opt-out)`() = runTest {
        val paginator = populatedPaginator()
        val source = FakeReactiveCache()
        val job = paginator.observe(
            scope = this,
            source = source,
            initialSync = InitialSyncPolicy.None,
            initPageState = null,
        )

        // Insert after id 6 (the last element of the last full page) → overflow at the very end.
        source.emit(
            ReactiveEvent.Inserted(Item(7, "x"), InsertPosition.AfterIdentity(identity = 6)),
        )
        runCurrent()

        // Null opt-out: the overflow has nowhere to go and is dropped; no page 3 is created.
        assertNull(paginator.cache.getStateOf(3))
        assertEquals(listOf(4, 5, 6), paginator.cache.getStateOf(2)!!.data.map { it.id })

        job.cancel()
    }
}

