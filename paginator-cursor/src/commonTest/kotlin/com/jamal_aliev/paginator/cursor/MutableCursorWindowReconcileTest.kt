package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.cursor.MutableCursorPaginator.CursorBookmarkFactory
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.cache.CursorPagingCache
import com.jamal_aliev.paginator.cursor.cache.eviction.CursorQueuedPagingCache
import com.jamal_aliev.paginator.cursor.cache.eviction.CursorTimeLimitedPagingCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * CRUD operations of [MutableCursorPaginator] write pages, and a write can make an eviction
 * strategy without `protectContextWindow` drop a page of the context window. The window must
 * follow (`CursorPagingCore.reconcileContextWindow`), also for `silently` calls, otherwise the
 * published snapshot skips the change or is cut at a hole.
 */
class MutableCursorWindowReconcileTest {

    private fun cb(self: String) = CursorBookmark<String>(prev = null, self = self, next = null)

    private val mintTail = CursorBookmarkFactory<String> { index, previous ->
        CursorBookmark(prev = previous.self, self = "tail$index", next = null)
    }

    private fun newPaginator(cache: CursorPagingCache<String, String>): MutableCursorPaginator<String, String> {
        val backend = FakeCursorBackend(FakeCursorBackend.defaultPages(pageCount = 8, capacity = 3))
        val core = CursorPagingCore<String, String>(cache = cache, initialCapacity = 3)
        return MutableCursorPaginator(core = core) { cursor -> backend.loadResult(cursor) }
    }

    private suspend fun MutableCursorPaginator<String, String>.snapshotSelves(): List<String> =
        core.snapshot.first().map { it.bookmark.self }

    private fun MutableCursorPaginator<String, String>.window(): Pair<String?, String?> =
        core.startContextCursor?.self to core.endContextCursor?.self

    private fun MutableCursorPaginator<String, String>.cachedSelves(): Set<String> =
        cache.cursors.map { it.self }.toSet()

    /** Every page on the chain from the window start to the window end is cached. */
    private fun MutableCursorPaginator<String, String>.assertWindowIsCached() {
        assertTrue(core.isStarted, "paginator must be started")
        val end = core.endContextCursor!!
        var current: CursorBookmark<String>? = core.startContextCursor!!
        while (current != null) {
            assertNotNull(core.cache.peekStateOf(current.self), "window page ${current.self} must be cached")
            if (current.self == end.self) return
            current = core.cache.walkForward(current)
        }
        error("the chain from the window start does not reach ${end.self}")
    }

    /**
     * `CursorQueuedPagingCache(maxSize = 3)`: pages are loaded in the order `p1, p0, p2`, so the
     * middle page `p1` is the oldest one and the window is `p0..p2`.
     */
    private suspend fun queuedWithOldestMiddlePage(): MutableCursorPaginator<String, String> {
        val paginator = newPaginator(CursorQueuedPagingCache(maxSize = 3, protectContextWindow = false))
        paginator.jump(cb("p1"))
        paginator.goPreviousPage()
        paginator.goNextPage()
        assertEquals("p0" to "p2", paginator.window())
        return paginator
    }

    private class TtlSetup(val paginator: MutableCursorPaginator<String, String>, val expireOldest: () -> Unit)

    /**
     * `CursorTimeLimitedPagingCache(1000 ms)`: pages are loaded in the order `from`, then the next
     * two along the chain, 600 ms after the first; the window is `p0..p2`. After `expireOldest`
     * the first loaded page is expired, the other two are not.
     */
    private suspend fun ttlSetup(forward: Boolean): TtlSetup {
        val timeSource = TestTimeSource()
        val paginator = newPaginator(
            CursorTimeLimitedPagingCache(ttl = 1000.milliseconds, protectContextWindow = false, timeSource = timeSource)
        )
        paginator.jump(cb(if (forward) "p0" else "p2"))
        timeSource += 600.milliseconds
        repeat(2) { if (forward) paginator.goNextPage() else paginator.goPreviousPage() }
        assertEquals("p0" to "p2", paginator.window())
        return TtlSetup(paginator) { timeSource += 500.milliseconds }
    }

    @Test
    fun `addAllElements overflow that evicts a middle page narrows the window silently`() = runTest {
        val paginator = queuedWithOldestMiddlePage()

        // p2 is full: the overflow creates tail0 and the queue evicts the oldest page, p1
        paginator.addAllElements(
            elements = listOf("x"), targetSelf = "p2", index = 0, silently = true, bookmarkFactory = mintTail,
        )

        assertEquals(setOf("p0", "p2", "tail0"), paginator.cachedSelves())
        assertEquals("p2" to "p2", paginator.window())
        paginator.assertWindowIsCached()
    }

    @Test
    fun `addAllElements overflow that evicts a middle page publishes the narrowed window`() = runTest {
        val paginator = queuedWithOldestMiddlePage()

        paginator.addAllElements(
            elements = listOf("x"), targetSelf = "p2", index = 0, bookmarkFactory = mintTail,
        )

        assertEquals("p2" to "p2", paginator.window())
        // tail0 holds only the overflow element, so it is not filled and is shown after the window
        assertEquals(listOf("p2", "tail0"), paginator.snapshotSelves())
        paginator.assertWindowIsCached()
    }

    @Test
    fun `setElement that makes the strategy evict the window start narrows the window`() = runTest {
        val setup = ttlSetup(forward = true)
        val paginator = setup.paginator
        setup.expireOldest()

        // the write expires p0 away; p1 and p2 stay
        paginator.setElement(element = "x", self = "p2", index = 0, silently = true)

        assertEquals(setOf("p1", "p2"), paginator.cachedSelves())
        assertEquals("p1" to "p2", paginator.window())
        paginator.assertWindowIsCached()
    }

    @Test
    fun `removeElement that makes the strategy evict the window start publishes the narrowed window`() = runTest {
        val setup = ttlSetup(forward = true)
        val paginator = setup.paginator
        setup.expireOldest()

        paginator.removeElement(self = "p2", index = 0)

        assertEquals(setOf("p1", "p2"), paginator.cachedSelves())
        assertEquals("p1" to "p2", paginator.window())
        assertEquals(listOf("p1", "p2"), paginator.snapshotSelves())
        paginator.assertWindowIsCached()
    }

    @Test
    fun `removeState whose relinking makes the strategy evict a window page keeps the window whole`() = runTest {
        val setup = ttlSetup(forward = false)
        val paginator = setup.paginator
        setup.expireOldest()

        // p0 and p1 are fresh, p2 is expired: relinking p1 after the removal of p0 expires p2 away
        paginator.removeState(selfToRemove = "p0")

        assertEquals(setOf("p1"), paginator.cachedSelves())
        assertEquals("p1" to "p1", paginator.window())
        assertEquals(listOf("p1"), paginator.snapshotSelves())
        paginator.assertWindowIsCached()
    }
}
