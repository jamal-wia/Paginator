package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.core.extension.toUiState
import com.jamal_aliev.paginator.core.page.PaginatorUiState
import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.extension.refreshAll
import com.jamal_aliev.paginator.offset.load.LoadResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for bug PAG-XXX: after refreshAll the window stayed stuck on empty pages,
 * even though live data for another range was still in the cache.
 *
 * Tester scenario (like doing it by hand in a chat app):
 *   1. Scroll the list — pages 1,2,3 get loaded.
 *   2. Jump via bookmark deep to page 11 and scroll to 13 (a gap sits between 3 and 11).
 *   3. Sit on the 11..13 window, messages are visible.
 *   4. Internet drops. While it's gone, the server deletes that chunk (pages 11..13).
 *   5. Internet comes back -> the app calls refreshAll().
 *
 * Expectation AFTER the fix: the paginator re-anchors us to the nearest live
 * range (1..3) and shows data instead of a blank screen.
 */
class RefreshReanchorWindowTest {

    /** Backend: pages 1..3 always have data; pages 11..13 are "deleted" via the toggle. */
    private fun paginatorWithDeepRange(capacity: Int, deepRangeDeleted: () -> Boolean) =
        MutablePaginator<String> { page: Int ->
            val hasData = when (page) {
                in 1..3 -> true
                in 11..13 -> !deepRangeDeleted()
                else -> false
            }
            if (hasData) LoadResult(MutableList(capacity) { "p${page}_item$it" })
            else LoadResult(emptyList())
        }.apply { core.resize(capacity = capacity, resize = false, silently = true) }

    @Test
    fun `refreshAll redirects user to nearest live pages when viewed range was deleted`() =
        runTest {
            val capacity = 5
            var deleted = false
            val paginator = paginatorWithDeepRange(capacity) { deleted }

            // Step 1: scroll through pages 1,2,3
            paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // 2
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // 3

            // Step 2: jump to page 11 and scroll to 13 -> creates the gap 4..10
            paginator.jump(BookmarkInt(11), silentlyLoading = true, silentlyResult = true)
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // 12
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // 13

            // Step 3: confirm we are actually sitting on the 11..13 window and seeing data
            assertEquals(11, paginator.cache.startContextPage)
            assertEquals(13, paginator.cache.endContextPage)
            assertEquals(listOf(1, 2, 3, 11, 12, 13), paginator.cache.pages)

            // Step 4: the server deleted the currently viewed range
            deleted = true

            // Step 5: internet is back -> the app reconciles
            paginator.refreshAll(loadingSilently = true)

            // === What must hold if the fix worked ===

            // The window moved to the live range 1..3 instead of staying on the empty 11..13
            assertEquals(1, paginator.cache.startContextPage, "window should re-anchor to page 1")
            assertEquals(3, paginator.cache.endContextPage, "window should re-anchor to page 3")

            // The user sees messages again (not a blank screen)
            val visible = paginator.core.scan()
            assertEquals(listOf(1, 2, 3), visible.map { it.page })
            assertEquals(capacity * 3, visible.sumOf { it.data.size }, "15 items should be visible")

            // The UI emits content with items, not Empty / an empty Content
            val ui = visible.toUiState(isStarted = paginator.core.isStarted)
            assertTrue(ui is PaginatorUiState.Content, "expected Content, but got $ui")
            assertTrue(ui.items.isNotEmpty(), "the item list must not be empty")
        }

    @Test
    fun `refresh keeps window intact when refreshed pages are still filled`() = runTest {
        // Control: when the data is still there, a normal refresh must NOT move the window.
        val capacity = 5
        val paginator = paginatorWithDeepRange(capacity) { false }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // 2
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // 3

        paginator.refresh(pages = listOf(1, 2, 3), loadingSilently = true)

        assertEquals(1, paginator.cache.startContextPage)
        assertEquals(3, paginator.cache.endContextPage)
    }
}
