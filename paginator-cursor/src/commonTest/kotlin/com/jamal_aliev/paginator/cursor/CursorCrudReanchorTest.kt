package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.load.CursorLoadResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for the cursor CRUD twin of the re-anchor bug.
 *
 * Tester scenario: the user is viewing feed B (a disconnected deep feed) while feed A
 * is still cached, then deletes every message of feed B by hand. The window used to be
 * left on emptiness; now it re-anchors to the live feed A and the UI shows its content.
 */
class CursorCrudReanchorTest {

    private fun cb(prev: String?, self: String, next: String?) =
        CursorBookmark(prev = prev, self = self, next = next)

    private fun twoFeedPaginator(capacity: Int): MutableCursorPaginator<String, String> {
        val core = CursorPagingCore<String, String>(initialCapacity = capacity)
        return MutableCursorPaginator<String, String>(core = core) { cursor ->
            fun page(self: String, prev: String?, next: String?) = CursorLoadResult(
                data = List(capacity) { "${self}_item$it" },
                bookmark = cb(prev, self, next),
            )
            when (cursor?.self) {
                "a0" -> page("a0", null, "a1")
                "a1" -> page("a1", "a0", "a2")
                "a2" -> page("a2", "a1", null)
                // Feed B is a separate chain (head b0.prev points at an uncached key).
                "b0" -> page("b0", "x", "b1")
                "b1" -> page("b1", "b0", "b2")
                "b2" -> page("b2", "b1", null)
                else -> error("unknown ${cursor?.self}")
            }
        }
    }

    @Test
    fun `deleting every element of the viewed feed re-anchors to the live feed`() = runTest {
        val capacity = 3
        val paginator = twoFeedPaginator(capacity)

        // Open feed A, then jump into the disconnected feed B and scroll it.
        paginator.jump(cb(null, "a0", "a1"), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // a1
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // a2
        paginator.jump(cb("x", "b0", "b1"), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // b1
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // b2

        assertEquals("b0", paginator.core.startContextCursor?.self)
        assertEquals("b2", paginator.core.endContextCursor?.self)

        // User deletes every message of feed B (tail page first to avoid refill churn).
        for (self in listOf("b2", "b1", "b0")) {
            repeat(capacity) { paginator.removeElement(self = self, index = 0) }
        }

        // Window re-anchored to the live feed A...
        assertEquals("a0", paginator.core.startContextCursor?.self, "window should re-anchor to feed A")
        assertEquals("a2", paginator.core.endContextCursor?.self, "window should re-anchor to feed A")

        // ...and the snapshot flow emitted feed A's content, not a blank window.
        val emitted = paginator.core.snapshot.first()
        assertEquals(listOf("a0", "a1", "a2"), emitted.map { it.bookmark.self })
        assertEquals(capacity * 3, emitted.sumOf { it.data.size }, "user must see feed A items")
        assertTrue(emitted.none { it.bookmark.self.startsWith("b") }, "deleted feed B must be gone")
    }
}
