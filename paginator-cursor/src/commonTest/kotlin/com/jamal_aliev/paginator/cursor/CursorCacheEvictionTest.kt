package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.core.cache.eviction.CacheEvictionListener
import com.jamal_aliev.paginator.cursor.page.CursorPageState

import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.cache.CursorInMemoryPagingCache
import com.jamal_aliev.paginator.cursor.cache.CursorPagingCache
import com.jamal_aliev.paginator.cursor.cache.eviction.CursorContextWindowPagingCache
import com.jamal_aliev.paginator.cursor.cache.eviction.CursorMostRecentPagingCache
import com.jamal_aliev.paginator.cursor.cache.eviction.CursorQueuedPagingCache
import com.jamal_aliev.paginator.cursor.cache.eviction.CursorTimeLimitedPagingCache
import com.jamal_aliev.paginator.cursor.extension.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class CursorCacheEvictionTest {

    private fun successPage(
        id: Int,
        vararg items: String
    ): CursorPageState.Success<String, String> =
        CursorPageState.Success(
            bookmark = CursorBookmark(null, "p$id", null),
            data = items.toMutableList()
        )

    private fun bookmarkAt(i: Int, total: Int): CursorBookmark<String> =
        CursorBookmark<String>(
            prev = if (i == 0) null else "p${i - 1}",
            self = "p$i",
            next = if (i == total - 1) null else "p${i + 1}",
        )

    // ── LRU ─────────────────────────────────────────────────────────────────

    @Test
    fun lru_evicts_least_recently_used_when_capacity_exceeded() {
        val c = CursorMostRecentPagingCache<String, String>(
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
        val c = CursorMostRecentPagingCache<String, String>(
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
        val c = CursorMostRecentPagingCache<String, String>(
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
        val c = CursorQueuedPagingCache<String, String>(
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
        val c = CursorQueuedPagingCache<String, String>(
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
        val c = CursorContextWindowPagingCache<String, String>(
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
        val c = CursorContextWindowPagingCache<String, String>(
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
        val c = CursorTimeLimitedPagingCache<String, String>(
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
        val c = CursorTimeLimitedPagingCache<String, String>(
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
        val outer = CursorMostRecentPagingCache<String, String>(
            cache = CursorInMemoryPagingCache(),
            maxSize = 3,
            protectContextWindow = false,
        )
        val inner = CursorQueuedPagingCache<String, String>(
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
        val c = CursorContextWindowPagingCache<String, String>(
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

    // ── Composition with an inner evicting strategy ─────────────────────────

    /** Moves the context window to `p[start]..p[end]` out of [TOTAL] linked pages. */
    private fun CursorPagingCache<String, String>.setContext(start: Int, end: Int) {
        startContextCursor = bookmarkAt(start, TOTAL)
        endContextCursor = bookmarkAt(end, TOTAL)
    }

    private fun CursorPagingCache<String, String>.put(i: Int) =
        setState(bookmarkAt(i, TOTAL), successPage(i, "item_$i"))

    /** FIFO decorator (outer) over a strict context window strategy (inner, `margin = 0`). */
    private fun fifoOverWindow(maxSize: Int): CursorQueuedPagingCache<String, String> =
        CursorQueuedPagingCache(
            cache = CursorContextWindowPagingCache(margin = 0),
            maxSize = maxSize,
            protectContextWindow = false,
        )

    @Test
    fun fifo_readded_page_after_inner_eviction_is_evicted_after_older_survivor() {
        val evicted = mutableListOf<Any>()
        val c = fifoOverWindow(maxSize = 2)
        c.evictionListener = CacheEvictionListener { evicted.add((it as CursorPageState<*, *>).bookmark.self) }

        c.setContext(0, 1)
        c.put(0)
        c.put(1)

        // The inner window strategy drops p0 behind the FIFO decorator's back.
        c.setContext(1, 1)
        c.put(1)
        assertNull(c.peekStateOf("p0"))

        // p0 is inserted again, so it is newer than p1.
        c.setContext(0, 1)
        c.put(0)

        // p2 overflows maxSize: the oldest surviving page is p1, not p0.
        c.setContext(0, 2)
        c.put(2)

        assertEquals(2, c.size)
        assertNull(c.peekStateOf("p1"))
        assertNotNull(c.peekStateOf("p0"))
        assertNotNull(c.peekStateOf("p2"))
        assertEquals(listOf<Any>("p1"), evicted)
    }

    @Test
    fun fifo_insertion_order_stays_bounded_when_inner_strategy_evicts() {
        val c = CursorQueuedPagingCache<String, String>(
            cache = CursorContextWindowPagingCache(margin = 0),
            maxSize = 5,
            protectContextWindow = false,
        )

        for (i in 0 until 1000) {
            val bookmark = bookmarkAt(i, 1000)
            c.startContextCursor = bookmark
            c.endContextCursor = bookmark
            c.setState(bookmark, successPage(i, "item_$i"))
        }

        assertEquals(1, c.size)
        assertTrue(c.trackedEntryCount < 50, "tracked ${c.trackedEntryCount} entries")
    }

    @Test
    fun fifo_remove_from_cache_of_readded_page_leaves_no_entry() {
        val c = fifoOverWindow(maxSize = 5)

        c.setContext(0, 1)
        c.put(0)
        c.put(1)

        // The inner window strategy drops p0, then p0 is added again.
        c.setContext(1, 1)
        c.put(1)
        assertNull(c.peekStateOf("p0"))
        c.setContext(0, 1)
        c.put(0)
        assertEquals(2, c.size)

        // Removing it through the outer decorator must not leave a second entry for p0.
        assertNotNull(c.removeFromCache("p0"))
        assertEquals(1, c.size)
        assertEquals(c.size, c.trackedEntryCount)
    }

    @Test
    fun lru_readded_page_after_inner_eviction_becomes_most_recent() {
        val c = CursorMostRecentPagingCache<String, String>(
            cache = CursorInMemoryPagingCache(),
            maxSize = 2,
            protectContextWindow = false,
        ) + CursorContextWindowPagingCache<String, String>(margin = 0)

        c.setContext(0, 1)
        c.put(0)
        c.put(1)

        // The inner window strategy drops p0 behind the LRU decorator's back.
        c.setContext(1, 1)
        c.put(1)
        assertNull(c.peekStateOf("p0"))

        // p0 comes back: it is the most recently used page now, p1 is older.
        c.setContext(0, 1)
        c.put(0)

        // p2 overflows maxSize: the least recently used surviving page is p1.
        c.setContext(0, 2)
        c.put(2)

        assertEquals(2, c.size)
        assertNull(c.peekStateOf("p1"))
        assertNotNull(c.peekStateOf("p0"))
        assertNotNull(c.peekStateOf("p2"))
    }

    @Test
    fun ttl_readded_page_after_inner_eviction_gets_fresh_timer() {
        val time = TestTimeSource()
        val c = CursorTimeLimitedPagingCache<String, String>(
            cache = CursorInMemoryPagingCache(),
            ttl = 1000.milliseconds,
            protectContextWindow = false,
            timeSource = time,
        ) + CursorContextWindowPagingCache<String, String>(margin = 0)

        // t = 0: p0 and p1 cached.
        c.setContext(0, 1)
        c.put(0)
        c.put(1)

        // The inner window strategy drops p0 behind the TTL decorator's back.
        c.setContext(1, 1)
        c.put(1)
        assertNull(c.peekStateOf("p0"))

        // Almost a full ttl later p0 comes back with a fresh timer.
        time += 900.milliseconds
        c.setContext(0, 1)
        c.put(0)

        // Another 500 ms: the old timestamp of p0 would be 1400 ms old, the fresh one is 500 ms.
        time += 500.milliseconds
        c.setContext(0, 2)
        c.put(2)

        assertNotNull(c.peekStateOf("p0"))
        // p1 has been cached since t = 0 and really expired, so the expiry pass did run.
        assertNull(c.peekStateOf("p1"))
        assertNotNull(c.peekStateOf("p2"))
    }

    private companion object {
        const val TOTAL = 5
    }
}
