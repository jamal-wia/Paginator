package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.BookmarkInt
import com.jamal_aliev.paginator.load.LoadResult
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotAndFlowTest {

    @Test
    fun `snapshot emits pages in context range`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.jump(BookmarkInt(1))
        paginator.goNextPage()
        paginator.goNextPage()

        val snapshotValue = paginator.core.snapshot.first()
        assertTrue(snapshotValue.isNotEmpty())
        // Should contain pages in context range
        val pages = snapshotValue.map { it.page }
        assertTrue(1 in pages)
    }

    @Test
    fun `scan returns pages in range`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 5, capacity = 3)
        val result = paginator.core.scan(2..4)
        assertEquals(3, result.size)
        assertEquals(listOf(2, 3, 4), result.map { it.page })
    }

    @Test
    fun `scan skips gaps in range`() = runTest {
        val paginator = MutablePaginator<String> { LoadResult(emptyList()) }
        paginator.core.resize(capacity = 1, resize = false, silently = true)
        paginator.cache.setState(SuccessPage(page = 1, data = mutableListOf("a")), silently = true)
        paginator.cache.setState(SuccessPage(page = 3, data = mutableListOf("c")), silently = true)
        paginator.cache.setState(SuccessPage(page = 5, data = mutableListOf("e")), silently = true)

        val result = paginator.core.scan(1..5)
        // scan uses continue (not break), so it collects all cached pages in range
        assertEquals(3, result.size)
        assertEquals(listOf(1, 3, 5), result.map { it.page })
    }

    @Test
    fun `asFlow enables cache flow`() {
        val paginator = createDeterministicPaginator()
        assertFalse(paginator.core.enableCacheFlow)

        paginator.core.asFlow()

        assertTrue(paginator.core.enableCacheFlow)
    }

    @Test
    fun `snapshot is not deduplicated when subsequent emissions are structurally equal`() =
        runTest {
            val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)

            val received = mutableListOf<List<com.jamal_aliev.paginator.page.PageState<String>>>()
            val job = paginator.core.snapshot
                .onEach { received += it }
                .launchIn(this)

            // Drain replay (initial emptyList emitted on construction).
            kotlinx.coroutines.yield()

            // Emit the same range three times, yielding between so the collector observes each.
            // This is representative of real navigation paths, where snapshot() is always called
            // from suspending functions with dispatcher yields in between.
            paginator.core.snapshot(1..3)
            kotlinx.coroutines.yield()
            paginator.core.snapshot(1..3)
            kotlinx.coroutines.yield()
            paginator.core.snapshot(1..3)
            kotlinx.coroutines.yield()

            job.cancel()

            // 1 replay (emptyList from init) + 3 explicit emits.
            assertEquals(4, received.size)
            assertTrue(received[0].isEmpty())
            assertEquals(received[1], received[2])
            assertEquals(received[2], received[3])
            assertEquals(listOf(1, 2, 3), received[1].map { it.page })
        }

    @Test
    fun `snapshot replays last value to late subscribers`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        paginator.core.snapshot(1..3)

        // Late subscriber should immediately get the last emitted snapshot via replay cache.
        val replayed = paginator.core.snapshot.first()
        assertEquals(listOf(1, 2, 3), replayed.map { it.page })
    }

    @Test
    fun `snapshotPageRange reflects last emission`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 5, capacity = 3)

        paginator.core.snapshot(2..4)
        assertEquals(2..4, paginator.core.snapshotPageRange())

        paginator.core.snapshot(1..3)
        assertEquals(1..3, paginator.core.snapshotPageRange())
    }

    @Test
    fun `snapshotPageRange is null after release`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        paginator.core.snapshot(1..3)
        assertEquals(1..3, paginator.core.snapshotPageRange())

        paginator.core.release()
        assertNull(paginator.core.snapshotPageRange())
    }

    @Test
    fun `release emits empty snapshot to subscribers`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        paginator.core.snapshot(1..3)

        val received = mutableListOf<List<com.jamal_aliev.paginator.page.PageState<String>>>()
        val job = paginator.core.snapshot
            .onEach { received += it }
            .launchIn(this)
        kotlinx.coroutines.yield()

        paginator.core.release()
        kotlinx.coroutines.yield()

        job.cancel()

        // Replay (last non-empty) + empty list from release.
        assertTrue(received.size >= 2)
        assertTrue(received.last().isEmpty())
    }

    @Test
    fun `asFlow emits cache updates`() = runTest {
        val paginator = MutablePaginator<String> { LoadResult(emptyList()) }
        paginator.core.resize(capacity = 1, resize = false, silently = true)
        val flow = paginator.core.asFlow()

        // Initial emission
        val initial = flow.first()
        assertTrue(initial.isEmpty())

        // Add state and trigger flow
        paginator.cache.setState(SuccessPage(page = 1, data = mutableListOf("a")))
        paginator.core.repeatCacheFlow()
        val updated = flow.first()
        assertEquals(1, updated.size)
        assertEquals(1, updated.first().page)
    }
}
