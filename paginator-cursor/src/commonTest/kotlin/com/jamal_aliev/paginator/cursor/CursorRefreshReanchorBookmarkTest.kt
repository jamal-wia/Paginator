package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.extension.refreshAll
import com.jamal_aliev.paginator.cursor.load.CursorLoadResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cursor twin of RefreshReanchorBookmarkTest: when refresh re-anchors the window (the backend
 * deleted the viewed feed), the bookmark cursor must be realigned to the new position so a
 * subsequent jumpForward/jumpBack resumes from the live feed, not the deleted one.
 */
class CursorRefreshReanchorBookmarkTest {

    private fun cb(prev: String?, self: String, next: String?) =
        CursorBookmark(prev = prev, self = self, next = next)

    @Test
    fun `refreshAll reanchor realigns bookmark cursor so jumpForward resumes from the live feed`() =
        runTest {
            val capacity = 3
            var feedBDeleted = false

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
                    "c0" -> page("c0", "y", null, alive = true) // a separate live "ahead" target
                    "b0" -> page("b0", "x", "b1", alive = !feedBDeleted)
                    "b1" -> page("b1", "b0", "b2", alive = !feedBDeleted)
                    "b2" -> page("b2", "b1", null, alive = !feedBDeleted)
                    else -> error("unknown ${cursor?.self}")
                }
            }

            paginator.bookmarks.clear()
            paginator.bookmarks.add(cb(null, "a0", "a1")) // feed A head — inside the window after reanchor
            paginator.bookmarks.add(cb("y", "c0", null))  // the live target just past feed A
            paginator.bookmarks.add(cb("x", "b0", "b1"))  // deep feed B (deleted)
            paginator.bookmarks.add(cb("b0", "b1", "b2")) // deep feed B (deleted)

            // Open feed A (cached), then jump into deep feed B (syncs the bookmark cursor onto B).
            paginator.jump(cb(null, "a0", "a1"), silentlyLoading = true, silentlyResult = true)
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // a1
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // a2
            paginator.jump(cb("x", "b0", "b1"), silentlyLoading = true, silentlyResult = true)
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // b1
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // b2
            assertEquals("b0", core.startContextCursor?.self)

            // Server deleted feed B; refresh re-anchors the window back to the live feed A.
            feedBDeleted = true
            paginator.refreshAll(loadingSilently = true)
            assertEquals("a0", core.startContextCursor?.self)

            // jumpForward must resume from feed A: the next bookmark outside it is the live c0.
            // Without realigning the bookmark cursor, the stale cursor (left on feed B) would jump
            // forward into the deleted feed-B bookmark b1 instead.
            val forward = paginator.jumpForward(silentlyLoading = true, silentlyResult = true)
            assertEquals("c0", forward?.first?.self)
        }
}
