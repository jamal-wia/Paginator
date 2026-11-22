package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.cache.PagingCache
import com.jamal_aliev.paginator.offset.cache.eviction.QueuedPagingCache
import com.jamal_aliev.paginator.offset.cache.eviction.TimeLimitedPagingCache
import com.jamal_aliev.paginator.offset.load.LoadResult
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * CRUD operations of [MutablePaginator] write pages, and a write can make an eviction strategy
 * without `protectContextWindow` drop a page of the context window. The window must follow
 * (`PagingCore.reconcileContextWindow`), also for `silently` calls, otherwise the published
 * snapshot skips the change or shows a hole.
 */
class MutableWindowReconcileTest {

    private val capacity = 3

    private fun newPaginator(cache: PagingCache<String>) =
        MutablePaginator(core = PagingCore(cache = cache, initialCapacity = capacity)) { page: Int ->
            LoadResult(List(this.core.capacity) { "p${page}_item$it" })
        }

    private suspend fun MutablePaginator<String>.snapshotPages(): List<Int> =
        core.snapshot.first().map { it.page }

    private fun MutablePaginator<String>.assertWindowIsCached() {
        assertTrue(core.isStarted, "paginator must be started")
        for (page in core.startContextPage..core.endContextPage) {
            assertNotNull(core.cache.peekStateOf(page), "window page $page must be cached")
        }
    }

    /**
     * `QueuedPagingCache(maxSize = 3)`: pages are loaded in the order 2, 1, 3, so the middle page 2
     * is the oldest one and the window is `1..3`.
     */
    private suspend fun queuedWithOldestMiddlePage(): MutablePaginator<String> {
        val paginator = newPaginator(QueuedPagingCache(maxSize = 3, protectContextWindow = false))
        paginator.jump(BookmarkInt(2))
        paginator.goPreviousPage()
        paginator.goNextPage()
        assertEquals(1, paginator.core.startContextPage)
        assertEquals(3, paginator.core.endContextPage)
        return paginator
    }

    /**
     * `TimeLimitedPagingCache(1000 ms)`: loaded in the order 3, 2, 1 with 600 ms between the first
     * and the other two; the window is `1..3`. After [expireOldest] page 3 is expired, 1 and 2 are
     * not.
     */
    private class TtlSetup(val paginator: MutablePaginator<String>, val expireOldest: () -> Unit)

    private suspend fun ttlWithExpiredLastPage(): TtlSetup = ttlSetup(loadFrom = 3, loadTo = 1)

    /** Like [ttlWithExpiredLastPage], but the pages are loaded `1, 2, 3` and page 1 is the expired one. */
    private suspend fun ttlWithExpiredFirstPage(): TtlSetup = ttlSetup(loadFrom = 1, loadTo = 3)

    private suspend fun ttlSetup(loadFrom: Int, loadTo: Int): TtlSetup {
        val timeSource = TestTimeSource()
        val paginator = newPaginator(
            TimeLimitedPagingCache(ttl = 1000.milliseconds, protectContextWindow = false, timeSource = timeSource)
        )
        paginator.jump(BookmarkInt(loadFrom))
        timeSource += 600.milliseconds
        repeat(abs(loadTo - loadFrom)) {
            if (loadTo > loadFrom) paginator.goNextPage() else paginator.goPreviousPage()
        }
        assertEquals(1, paginator.core.startContextPage)
        assertEquals(3, paginator.core.endContextPage)
        return TtlSetup(paginator) { timeSource += 500.milliseconds }
    }

    @Test
    fun `addAllElements overflow that evicts a middle page narrows the window silently`() = runTest {
        val paginator = queuedWithOldestMiddlePage()

        // page 3 is full: the overflow creates page 4 and the queue evicts the oldest page, 2
        paginator.addAllElements(elements = listOf("x"), targetPage = 3, index = 0, silently = true)

        assertEquals(listOf(1, 3, 4), paginator.cache.pages)
        assertEquals(3, paginator.core.startContextPage)
        assertEquals(3, paginator.core.endContextPage)
        paginator.assertWindowIsCached()
    }

