package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.extension.refreshAll
import com.jamal_aliev.paginator.offset.load.LoadResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression: when refresh re-anchors the window (the backend deleted the viewed range), the
 * bookmark cursor must be realigned to the new position — just like jump/restart do — so a
 * subsequent jumpForward/jumpBack resumes from the new window, not the deleted range.
 */
class RefreshReanchorBookmarkTest {

    @Test
    fun `refresh reanchor realigns bookmark cursor so jumpForward resumes from the new window`() =
        runTest {
            var deleted = false
            val paginator = MutablePaginator<String> { page: Int ->
                val hasData = when (page) {
                    in 1..3 -> true     // live range
                    5 -> true           // a bookmark target just past the live range
                    in 11..13 -> !deleted // the deletable deep range
                    else -> false
                }
                if (hasData) LoadResult(MutableList(5) { "p${page}_item$it" })
                else LoadResult(emptyList())
            }.apply { core.resize(capacity = 5, resize = false, silently = true) }

            paginator.bookmarks.clear()
            paginator.bookmarks.add(BookmarkInt(2))   // inside the live range 1..3
            paginator.bookmarks.add(BookmarkInt(5))   // just past the live range
            paginator.bookmarks.add(BookmarkInt(12))  // inside the deep range 11..13

            // Cache the live range 1..3, then jump deep to 11..13 (syncs the bookmark cursor past 11).
            paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
            paginator.jump(BookmarkInt(11), silentlyLoading = true, silentlyResult = true)
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
            assertEquals(11, paginator.cache.startContextPage)

            // The server deleted the viewed range; refresh re-anchors the window back to 1..3.
            deleted = true
            paginator.refreshAll(loadingSilently = true)
            assertEquals(1, paginator.cache.startContextPage)
            assertEquals(3, paginator.cache.endContextPage)

            // jumpForward must resume from the NEW window (1..3): the next bookmark outside it is
            // page 5. Without realigning the bookmark cursor, the stale cursor (left after the
            // deleted page 11) would instead jump forward to the deleted bookmark at page 12.
            val forward = paginator.jumpForward(silentlyLoading = true, silentlyResult = true)
            assertEquals(5, forward?.first?.page)
        }
}
