package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.cache.InMemoryPagingCache
import com.jamal_aliev.paginator.offset.cache.PagingCache
import com.jamal_aliev.paginator.offset.cache.eviction.QueuedPagingCache
import com.jamal_aliev.paginator.offset.load.LoadResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `PagingCore.resize` rebuilds the cache with pages of the new capacity. The rebuilt cache can hold
 * fewer pages than the window covered, or an eviction strategy can drop some of them while they are
 * written; the window must follow, otherwise the snapshot goes silent on a missing boundary.
 */
class ResizeWindowReconcileTest {

    private suspend fun loadedPaginator(
        cache: PagingCache<String>,
        capacity: Int,
        pages: Int,
    ): MutablePaginator<String> {
        val paginator = MutablePaginator(core = PagingCore(cache = cache, initialCapacity = capacity)) { page: Int ->
            LoadResult(List(this.core.capacity) { "p${page}_item$it" })
        }
        paginator.jump(BookmarkInt(1))
        repeat(pages - 1) { paginator.goNextPage() }
        assertEquals(1, paginator.core.startContextPage)
        assertEquals(pages, paginator.core.endContextPage)
        return paginator
    }

    private fun MutablePaginator<String>.assertWindowIsCached() {
        assertTrue(core.isStarted, "paginator must be started")
        for (page in core.startContextPage..core.endContextPage) {
            assertNotNull(core.cache.peekStateOf(page), "window page $page must be cached")
        }
    }

    @Test
    fun `resize that makes the strategy evict the whole window re-anchors the window and publishes it`() = runTest {
        val paginator = loadedPaginator(
            cache = QueuedPagingCache(maxSize = 4, protectContextWindow = false),
            capacity = 5,
            pages = 3,
        )

        // 15 items become 8 pages of 2; the queue keeps the last four, 5..8, so the window 1..3 is gone
        paginator.core.resize(capacity = 2, resize = true)

        assertEquals(listOf(5, 6, 7, 8), paginator.cache.pages)
        paginator.assertWindowIsCached()
        val snapshotPages = paginator.core.snapshot.first().map { it.page }
        assertTrue(snapshotPages.isNotEmpty(), "the resize must publish a snapshot")
        for (page in paginator.core.startContextPage..paginator.core.endContextPage) {
            assertTrue(page in snapshotPages, "snapshot must cover window page $page: $snapshotPages")
        }
    }

    @Test
    fun `resize into bigger pages narrows the window to the pages that exist and publishes it`() = runTest {
        val paginator = loadedPaginator(cache = InMemoryPagingCache(), capacity = 3, pages = 4)

        // 12 items become 2 pages: 10 and 2 items
        paginator.core.resize(capacity = 10, resize = true)

        assertEquals(listOf(1, 2), paginator.cache.pages)
        assertEquals(1, paginator.core.startContextPage)
        assertEquals(2, paginator.core.endContextPage)
        assertEquals(listOf(1, 2), paginator.core.snapshot.first().map { it.page })
    }
}
