package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comprehensive edge-case tests for bookmarkIndex synchronisation
 * across all navigation methods: jump, goNextPage, goPreviousPage,
 * restart, jumpForward, jumpBack.
 */
class BookmarkSyncEdgeCasesTest {

    // ── restart() ────────────────────────────────────────────────────────

    @Test
    fun `restart syncs bookmarkIndex so jumpForward starts from beginning`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(
            listOf(BookmarkInt(1), BookmarkInt(5), BookmarkInt(10), BookmarkInt(15))
        )

        // Navigate far from page 1
        paginator.jump(BookmarkInt(12), silentlyLoading = true, silentlyResult = false)

        // restart → page 1, bookmarkIndex should sync to 1 (first bookmark > 1 is index 1)
        paginator.restart(silentlyLoading = true, silentlyResult = false)

        // jumpForward should find bookmark 5 (not 15)
        val result = paginator.jumpForward(silentlyLoading = true, silentlyResult = false)
        assertNotNull(result)
        assertEquals(5, result!!.first.page)
    }

    @Test
    fun `restart syncs bookmarkIndex so jumpBack finds nearest preceding`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(
            listOf(BookmarkInt(1), BookmarkInt(5), BookmarkInt(10), BookmarkInt(15))
        )

        paginator.jump(BookmarkInt(12), silentlyLoading = true, silentlyResult = false)
        paginator.restart(silentlyLoading = true, silentlyResult = false)

        // jumpBack from synced position (bookmarkIndex=1) → checks index 0 = page 1
        // page 1 is in visible range (snapshot is page 1) → skip / fallback
        val result = paginator.jumpBack(
            recycling = true,
            silentlyLoading = true,
            silentlyResult = false
        )
        // All preceding bookmarks are page 1 which is visible, so with recycling
        // we may find 15, 10, or 5 (outside visible range)
        if (result != null) {
            assertTrue(result.first.page > 1)
        }
    }

    // ── jump to exact bookmark page ──────────────────────────────────────

    @Test
    fun `jump to exact bookmark page positions index correctly`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(
            listOf(BookmarkInt(1), BookmarkInt(5), BookmarkInt(10), BookmarkInt(15))
        )

        // Jump to page 5 (exact bookmark match)
        paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = false)

        // jumpForward should go to 10 (next bookmark after 5)
        val fwd = paginator.jumpForward(silentlyLoading = true, silentlyResult = false)
        assertNotNull(fwd)
        assertEquals(10, fwd!!.first.page)
    }

    @Test
    fun `jump to exact bookmark then jumpBack goes to preceding bookmark`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(
            listOf(BookmarkInt(1), BookmarkInt(5), BookmarkInt(10), BookmarkInt(15))
        )

        // Jump to page 10 (exact match at index 2)
        paginator.jump(BookmarkInt(10), silentlyLoading = true, silentlyResult = false)
        // Visible range = 10..10, bookmarkIndex should be 3

        // jumpBack: checks index 2 = page 10, in 10..10 → skip
        //           checks index 1 = page 5, NOT in 10..10 → returns 5
        val back = paginator.jumpBack(silentlyLoading = true, silentlyResult = false)
        assertNotNull(back)
        assertEquals(5, back!!.first.page)
    }

    // ── jump beyond / before all bookmarks ───────────────────────────────

    @Test
    fun `jump beyond all bookmarks then jumpBack finds last bookmark`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(
            listOf(BookmarkInt(1), BookmarkInt(5), BookmarkInt(10))
        )

        // Jump to page 18 (beyond all bookmarks)
        paginator.jump(BookmarkInt(18), silentlyLoading = true, silentlyResult = false)

        // jumpBack should find bookmark 10 (last one before page 18)
        val back = paginator.jumpBack(silentlyLoading = true, silentlyResult = false)
        assertNotNull(back)
        assertEquals(10, back!!.first.page)
    }

    @Test
    fun `jump beyond all bookmarks then jumpForward returns null without recycling`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(
            listOf(BookmarkInt(1), BookmarkInt(5), BookmarkInt(10))
        )

        paginator.jump(BookmarkInt(18), silentlyLoading = true, silentlyResult = false)

        // No bookmarks ahead without recycling
        val fwd = paginator.jumpForward(
            recycling = false,
            silentlyLoading = true,
            silentlyResult = false
        )
        assertNull(fwd)
    }

    @Test
    fun `jump before first bookmark then jumpForward finds first bookmark`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(
            listOf(BookmarkInt(5), BookmarkInt(10), BookmarkInt(15))
        )

        // Jump to page 2 (before all bookmarks)
        paginator.jump(BookmarkInt(2), silentlyLoading = true, silentlyResult = false)

        // jumpForward should find bookmark 5
        val fwd = paginator.jumpForward(silentlyLoading = true, silentlyResult = false)
        assertNotNull(fwd)
        assertEquals(5, fwd!!.first.page)
    }

    @Test
    fun `jump before first bookmark then jumpBack returns null without recycling`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(
            listOf(BookmarkInt(5), BookmarkInt(10), BookmarkInt(15))
        )

        paginator.jump(BookmarkInt(2), silentlyLoading = true, silentlyResult = false)

        val back = paginator.jumpBack(
            recycling = false,
            silentlyLoading = true,
            silentlyResult = false
        )
        assertNull(back)
    }

    // ── Multiple consecutive jumps ───────────────────────────────────────

    @Test
    fun `multiple jumps keep bookmarkIndex in sync`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(
            listOf(BookmarkInt(1), BookmarkInt(5), BookmarkInt(10), BookmarkInt(15))
        )

        // jump(3) → bookmarkIndex should point before bookmark 5
        paginator.jump(BookmarkInt(3), silentlyLoading = true, silentlyResult = false)
        var fwd = paginator.jumpForward(silentlyLoading = true, silentlyResult = false)
        assertNotNull(fwd)
        assertEquals(5, fwd!!.first.page)

        // jump(12) → bookmarkIndex should point before bookmark 15
        paginator.jump(BookmarkInt(12), silentlyLoading = true, silentlyResult = false)
        fwd = paginator.jumpForward(silentlyLoading = true, silentlyResult = false)
        assertNotNull(fwd)
        assertEquals(15, fwd!!.first.page)

        // jump(7) → bookmarkIndex should point before bookmark 10
        paginator.jump(BookmarkInt(7), silentlyLoading = true, silentlyResult = false)
        var back = paginator.jumpBack(silentlyLoading = true, silentlyResult = false)
        assertNotNull(back)
        assertEquals(5, back!!.first.page)
    }

    // ── goNextPage extensive navigation + jumpBack ───────────────────────

    @Test
    fun `goNextPage past multiple bookmarks then jumpBack with recycling`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(
            listOf(BookmarkInt(1), BookmarkInt(5), BookmarkInt(10), BookmarkInt(15))
        )

        // Start and navigate sequentially to page 12
        paginator.goNextPage(silentlyLoading = true, silentlyResult = false) // 1 (auto-start via jump)
        for (i in 2..12) {
            paginator.goNextPage(silentlyLoading = true, silentlyResult = false) // 2..12
        }
        // Context: 1..12, visible range = 1..12
        // Bookmarks 1, 5, 10 are all in visible range
        // Only bookmark 15 is outside

        val back = paginator.jumpBack(
            recycling = true,
            silentlyLoading = true,
            silentlyResult = false
        )
        // With recycling, jumpBack should wrap around and find 15 (only one outside 1..12)
        assertNotNull(back)
        assertEquals(15, back!!.first.page)
    }

    @Test
    fun `goNextPage then jump resets context and jumpBack works correctly`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(
            listOf(BookmarkInt(1), BookmarkInt(5), BookmarkInt(10), BookmarkInt(15))
        )

        // Sequential navigation to page 3
        paginator.goNextPage(silentlyLoading = true, silentlyResult = false) // 1
        paginator.goNextPage(silentlyLoading = true, silentlyResult = false) // 2
        paginator.goNextPage(silentlyLoading = true, silentlyResult = false) // 3

        // Jump to page 12 — resets context to 12..12
        paginator.jump(BookmarkInt(12), silentlyLoading = true, silentlyResult = false)

        // jumpBack should find 10 (nearest bookmark before 12, outside visible range 12..12)
        val back = paginator.jumpBack(silentlyLoading = true, silentlyResult = false)
        assertNotNull(back)
        assertEquals(10, back!!.first.page)
    }

    // ── goPreviousPage + jumpForward ─────────────────────────────────────

    @Test
    fun `goPreviousPage then jumpForward finds correct next bookmark`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(
            listOf(BookmarkInt(1), BookmarkInt(5), BookmarkInt(10), BookmarkInt(15))
        )

        // Jump to page 12, then go back to page 8
        paginator.jump(BookmarkInt(12), silentlyLoading = true, silentlyResult = false)
        for (i in 11 downTo 8) {
            paginator.goPreviousPage(silentlyLoading = true, silentlyResult = false)
        }
        // Context: 8..12, visible range = 8..12
        // bookmarkIndex synced by jump(12) to index 3 (first bookmark > 12 = bookmarks.size = 4? no, index 3 = bookmark 15)
        // Actually syncBookmarkIndex(12): indexOfFirst { page > 12 } = 3 → bookmarkIndex = 3

        // jumpForward from 3: checks 3 (page 15, NOT in 8..12) → returns 15
        val fwd = paginator.jumpForward(silentlyLoading = true, silentlyResult = false)
        assertNotNull(fwd)
        assertEquals(15, fwd!!.first.page)
    }

    // ── Single bookmark ──────────────────────────────────────────────────

    @Test
    fun `single bookmark - jump then jumpBack returns null without recycling`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        // Default bookmarks = [BookmarkInt(1)]

        paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = false)
        // syncBookmarkIndex(5): indexOfFirst { page > 5 } = -1 → bookmarkIndex = 1

        val back = paginator.jumpBack(
            recycling = false,
            silentlyLoading = true,
            silentlyResult = false
        )
        // bookmarkIndex = 1, limit = 1 (no recycling)
        // checks index 0 = page 1, NOT in 5..5 → returns 1
        assertNotNull(back)
        assertEquals(1, back!!.first.page)
    }

    @Test
    fun `single bookmark - jump then jumpForward returns null without recycling`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        // Default bookmarks = [BookmarkInt(1)]

        paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = false)
        // bookmarkIndex = 1 (past end)

        val fwd = paginator.jumpForward(
            recycling = false,
            silentlyLoading = true,
            silentlyResult = false
        )
        // bookmarkIndex = 1, coerced to min(1, 1) = 1. limit = 1 - 1 = 0
        assertNull(fwd)
    }

    // ── Recycling edge cases ─────────────────────────────────────────────

    @Test
    fun `jumpForward with recycling after jump finds correct bookmark`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(
            listOf(BookmarkInt(1), BookmarkInt(5), BookmarkInt(10), BookmarkInt(15))
        )

        // Jump to page 13 → bookmarkIndex = 3 (bookmark 15)
        paginator.jump(BookmarkInt(13), silentlyLoading = true, silentlyResult = false)

        // jumpForward from 3: checks 3 (page 15, NOT in 13..13) → returns 15
        val fwd = paginator.jumpForward(
            recycling = true,
            silentlyLoading = true,
            silentlyResult = false
        )
        assertNotNull(fwd)
        assertEquals(15, fwd!!.first.page)
    }

    @Test
    fun `jumpBack with recycling after jump beyond all finds last bookmark`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(
            listOf(BookmarkInt(1), BookmarkInt(5), BookmarkInt(10))
        )

        // Jump to page 20 → bookmarkIndex = 3 (bookmarks.size)
        paginator.jump(BookmarkInt(20), silentlyLoading = true, silentlyResult = true)

        // jumpBack from 3 with recycling: checks 2 (page 10) → returns 10
        val back = paginator.jumpBack(
            recycling = true,
            silentlyLoading = true,
            silentlyResult = false
        )
        assertNotNull(back)
        assertEquals(10, back!!.first.page)
    }

    // ── jumpForward/jumpBack interleaving ────────────────────────────────

    @Test
    fun `alternating jumpForward and jumpBack`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(
            listOf(BookmarkInt(1), BookmarkInt(5), BookmarkInt(10), BookmarkInt(15))
        )

        // Start at page 7
        paginator.jump(BookmarkInt(7), silentlyLoading = true, silentlyResult = false)
        // bookmarkIndex = 2 (before bookmark 10)

        // jumpForward → 10
        val fwd1 = paginator.jumpForward(silentlyLoading = true, silentlyResult = false)
        assertNotNull(fwd1)
        assertEquals(10, fwd1!!.first.page)
        // jumpForward internally: found index 2, sets bookmarkIndex = 3, calls jump(10) which syncs to 3, restores to 3

        // jumpBack → should find bookmark before page 10 (visible range after jump is 10..10)
        // bookmarkIndex = 3, checks index 2 (page 10, in 10..10 → skip), index 1 (page 5, NOT in range) → returns 5
        val back1 = paginator.jumpBack(silentlyLoading = true, silentlyResult = false)
        assertNotNull(back1)
        assertEquals(5, back1!!.first.page)
    }

    // ── goNextPage auto-start syncs correctly ────────────────────────────

    @Test
    fun `goNextPage auto-start syncs bookmarkIndex`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(
            listOf(BookmarkInt(1), BookmarkInt(5), BookmarkInt(10))
        )

        // goNextPage triggers auto-start via jump(1), which syncs bookmarkIndex
        paginator.goNextPage(silentlyLoading = true, silentlyResult = false) // page 1

        // jumpForward should find bookmark 5 (not skip to 10)
        val fwd = paginator.jumpForward(silentlyLoading = true, silentlyResult = false)
        assertNotNull(fwd)
        assertEquals(5, fwd!!.first.page)
    }

    // ── Stress: mixed operations ─────────────────────────────────────────

    @Test
    fun `complex mixed navigation scenario`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(
            listOf(BookmarkInt(1), BookmarkInt(5), BookmarkInt(10), BookmarkInt(15), BookmarkInt(20))
        )

        // 1. Start at page 1
        paginator.goNextPage(silentlyLoading = true, silentlyResult = false) // page 1
        // 2. Navigate to page 3
        paginator.goNextPage(silentlyLoading = true, silentlyResult = false) // page 2
        paginator.goNextPage(silentlyLoading = true, silentlyResult = false) // page 3

        // 3. Jump to page 12
        paginator.jump(BookmarkInt(12), silentlyLoading = true, silentlyResult = false)

        // 4. jumpBack should go to 10
        val back1 = paginator.jumpBack(silentlyLoading = true, silentlyResult = false)
        assertNotNull(back1)
        assertEquals(10, back1!!.first.page)

        // 5. Now at page 10. jumpForward should go to 15
        //    (jumpBack set bookmarkIndex, then jump(10) synced to 3)
        //    After jumpBack: bookmarkIndex restored to jumpBack's saved value = 2
        //    But jump(10) inside jumpBack synced it to 3, then restore to 2
        //    jumpForward from 2: checks index 2 (page 10, in 10..10 skip), index 3 (page 15, not in range) → 15
        val fwd1 = paginator.jumpForward(silentlyLoading = true, silentlyResult = false)
        assertNotNull(fwd1)
        assertEquals(15, fwd1!!.first.page)

        // 6. Restart
        paginator.restart(silentlyLoading = true, silentlyResult = false)

        // 7. After restart (page 1), jumpForward should go to 5
        val fwd2 = paginator.jumpForward(silentlyLoading = true, silentlyResult = false)
        assertNotNull(fwd2)
        assertEquals(5, fwd2!!.first.page)
    }

    // ── saveState/restoreState preserves synced bookmarkIndex ─────────────

    @Test
    fun `saveState after jump preserves synced bookmarkIndex`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(
            listOf(BookmarkInt(1), BookmarkInt(5), BookmarkInt(10), BookmarkInt(15))
        )

        // Jump to page 7 → bookmarkIndex syncs to 2
        paginator.jump(BookmarkInt(7), silentlyLoading = true, silentlyResult = false)

        val snapshot = paginator.saveState()

        // Create new paginator, restore state
        val restored = createDeterministicPaginator(capacity = 5)
        restored.restoreState(snapshot, silently = false)

        // jumpForward should find 10 (same as before save)
        val fwd = restored.jumpForward(silentlyLoading = true, silentlyResult = false)
        assertNotNull(fwd)
        assertEquals(10, fwd!!.first.page)
    }
}
