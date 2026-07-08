package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.extension.addElement
import com.jamal_aliev.paginator.cursor.load.CursorLoadResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cursor twin of the offset issue #2 regression: a partial-only feed (a single page shorter than
 * one page capacity) used to collapse the context window (`startContextCursor`/`endContextCursor`
 * set to `null`) on any mutation / refresh, which stops the reactive snapshot. A partial-but-present
 * success page must remain a valid context anchor.
 */
class CursorShortFeedReactiveWindowTest {

    private fun cb(prev: String?, self: String, next: String?) =
        CursorBookmark(prev = prev, self = self, next = next)

    /** Single short page "a0" (3 items) with capacity 50 — never a filled page. */
    private fun shortFeedPaginator(): MutableCursorPaginator<String, String> {
        val core = CursorPagingCore<String, String>(initialCapacity = 50)
        return MutableCursorPaginator<String, String>(core = core) { cursor ->
            when (cursor?.self) {
                null, "a0" -> CursorLoadResult(
                    data = listOf("a0_item0", "a0_item1", "a0_item2"),
                    bookmark = cb(null, "a0", null),
                )
                else -> error("unknown ${cursor?.self}")
            }
        }
    }

    @Test
    fun `mutating a feed shorter than one page keeps the window and emits the update`() = runTest {
        val paginator = shortFeedPaginator()

        paginator.jump(cb(null, "a0", null))

        // Precondition: the short partial feed is visible, window anchored on a0.
        assertEquals("a0", paginator.core.startContextCursor?.self)
        assertEquals("a0", paginator.core.endContextCursor?.self)
        assertEquals(3, paginator.core.snapshot.first().sumOf { it.data.size })

        // Append an element — routes through addAllElements -> snapshotAfterMutation -> findNearContextCursor.
        paginator.addElement("a0_item3")

        // Before the fix the window collapsed to null,null (isStarted == false) and the snapshot
        // froze on the stale 3 items. It must now stay anchored and emit the update.
        assertTrue(paginator.core.isStarted, "window must stay anchored on the partial page")
        assertEquals("a0", paginator.core.startContextCursor?.self)
        assertEquals("a0", paginator.core.endContextCursor?.self)
        assertEquals(4, paginator.core.snapshot.first().sumOf { it.data.size }, "mutation must be visible")
    }
}
