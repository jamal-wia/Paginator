package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.core.extension.toUiState
import com.jamal_aliev.paginator.core.page.PaginatorUiState
import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.load.LoadResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for the offset CRUD twin of the refresh bug: deleting the elements of the
 * only visible page used to re-anchor the cache but suppress the UI emission, leaving the
 * user staring at a stale/blank window even though live pages were still cached.
 */
class CrudReanchorWindowTest {

    /** Backend with data on pages 1..3 and 11..13, a gap in between. */
    private fun gapPaginator(capacity: Int) =
        MutablePaginator<String> { page: Int ->
            val hasData = page in 1..3 || page in 11..13
            if (hasData) LoadResult(MutableList(capacity) { "p${page}_item$it" })
            else LoadResult(emptyList())
        }.apply { core.resize(capacity = capacity, resize = false, silently = true) }

    @Test
    fun `removeElement emptying the only visible page re-anchors AND emits the new window`() =
        runTest {
            val capacity = 5
            val paginator = gapPaginator(capacity)

            // Load 1,2,3, then jump deep to page 11 (gap 4..10), window = 11..11.
            paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
            paginator.jump(BookmarkInt(11), silentlyLoading = true, silentlyResult = true)

            assertEquals(11, paginator.cache.startContextPage)
            assertEquals(11, paginator.cache.endContextPage)

            // User deletes every message of page 11 (e.g. clears the visible chunk).
            repeat(capacity) { paginator.removeElement(page = 11, index = 0) }

            // Cache re-anchored to the nearest live group 1..3...
            assertEquals(1, paginator.cache.startContextPage)
            assertEquals(3, paginator.cache.endContextPage)

            // ...and the SNAPSHOT FLOW actually emitted that new window (the real bug:
            // previously this emission was suppressed and the UI stayed on page 11).
            val emitted = paginator.core.snapshot.first()
            assertEquals(listOf(1, 2, 3), emitted.map { it.page }, "emission must show the live window")
            assertTrue(11 !in emitted.map { it.page }, "the deleted page must be gone from the UI")

            val ui = emitted.toUiState(isStarted = paginator.core.isStarted)
            assertTrue(ui is PaginatorUiState.Content, "expected Content, got $ui")
            assertTrue(ui.items.isNotEmpty(), "user must see items, not a blank screen")
        }
}
