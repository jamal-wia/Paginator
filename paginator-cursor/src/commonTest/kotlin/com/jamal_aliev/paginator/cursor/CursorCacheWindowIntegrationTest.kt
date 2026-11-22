package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.cache.CursorPagingCache
import com.jamal_aliev.paginator.cursor.cache.eviction.CursorMostRecentPagingCache
import com.jamal_aliev.paginator.cursor.cache.eviction.CursorNearestPagingCache
import com.jamal_aliev.paginator.cursor.cache.eviction.CursorQueuedPagingCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Scenarios that need several fixes at once on the cursor side: a size limited cache, navigation in
 * both directions, the context window shrinking after an eviction, and the published snapshot.
 */
class CursorCacheWindowIntegrationTest {

    private fun createPaginator(cache: CursorPagingCache<String, String>): CursorPaginator<String, String> {
        val backend = FakeCursorBackend(FakeCursorBackend.defaultPages(pageCount = 8, capacity = 3))
        val core = CursorPagingCore<String, String>(cache = cache, initialCapacity = 3)
        return CursorPaginator<String, String>(core = core) { cursor -> backend.loadResult(cursor) }
    }

    private fun cb(self: String) = CursorBookmark<String>(prev = null, self = self, next = null)

    private suspend fun CursorPaginator<String, String>.snapshotSelves(): List<String> =
        core.snapshot.first().map { it.bookmark.self }

    /**
     * The context window invariant on a started paginator: both boundaries are cached, every page on
     * the chain from `startContextCursor` to `endContextCursor` is cached (no hole), and the last
     * published snapshot is not empty and holds exactly the window pages.
     */
    private suspend fun assertWindowConsistent(paginator: CursorPaginator<String, String>) {
        val core = paginator.core
        assertTrue(core.isStarted, "paginator must be started")
        val start = core.startContextCursor!!
        val end = core.endContextCursor!!
        val window = mutableListOf<String>()
        var current: CursorBookmark<String>? = start
        while (current != null) {
            assertNotNull(core.cache.peekStateOf(current.self), "window page ${current.self} must be cached")
            window += current.self
            if (current.self == end.self) break
            current = core.cache.walkForward(current)
        }
        assertEquals(end.self, window.last(), "the chain from the start boundary must reach the end boundary: $window")
        val snapshot = paginator.snapshotSelves()
        assertTrue(snapshot.isNotEmpty(), "the published snapshot must not be empty")
        assertEquals(window, snapshot, "the published snapshot must hold exactly the window pages")
    }

    /** Visible navigation p0..p2 then p3 under a limit of three pages. */
    private suspend fun assertFourthPageUnderLimitOfThree(cache: CursorPagingCache<String, String>) {
        val paginator = createPaginator(cache)
        paginator.jump(cb("p0"))
        paginator.goNextPage()
        paginator.goNextPage()
        assertWindowConsistent(paginator)

        paginator.goNextPage()

        assertTrue(paginator.cache.size <= 3, "limit exceeded: ${paginator.cache.cursors.map { it.self }}")
        assertNotNull(paginator.cache.peekStateOf("p3"))
        assertWindowConsistent(paginator)
    }

    /** Window p0..p2, forward to p1..p3, back to p0..p2: the window and the snapshot never get a hole. */
    private suspend fun assertDirectionChangeKeepsWindowWhole(
        cache: CursorPagingCache<String, String>,
    ): List<List<String>> {
        val paginator = createPaginator(cache)
        val snapshots = mutableListOf<List<String>>()
        paginator.jump(cb("p0"))
        paginator.goNextPage()
        paginator.goNextPage()
        assertWindowConsistent(paginator)
        snapshots += paginator.snapshotSelves()

        paginator.goNextPage()
        assertWindowConsistent(paginator)
        assertNotNull(paginator.cache.peekStateOf("p3"))
        snapshots += paginator.snapshotSelves()

        paginator.goPreviousPage()
        assertWindowConsistent(paginator)
        assertNotNull(paginator.cache.peekStateOf("p0"))
        snapshots += paginator.snapshotSelves()
        return snapshots
    }

    @Test
    fun fourth_page_under_a_limit_of_three_with_LRU_without_window_protection_keeps_window_and_snapshot_whole() =
        runTest {
            assertFourthPageUnderLimitOfThree(
                CursorMostRecentPagingCache(maxSize = 3, protectContextWindow = false)
            )
        }

    @Test
    fun fourth_page_under_a_limit_of_three_with_FIFO_without_window_protection_keeps_window_and_snapshot_whole() =
        runTest {
            assertFourthPageUnderLimitOfThree(
                CursorQueuedPagingCache(maxSize = 3, protectContextWindow = false)
            )
        }

    @Test
    fun fourth_page_under_a_limit_of_three_with_CursorNearestPagingCache_keeps_window_and_snapshot_whole() = runTest {
        val cache = CursorNearestPagingCache<String, String>(maxSize = 3)
        assertFourthPageUnderLimitOfThree(cache)
        assertEquals(setOf("p1", "p2", "p3"), cache.cursors.map { it.self }.toSet())
    }

    @Test
    fun direction_change_p0_p2_then_p1_p3_then_p0_p2_with_CursorNearestPagingCache_keeps_window_and_snapshot_whole() =
        runTest {
            val snapshots = assertDirectionChangeKeepsWindowWhole(CursorNearestPagingCache(maxSize = 3))

            assertEquals(
                listOf(listOf("p0", "p1", "p2"), listOf("p1", "p2", "p3"), listOf("p0", "p1", "p2")),
                snapshots,
            )
        }

    @Test
    fun direction_change_p0_p2_then_p1_p3_then_p0_p2_with_LRU_without_window_protection_keeps_window_and_snapshot_without_holes() =
        runTest {
            val snapshots = assertDirectionChangeKeepsWindowWhole(
                CursorMostRecentPagingCache(maxSize = 3, protectContextWindow = false)
            )
            // LRU evicts a page from the middle of the window; the window narrows around the loaded page (D-3)
            assertEquals(
                listOf(listOf("p0", "p1", "p2"), listOf("p1", "p2", "p3"), listOf("p0", "p1")),
                snapshots,
            )
        }
}
