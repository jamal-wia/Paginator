package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.core.cache.reactive.InitialSyncPolicy
import com.jamal_aliev.paginator.core.cache.reactive.ReorderPolicy
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.cache.reactive.cursorSnapshotReactiveCache
import com.jamal_aliev.paginator.cursor.extension.observe
import com.jamal_aliev.paginator.cursor.load.CursorLoadResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end coverage of [cursorSnapshotReactiveCache] wired to a real
 * [MutableCursorPaginator] via `observe()`: a snapshot stream is diffed into
 * [com.jamal_aliev.paginator.cursor.cache.reactive.CursorReactiveEvent]s and
 * applied as CRUD on the L1 cache. The diff logic itself is unit-tested in
 * `CursorSnapshotReactiveCacheTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CursorSnapshotReactiveObserveTest {

    private data class Item(val id: Int, val label: String)

    /** Linked, paged backend: page `i` `self` = "p$i", ids ∈ [i*3+1 .. i*3+3]. */
    private class IntBackend(val pageCount: Int = 3) {
        private fun pageOf(self: String): Int = self.removePrefix("p").toInt()
        private fun pageData(page: Int): List<Item> {
            val base = page * 3
            return List(3) { i -> Item(id = base + i + 1, label = "v0-${base + i + 1}") }
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
                bookmark = CursorBookmark(
                    prev = if (page == 0) null else "p${page - 1}",
                    self = targetSelf,
                    next = if (page == pageCount - 1) null else "p${page + 1}",
                ),
            )
        }
    }

    /** Two pages of capacity 3: p0 → ids 1,2,3; p1 → ids 4,5,6. */
    private suspend fun populatedPaginator(): MutableCursorPaginator<String, Item> {
        val backend = IntBackend(pageCount = 3)
        val core = CursorPagingCore<String, Item>(initialCapacity = 3)
        val paginator =
            MutableCursorPaginator<String, Item>(core = core) { cursor -> backend.load(cursor) }
        paginator.jump(
            bookmark = CursorBookmark(prev = null, self = "p0", next = null),
            silentlyLoading = true,
            silentlyResult = true,
        )
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        return paginator
    }

    /** The snapshot matching the loaded window, used as the diff baseline. */
    private fun baseline(): List<Item> = List(6) { i -> Item(id = i + 1, label = "v0-${i + 1}") }

    private fun pageIds(paginator: MutableCursorPaginator<String, Item>, self: String): List<Int> =
        paginator.cache.getStateOf(self)!!.data.map { it.id }

    @Test
    fun `content edit in a snapshot patches the matching cached item`() = runTest {
        val paginator = populatedPaginator()
        val snapshots = MutableSharedFlow<List<Item>>(replay = 64)
        val source =
            cursorSnapshotReactiveCache(snapshots = snapshots.asSharedFlow(), identity = { it.id })
        val job =
            paginator.observe(scope = this, source = source, initialSync = InitialSyncPolicy.None)

        snapshots.emit(baseline()); runCurrent()
        snapshots.emit(baseline().map { if (it.id == 5) it.copy(label = "patched") else it }); runCurrent()

        assertEquals("patched", paginator.cache.getStateOf("p1")!!.data.first { it.id == 5 }.label)
        job.cancel()
    }

    @Test
    fun `removed item in a snapshot deletes and rebalances`() = runTest {
        val paginator = populatedPaginator()
        val snapshots = MutableSharedFlow<List<Item>>(replay = 64)
        val source =
            cursorSnapshotReactiveCache(snapshots = snapshots.asSharedFlow(), identity = { it.id })
        val job =
            paginator.observe(scope = this, source = source, initialSync = InitialSyncPolicy.None)

        snapshots.emit(baseline()); runCurrent()
        snapshots.emit(baseline().filterNot { it.id == 2 }); runCurrent()

        // Removing id 2 pulls id 4 up: p0 → 1,3,4 ; p1 → 5,6.
        assertEquals(listOf(1, 3, 4), pageIds(paginator, "p0"))
        assertEquals(listOf(5, 6), pageIds(paginator, "p1"))
        job.cancel()
    }

    @Test
    fun `inserted item in a snapshot lands after its left neighbour`() = runTest {
        val paginator = populatedPaginator()
        val snapshots = MutableSharedFlow<List<Item>>(replay = 64)
        val source =
            cursorSnapshotReactiveCache(snapshots = snapshots.asSharedFlow(), identity = { it.id })
        val job =
            paginator.observe(scope = this, source = source, initialSync = InitialSyncPolicy.None)

        snapshots.emit(baseline()); runCurrent()
        val withInsert = baseline().toMutableList().apply { add(1, Item(99, "x")) } // after id 1
        snapshots.emit(withInsert); runCurrent()

        // 1, 99, 2 → id 3 overflows into p1.
        assertEquals(listOf(1, 99, 2), pageIds(paginator, "p0"))
        job.cancel()
    }

    @Test
    fun `reorder under Moves policy reorders the cache`() = runTest {
        val paginator = populatedPaginator()
        val snapshots = MutableSharedFlow<List<Item>>(replay = 64)
        val source = cursorSnapshotReactiveCache(
            snapshots = snapshots.asSharedFlow(),
            identity = { it.id },
            reorderPolicy = ReorderPolicy.Moves,
        )
        val job =
            paginator.observe(scope = this, source = source, initialSync = InitialSyncPolicy.None)

        snapshots.emit(baseline()); runCurrent()
        // Swap ids 2 and 3 → target order 1,3,2,4,5,6.
        val swapped =
            baseline().toMutableList().apply { val t = this[1]; this[1] = this[2]; this[2] = t }
        snapshots.emit(swapped); runCurrent()

        assertEquals(listOf(1, 3, 2), pageIds(paginator, "p0"))
        assertEquals(listOf(4, 5, 6), pageIds(paginator, "p1"))
        job.cancel()
    }
}
