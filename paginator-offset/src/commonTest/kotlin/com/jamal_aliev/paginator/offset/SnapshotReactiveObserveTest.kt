package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.core.cache.reactive.InitialSyncPolicy
import com.jamal_aliev.paginator.core.cache.reactive.ReorderPolicy
import com.jamal_aliev.paginator.core.cache.reactive.snapshotReactiveCache
import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.extension.observe
import com.jamal_aliev.paginator.offset.load.LoadResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end coverage of [snapshotReactiveCache] wired to a real
 * [MutablePaginator] via `observe()`: a snapshot stream is diffed into
 * [com.jamal_aliev.paginator.core.cache.reactive.ReactiveEvent]s and applied as
 * CRUD on the L1 cache. The diff logic itself is unit-tested in the core
 * module's `SnapshotReactiveCacheTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotReactiveObserveTest {

    private data class Item(val id: Int, val label: String)

    /** Two pages of capacity 3: page 1 → ids 1,2,3; page 2 → ids 4,5,6. */
    private suspend fun populatedPaginator(): MutablePaginator<Item> {
        val paginator = MutablePaginator<Item> { page ->
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

    /** The snapshot matching the loaded window, used as the diff baseline. */
    private fun baseline(): List<Item> = List(6) { i -> Item(id = i + 1, label = "v0-${i + 1}") }

    private fun pageIds(paginator: MutablePaginator<Item>, page: Int): List<Int> =
        paginator.cache.getStateOf(page)!!.data.map { it.id }

    @Test
    fun `content edit in a snapshot patches the matching cached item`() = runTest {
        val paginator = populatedPaginator()
        val snapshots = MutableSharedFlow<List<Item>>(replay = 64)
        val source =
            snapshotReactiveCache(snapshots = snapshots.asSharedFlow(), identity = { it.id })
        val job =
            paginator.observe(scope = this, source = source, initialSync = InitialSyncPolicy.None)

        snapshots.emit(baseline()); runCurrent()
        snapshots.emit(baseline().map { if (it.id == 5) it.copy(label = "patched") else it }); runCurrent()

        assertEquals("patched", paginator.cache.getStateOf(2)!!.data.first { it.id == 5 }.label)
        job.cancel()
    }

    @Test
    fun `removed item in a snapshot deletes and rebalances`() = runTest {
        val paginator = populatedPaginator()
        val snapshots = MutableSharedFlow<List<Item>>(replay = 64)
        val source =
            snapshotReactiveCache(snapshots = snapshots.asSharedFlow(), identity = { it.id })
        val job =
            paginator.observe(scope = this, source = source, initialSync = InitialSyncPolicy.None)

        snapshots.emit(baseline()); runCurrent()
        snapshots.emit(baseline().filterNot { it.id == 2 }); runCurrent()

        // Removing id 2 pulls id 4 up: page 1 → 1,3,4 ; page 2 → 5,6.
        assertEquals(listOf(1, 3, 4), pageIds(paginator, 1))
        assertEquals(listOf(5, 6), pageIds(paginator, 2))
        job.cancel()
    }

    @Test
    fun `inserted item in a snapshot lands after its left neighbour`() = runTest {
        val paginator = populatedPaginator()
        val snapshots = MutableSharedFlow<List<Item>>(replay = 64)
        val source =
            snapshotReactiveCache(snapshots = snapshots.asSharedFlow(), identity = { it.id })
        val job =
            paginator.observe(scope = this, source = source, initialSync = InitialSyncPolicy.None)

        snapshots.emit(baseline()); runCurrent()
        val withInsert = baseline().toMutableList().apply { add(1, Item(99, "x")) } // after id 1
        snapshots.emit(withInsert); runCurrent()

        // 1, 99, 2 → id 3 overflows into page 2.
        assertEquals(listOf(1, 99, 2), pageIds(paginator, 1))
        job.cancel()
    }

    @Test
    fun `reorder under Moves policy reorders the cache`() = runTest {
        val paginator = populatedPaginator()
        val snapshots = MutableSharedFlow<List<Item>>(replay = 64)
        val source = snapshotReactiveCache(
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

        assertEquals(listOf(1, 3, 2), pageIds(paginator, 1))
        assertEquals(listOf(4, 5, 6), pageIds(paginator, 2))
        job.cancel()
    }
}
