package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.core.extension.toUiState
import com.jamal_aliev.paginator.core.page.PaginatorUiState
import com.jamal_aliev.paginator.offset.extension.addElement
import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.load.LoadResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for issue #2: when the cache holds only a PARTIAL page (a list shorter than one page
 * capacity), a mutation / refresh used to re-anchor via `findNearContextPage`, find no FILLED
 * (`data.size == capacity`) page, and collapse the context window to `0,0` — which stops the
 * reactive snapshot (SnapshotReactiveCache then drops the `Invalidate` reorder or empties the list
 * under `Moves`). A partial-but-present success page must remain a valid context anchor.
 */
class ShortListReactiveWindowTest {

    /** Backend returning a single short page (3 items) with capacity 50 — never a filled page. */
    private fun shortListPaginator() =
        MutablePaginator<Int> { _: Int -> LoadResult(listOf(1, 2, 3)) }
            .apply { core.resize(capacity = 50, resize = false, silently = true) }

    @Test
    fun `mutating a list shorter than one page keeps the window and emits the update`() = runTest {
        val paginator = shortListPaginator()

        paginator.jump(BookmarkInt(1))

        // Precondition: the short partial list is visible, window anchored on page 1.
        assertEquals(1, paginator.cache.startContextPage)
        assertEquals(1, paginator.cache.endContextPage)
        assertEquals(listOf(1, 2, 3), paginator.core.snapshot.first().flatMap { it.data })

        // Add an element — routes through addAllElements -> snapshotAfterMutation -> findNearContextPage.
        paginator.addElement(4)

        // Before the fix the window collapsed to 0,0 (isStarted == false) and the snapshot froze on
        // the stale [1,2,3]. It must now stay anchored and emit the update.
        assertTrue(paginator.core.isStarted, "window must stay anchored on the partial page")
        assertEquals(1, paginator.cache.startContextPage)
        assertEquals(1, paginator.cache.endContextPage)

        val emitted = paginator.core.snapshot.first()
        assertEquals(listOf(1, 2, 3, 4), emitted.flatMap { it.data }, "the mutation must be visible")

        val ui = emitted.toUiState(isStarted = paginator.core.isStarted)
        assertTrue(ui is PaginatorUiState.Content, "expected Content, got $ui")
        assertEquals(listOf(1, 2, 3, 4), ui.items)
    }

    @Test
    fun `refreshing a list shorter than one page keeps the window`() = runTest {
        val paginator = shortListPaginator()
        paginator.jump(BookmarkInt(1))

        paginator.refresh(pages = listOf(1))

        assertTrue(paginator.core.isStarted, "refresh must not collapse a short-list window")
        assertEquals(1, paginator.cache.startContextPage)
        assertEquals(1, paginator.cache.endContextPage)
        assertEquals(listOf(1, 2, 3), paginator.core.snapshot.first().flatMap { it.data })
    }
}
