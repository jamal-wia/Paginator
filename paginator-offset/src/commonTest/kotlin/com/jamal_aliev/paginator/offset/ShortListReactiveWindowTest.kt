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

    /**
     * The re-anchor case that distinguishes a real fix from merely swapping the `validStates`
     * predicate: the window must MOVE onto a *different* still-cached partial page, which requires
     * setting it directly (the shared `expand*` walks bail on a partial pivot and would leave the
     * window on the now-dead page).
     */
    @Test
    fun `deleting the viewed short group re-anchors onto another cached short group`() = runTest {
        // Two disconnected short pages: page 1 and page 11, each 3 items (partial, capacity 50).
        val paginator = MutablePaginator<String> { page: Int ->
            if (page == 1 || page == 11) LoadResult(List(3) { "p${page}_$it" }) else LoadResult(emptyList())
        }.apply { core.resize(capacity = 50, resize = false, silently = true) }

        paginator.jump(BookmarkInt(1))   // cache the partial page 1
        paginator.jump(BookmarkInt(11))  // view the partial page 11
        assertEquals(11, paginator.cache.startContextPage)
        assertEquals(11, paginator.cache.endContextPage)

        // Delete every item of the viewed partial page 11.
        repeat(3) { paginator.removeElement(page = 11, index = 0) }

        // Must re-anchor to the still-cached partial page 1 — not sit on the now-dead page 11.
        assertTrue(paginator.core.isStarted, "window must re-anchor, not collapse")
        assertEquals(1, paginator.cache.startContextPage)
        assertEquals(1, paginator.cache.endContextPage)
        val emitted = paginator.core.snapshot.first()
        assertEquals(listOf(1), emitted.map { it.page })
        assertEquals(listOf("p1_0", "p1_1", "p1_2"), emitted.flatMap { it.data })
    }
}