    @Test
    fun `addAllElements overflow that evicts a middle page publishes the narrowed window`() = runTest {
        val paginator = queuedWithOldestMiddlePage()

        paginator.addAllElements(elements = listOf("x"), targetPage = 3, index = 0)

        assertEquals(3, paginator.core.startContextPage)
        assertEquals(3, paginator.core.endContextPage)
        // page 4 holds only the one overflow element, so it is not filled and is shown after the window
        assertEquals(listOf(3, 4), paginator.snapshotPages())
        paginator.assertWindowIsCached()
    }

    @Test
    fun `setElement that makes the strategy evict the window start narrows the window`() = runTest {
        val setup = ttlWithExpiredFirstPage()
        val paginator = setup.paginator
        setup.expireOldest()

        // the write expires page 1 away; pages 2 and 3 stay
        paginator.setElement(element = "x", page = 3, index = 0, silently = true)

        assertEquals(listOf(2, 3), paginator.cache.pages)
        assertEquals(2, paginator.core.startContextPage)
        assertEquals(3, paginator.core.endContextPage)
        paginator.assertWindowIsCached()
    }

    @Test
    fun `removeElement that makes the strategy evict the window start publishes the narrowed window`() = runTest {
        val setup = ttlWithExpiredFirstPage()
        val paginator = setup.paginator
        setup.expireOldest()

        paginator.removeElement(page = 3, index = 0)

        assertEquals(listOf(2, 3), paginator.cache.pages)
        assertEquals(2, paginator.core.startContextPage)
        assertEquals(3, paginator.core.endContextPage)
        assertEquals(listOf(2, 3), paginator.snapshotPages())
        paginator.assertWindowIsCached()
    }

    @Test
    fun `removeState whose re-labelling makes the strategy evict a window page keeps the window whole`() = runTest {
        val setup = ttlWithExpiredLastPage()
        val paginator = setup.paginator
        setup.expireOldest()

        // pages 1 and 2 are fresh, page 3 is expired. Removing page 1 re-labels 2 as 1 and the write
        // expires page 3 away before it can slide down; the window `1..2` would point at a hole
        paginator.removeState(pageToRemove = 1)

        assertEquals(listOf(1), paginator.cache.pages)
        assertEquals(1, paginator.core.startContextPage)
        assertEquals(1, paginator.core.endContextPage)
        assertEquals(listOf(1), paginator.snapshotPages())
        paginator.assertWindowIsCached()
    }

    /** `QueuedPagingCache(maxSize = 3)` without protection: pages `1..3` loaded in order, window `1..3`. */
    private suspend fun queuedWithThreePages(): MutablePaginator<String> {
        val paginator = newPaginator(QueuedPagingCache(maxSize = 3, protectContextWindow = false))
        paginator.jump(BookmarkInt(1))
        paginator.goNextPage()
        paginator.goNextPage()
        assertEquals(1, paginator.core.startContextPage)
        assertEquals(3, paginator.core.endContextPage)
        return paginator
    }

    @Test
    fun `plusAssign of a new page that evicts the window start narrows the window and publishes it`() = runTest {
        val paginator = queuedWithThreePages()

        paginator += OffsetPageState.Success(page = 4, data = MutableList(capacity) { "n$it" })

        assertEquals(listOf(2, 3, 4), paginator.cache.pages)
        assertEquals(2, paginator.core.startContextPage)
        assertEquals(3, paginator.core.endContextPage)
        // the snapshot covers the window and the cached page right behind it
        assertEquals(listOf(2, 3, 4), paginator.snapshotPages())
        paginator.assertWindowIsCached()
    }

    @Test
    fun `core setState of a new page that evicts the window start narrows the window silently`() = runTest {
        val paginator = queuedWithThreePages()

        paginator.core.setState(OffsetPageState.Success(page = 4, data = MutableList(capacity) { "n$it" }), silently = true)

        assertEquals(listOf(2, 3, 4), paginator.cache.pages)
        assertEquals(2, paginator.core.startContextPage)
        assertEquals(3, paginator.core.endContextPage)
        paginator.assertWindowIsCached()
    }
}
