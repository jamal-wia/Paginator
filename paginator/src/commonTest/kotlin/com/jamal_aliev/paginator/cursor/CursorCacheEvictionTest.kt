package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cache.CursorInMemoryPagingCache
import com.jamal_aliev.paginator.cache.eviction.CursorContextWindowPagingCache
import com.jamal_aliev.paginator.cache.eviction.CursorMostRecentPagingCache
import com.jamal_aliev.paginator.cache.eviction.CursorQueuedPagingCache
import com.jamal_aliev.paginator.cache.eviction.CursorTimeLimitedPagingCache
import com.jamal_aliev.paginator.extension.plus
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class CursorCacheEvictionTest {

    private fun successPage(id: Int, vararg items: String): SuccessPage<String> =
        SuccessPage(page = id, data = items.toMutableList())

    private fun bookmarkAt(i: Int, total: Int): CursorBookmark =
        CursorBookmark(
            prev = if (i == 0) null else "p${i - 1}",
            self = "p$i",
            next = if (i == total - 1) null else "p${i + 1}",
        )

    // ── LRU ─────────────────────────────────────────────────────────────────

    @Test
    fun lru_evicts_least_recently_used_when_capacity_exceeded() {
        val c = CursorMostRecentPagingCache<String>(
            cache = CursorInMemoryPagingCache(),
            maxSize = 2,
            protectContextWindow = false,
        )
        c.setState(bookmarkAt(0, 5), successPage(1, "a"))
        c.setState(bookmarkAt(1, 5), successPage(2, "b"))
        // "touch" p0 so p1 is the stalest
        c.getStateOf("p0")
        c.setState(bookmarkAt(2, 5), successPage(3, "c"))

        assertNotNull(c.getStateOf("p0"), "p0 should still be in the cache (most recently used)")
        assertNull(c.getStateOf("p1"), "p1 should have been evicted")
        assertNotNull(c.getStateOf("p2"))
        assertEquals(2, c.size)
    }

    @Test
    fun lru_protects_context_window() {
        val c = CursorMostRecentPagingCache<String>(
            cache = CursorInMemoryPagingCache(),
            maxSize = 2,
            protectContextWindow = true,
        )
        val p0 = bookmarkAt(0, 5)
        val p1 = bookmarkAt(1, 5)
        c.setState(p0, successPage(1, "a"))
        c.setState(p1, successPage(2, "b"))

        // Mark p0..p1 as the context window so they become protected.
        c.startContextCursor = p0
        c.endContextCursor = p1
        c.setState(bookmarkAt(2, 5), successPage(3, "c"))

        assertNotNull(c.getStateOf("p0"))
        assertNotNull(c.getStateOf("p1"))
        assertNotNull(c.getStateOf("p2"))
        // maxSize was exceeded but all candidates were protected — size > maxSize is OK.
        assertEquals(3, c.size)
    }

    @Test
    fun lru_just_added_is_not_immediately_evicted() {
        val c = CursorMostRecentPagingCache<String>(
            cache = CursorInMemoryPagingCache(),
            maxSize = 1,
            protectContextWindow = false,
        )
        c.setState(bookmarkAt(0, 3), successPage(1, "a"))
        c.setState(bookmarkAt(1, 3), successPage(2, "b"))
        assertNull(c.getStateOf("p0"))
        assertNotNull(c.getStateOf("p1"))
    }

    // ── FIFO ────────────────────────────────────────────────────────────────

    @Test
    fun fifo_evicts_oldest_regardless_of_reads() {
        val c = CursorQueuedPagingCache<String>(
            cache = CursorInMemoryPagingCache(),
            maxSize = 2,
            protectContextWindow = false,
        )
        c.setState(bookmarkAt(0, 5), successPage(1, "a"))
        c.setState(bookmarkAt(1, 5), successPage(2, "b"))
        // Even after touching p0, it remains the oldest — FIFO doesn't consider reads.
        c.getStateOf("p0")
        c.setState(bookmarkAt(2, 5), successPage(3, "c"))

        assertNull(c.getStateOf("p0"), "p0 should have been evicted (oldest)")
        assertNotNull(c.getStateOf("p1"))
        assertNotNull(c.getStateOf("p2"))
    }

    @Test
    fun fifo_replacing_self_does_not_re_enter_queue() {
        val c = CursorQueuedPagingCache<String>(
            cache = CursorInMemoryPagingCache(),
            maxSize = 2,
            protectContextWindow = false,
        )
        c.setState(bookmarkAt(0, 3), successPage(1, "a"))
        c.setState(bookmarkAt(1, 3), successPage(2, "b"))
        // Replacing p0 should NOT move it to the tail of the insertion order — it stays oldest.
        c.setState(bookmarkAt(0, 3), successPage(99, "a-replaced"))
        c.setState(bookmarkAt(2, 3), successPage(3, "c"))

        assertNull(
            c.getStateOf("p0"),
            "p0 should still be oldest in FIFO semantics even after replacement"
        )
        assertNotNull(c.getStateOf("p1"))
        assertNotNull(c.getStateOf("p2"))
    }

    // ── Sliding window ──────────────────────────────────────────────────────

    @Test
    fun slidingWindow_evicts_everything_outside_context() {
        val c = CursorContextWindowPagingCache<String>(
            cache = CursorInMemoryPagingCache(),
            margin = 0,
        )
        val bookmarks = (0..4).map { bookmarkAt(it, 5) }
        bookmarks.forEach {
            c.setState(
                it,
                successPage(it.self.toString().last().digitToInt(), "x")
            )
        }

        // Set the window over p2 only and then trigger eviction by a further setState.
        c.startContextCursor = bookmarks[2]
        c.endContextCursor = bookmarks[2]
        c.setState(bookmarkAt(2, 5), successPage(99, "x-touched"))

        assertNull(c.getStateOf("p0"))
        assertNull(c.getStateOf("p1"))
        assertNotNull(c.getStateOf("p2"))
        assertNull(c.getStateOf("p3"))
        assertNull(c.getStateOf("p4"))
    }

    @Test
    fun slidingWindow_respects_margin() {
        val c = CursorContextWindowPagingCache<String>(
            cache = CursorInMemoryPagingCache(),
            margin = 1,
        )
        val bookmarks = (0..4).map { bookmarkAt(it, 5) }
        bookmarks.forEach { c.setState(it, successPage(1, "x")) }

        // Put the window over p2 only — margin 1 should keep p1 and p3 too.
        c.startContextCursor = bookmarks[2]
        c.endContextCursor = bookmarks[2]
        c.setState(bookmarkAt(2, 5), successPage(99, "y"))

        assertNull(c.getStateOf("p0"))
        assertNotNull(c.getStateOf("p1"))
        assertNotNull(c.getStateOf("p2"))
        assertNotNull(c.getStateOf("p3"))
        assertNull(c.getStateOf("p4"))
    }

    // ── TTL ─────────────────────────────────────────────────────────────────

    @Test
    fun ttl_evicts_expired_pages_on_next_set() {
        val time = TestTimeSource()
        val c = CursorTimeLimitedPagingCache<String>(
            cache = CursorInMemoryPagingCache(),
            ttl = 100.milliseconds,
            protectContextWindow = false,
            timeSource = time,
        )
        c.setState(bookmarkAt(0, 3), successPage(1, "a"))
        time += 250.milliseconds
        // A new setState triggers eviction of the expired entry.
        c.setState(bookmarkAt(1, 3), successPage(2, "b"))

        assertNull(c.getStateOf("p0"), "p0 should have expired and been evicted")
        assertNotNull(c.getStateOf("p1"))
    }

    @Test
    fun ttl_refreshOnAccess_keeps_entry_alive() {
        val time = TestTimeSource()
        val c = CursorTimeLimitedPagingCache<String>(
            cache = CursorInMemoryPagingCache(),
            ttl = 100.milliseconds,
            refreshOnAccess = true,
            protectContextWindow = false,
            timeSource = time,
        )
        c.setState(bookmarkAt(0, 3), successPage(1, "a"))
        time += 80.milliseconds
        c.getStateOf("p0") // refreshes the timestamp
        time += 80.milliseconds // still < 100ms since refresh
        c.setState(bookmarkAt(1, 3), successPage(2, "b"))

        assertNotNull(c.getStateOf("p0"))
        assertNotNull(c.getStateOf("p1"))
    }

    // ── Composition ─────────────────────────────────────────────────────────

    @Test
    fun plus_operator_composes_strategies_left_to_right() {
        val outer = CursorMostRecentPagingCache<String>(
            cache = CursorInMemoryPagingCache(),
            maxSize = 3,
            protectContextWindow = false,
        )
        val inner = CursorQueuedPagingCache<String>(
            cache = CursorInMemoryPagingCache(),
            maxSize = 10,
            protectContextWindow = false,
        )
        val composed = outer + inner
        repeat(5) {
            composed.setState(bookmarkAt(it, 5), successPage(it + 1, "x"))
        }
        // Outer LRU should trim to 3.
        assertTrue(composed.size <= 3, "outer LRU must enforce maxSize=3, got ${composed.size}")
    }

    @Test
    fun sliding_window_tolerates_missing_context_cursors() {
        val c = CursorContextWindowPagingCache<String>(
            cache = CursorInMemoryPagingCache(),
            margin = 0,
        )
        // No context cursors set — eviction should be a no-op.
        c.setState(bookmarkAt(0, 3), successPage(1, "a"))
        c.setState(bookmarkAt(1, 3), successPage(2, "b"))
        c.setState(bookmarkAt(2, 3), successPage(3, "c"))
        assertFalse(c.isStarted)
        assertEquals(3, c.size)
    }
}
