package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.extension.refreshAll
import com.jamal_aliev.paginator.cursor.load.CursorLoadResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for the cursor twin of the offset refresh bug.
 *
 * Tester scenario (chat with two separately-opened feeds):
 *   1. Open feed A — pages a0,a1,a2 load, window sits on A.
 *   2. Jump into a different deep feed B (b0,b1,b2) — window moves to B; A stays cached
 *      but disconnected (the cursor analog of an offset "gap").
 *   3. Internet drops; while offline the server deletes feed B.
 *   4. Internet returns -> the app calls refreshAll().
 *
 * Expectation AFTER the fix: feed B comes back empty, but instead of showing a blank
 * window the paginator re-anchors to the nearest live group (feed A).
 */
class CursorRefreshReanchorTest {

    private fun cb(prev: String?, self: String, next: String?) =
        CursorBookmark(prev = prev, self = self, next = next)

    @Test
    fun `refreshAll re-anchors to the live feed when the viewed feed was deleted`() = runTest {
        val capacity = 3
        var feedBDeleted = false

        // Feed A is a standalone chain (a0 is the canonical head, prev == null).
        // Feed B is a separate chain whose head b0.prev points at a key that is never
        // cached ("x"), so A and B stay disconnected in the cache.
        val core = CursorPagingCore<String, String>(initialCapacity = capacity)
        val paginator = CursorPaginator<String, String>(core = core) { cursor ->
            fun page(self: String, prev: String?, next: String?, alive: Boolean) =
                CursorLoadResult(
                    data = if (alive) List(capacity) { "${self}_item$it" } else emptyList(),
                    bookmark = cb(prev, self, next),
                )
            when (cursor?.self) {
                "a0" -> page("a0", null, "a1", alive = true)
                "a1" -> page("a1", "a0", "a2", alive = true)
                "a2" -> page("a2", "a1", null, alive = true)
                "b0" -> page("b0", "x", "b1", alive = !feedBDeleted)
                "b1" -> page("b1", "b0", "b2", alive = !feedBDeleted)
                "b2" -> page("b2", "b1", null, alive = !feedBDeleted)
                else -> error("unknown ${cursor?.self}")
            }
        }

        // Step 1: open feed A
        paginator.jump(cb(null, "a0", "a1"), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // a1
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // a2

        // Step 2: jump into the disconnected deep feed B and scroll it
        paginator.jump(cb("x", "b0", "b1"), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // b1
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // b2

        // We are on feed B; feed A is cached but out of the window.
        assertEquals("b0", core.startContextCursor?.self)
        assertEquals("b2", core.endContextCursor?.self)
        assertTrue(core.getStateOf("a0") != null, "feed A must still be cached")

        // Step 3 + 4: server deleted feed B, app reconciles on reconnect
        feedBDeleted = true
        paginator.refreshAll(loadingSilently = true)

        // === What must hold if the fix worked ===

        // Window re-anchored to the live feed A...
        assertEquals("a0", core.startContextCursor?.self, "window should re-anchor to feed A head")
        assertEquals("a2", core.endContextCursor?.self, "window should re-anchor to feed A tail")

        // ...and the snapshot flow emitted feed A's live content, not a blank window.
        val emitted = core.snapshot.first()
        assertEquals(listOf("a0", "a1", "a2"), emitted.map { it.bookmark.self })
        assertEquals(capacity * 3, emitted.sumOf { it.data.size }, "user must see feed A items")
        assertTrue(emitted.none { it.bookmark.self.startsWith("b") }, "deleted feed B must be gone")
    }
}
