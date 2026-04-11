package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import com.jamal_aliev.paginator.extension.isSuccessState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookmarkNavigationTest {

    @Test
    fun `BookmarkInt requires page gte 1`() {
        val bookmark = BookmarkInt(page = 1)
        assertEquals(1, bookmark.page)
    }

    @Test
    fun `BookmarkInt rejects page 0`() {
        assertFailsWith<IllegalArgumentException> {
            BookmarkInt(page = 0)
        }
    }

    @Test
    fun `BookmarkInt rejects negative page`() {
        assertFailsWith<IllegalArgumentException> {
            BookmarkInt(page = -1)
        }
    }

    @Test
    fun `jumpForward with default bookmark navigates to page 1`() = runTest {
        val paginator = createDeterministicPaginator()
        // Default bookmarks = [BookmarkInt(1)]
        val result = paginator.jumpForward(silentlyLoading = true, silentlyResult = true)
        assertNotNull(result)
        assertEquals(1, result!!.first.page)
        assertTrue(result.second.isSuccessState())
    }

    @Test
    fun `jumpForward returns null after exhausting bookmarks`() = runTest {
        val paginator = createDeterministicPaginator()
        // Default bookmarks = [BookmarkInt(1)]
        paginator.jumpForward(silentlyLoading = true, silentlyResult = true) // consumes 1

        val result = paginator.jumpForward(
            recycling = false,
            silentlyLoading = true,
            silentlyResult = true
        )
        assertNull(result)
    }

    @Test
    fun `jumpForward with recycling wraps around`() = runTest {
        val paginator = createDeterministicPaginator()
        // Default bookmarks = [BookmarkInt(1)]
        paginator.jumpForward(silentlyLoading = true, silentlyResult = true) // consumes 1

        // Without recycling, should return null
        val noRecycle = paginator.jumpForward(
            recycling = false,
            silentlyLoading = true,
            silentlyResult = true
        )
        assertNull(noRecycle)

        // With recycling, should wrap to first bookmark
        val recycled = paginator.jumpForward(
            recycling = true,
            silentlyLoading = true,
            silentlyResult = true
        )
        assertNotNull(recycled)
        assertEquals(1, recycled!!.first.page)
    }

    @Test
    fun `jumpBack returns null at beginning without recycling`() = runTest {
        val paginator = createDeterministicPaginator()
        // Default bookmarks = [BookmarkInt(1)]
        // No forward navigation done, so iterator is at beginning
        val result = paginator.jumpBack(
            recycling = false,
            silentlyLoading = true,
            silentlyResult = true
        )
        assertNull(result)
    }

    @Test
    fun `jumpForward then jumpBack returns same bookmark`() = runTest {
        val paginator = createDeterministicPaginator()
        // Default bookmarks = [BookmarkInt(1)]
        val forward = paginator.jumpForward(silentlyLoading = true, silentlyResult = true)
        assertNotNull(forward)
        assertEquals(1, forward!!.first.page)

        // jumpBack should go to same (previous) which is 1
        val back = paginator.jumpBack(silentlyLoading = true, silentlyResult = true)
        assertNotNull(back)
        assertEquals(1, back!!.first.page)
    }

    @Test
    fun `jumpForward returns null when bookmarks empty`() = runTest {
        val paginator = MutablePaginator<String> { MutableList(this.cache.pagingCore.capacity) { "item$it" } }
        paginator.cache.pagingCore.resize(capacity = 5, resize = false, silently = true)
        // Properly release to reset the iterator, then clear
        paginator.release()
        paginator.bookmarks.clear()
        // Need to recreate iterator since bookmarks changed
        // Note: this is a known limitation - modifying bookmarks externally
        // invalidates the internal iterator

        val result = paginator.jumpForward(
            recycling = true,
            silentlyLoading = true,
            silentlyResult = true
        )
        assertNull(result)
    }

    @Test
    fun `jump with already cached filled success page returns immediately`() = runTest {
        var loadCount = 0
        val paginator = MutablePaginator<String> { page ->
            loadCount++
            MutableList(this.cache.pagingCore.capacity) { "item_$it" }
        }
        paginator.cache.pagingCore.resize(capacity = 5, resize = false, silently = true)

        // First jump loads from source
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        assertEquals(1, loadCount)

        // Second jump to same page should use cache (no additional load)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        assertEquals(1, loadCount)
    }

    @Test
    fun `recyclingBookmark property controls default recycling`() = runTest {
        val paginator = createDeterministicPaginator()
        paginator.recyclingBookmark = true
        // Consume the only bookmark
        paginator.jumpForward(silentlyLoading = true, silentlyResult = true)

        // With recyclingBookmark = true, jumpForward should recycle by default
        val result = paginator.jumpForward(silentlyLoading = true, silentlyResult = true)
        assertNotNull(result)
        assertEquals(1, result!!.first.page)
    }

    @Test
    fun `multiple bookmarks with direct jump`() = runTest {
        val paginator = createDeterministicPaginator()
        // Direct jump doesn't use bookmarks, just navigates
        paginator.jump(BookmarkInt(3), silentlyLoading = true, silentlyResult = true)
        assertTrue(paginator.cache.getStateOf(3)!!.isSuccessState())
        assertEquals(3, paginator.cache.startContextPage)
        assertEquals(3, paginator.cache.endContextPage)

        paginator.jump(BookmarkInt(7), silentlyLoading = true, silentlyResult = true)
        assertTrue(paginator.cache.getStateOf(7)!!.isSuccessState())
        assertEquals(7, paginator.cache.startContextPage)
        assertEquals(7, paginator.cache.endContextPage)
    }

    // --- Smart skip-visible-bookmarks tests ---

    @Test
    fun `jumpForward skips bookmarks inside visible range`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)

        // Jump to page 1 and navigate to page 3 so snapshot covers 1..3
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = false)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = false) // page 2
        paginator.goNextPage(silentlyLoading = true, silentlyResult = false) // page 3
        assertEquals(1, paginator.cache.startContextPage)
        assertEquals(3, paginator.cache.endContextPage)

        // Now set up bookmarks. The default iterator was consumed by earlier operations,
        // so we set up bookmarks and use recycling to get a fresh iterator.
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(listOf(BookmarkInt(1), BookmarkInt(5), BookmarkInt(10)))

        // jumpForward with recycling=true will recreate the iterator (Phase 2)
        // since the old iterator is invalidated. It should skip bookmark 1 (visible)
        // and jump to 5.
        val result = paginator.jumpForward(
            recycling = true,
            silentlyLoading = true,
            silentlyResult = false
        )
        assertNotNull(result)
        assertEquals(5, result!!.first.page)
        assertTrue(result.second.isSuccessState())
    }

    @Test
    fun `jumpForward falls back to last visible bookmark when all are visible`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)

        // Set up bookmarks BEFORE navigation so bookmarkIndex stays consistent
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(listOf(BookmarkInt(1), BookmarkInt(2), BookmarkInt(3)))

        // Load pages 1..3 so snapshot covers 1..3
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = false)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = false) // page 2
        paginator.goNextPage(silentlyLoading = true, silentlyResult = false) // page 3

        // All bookmarks (1,2,3) are visible → fallback to a visible bookmark (not null)
        val result = paginator.jumpForward(
            recycling = true,
            silentlyLoading = true,
            silentlyResult = false
        )
        assertNotNull(result)
        assertTrue(result!!.first.page in 1..3)
    }

    @Test
    fun `jumpBack skips bookmarks inside visible range`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)

        // Load pages 1..3 so snapshot covers 1..3
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = false)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = false) // page 2
        paginator.goNextPage(silentlyLoading = true, silentlyResult = false) // page 3

        // Set up bookmarks: [1, 2, 10]
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(listOf(BookmarkInt(1), BookmarkInt(2), BookmarkInt(10)))

        // jumpForward with recycling — Phase 1 CME → Phase 2 fresh iterator:
        // bookmark 1 (in 1..3) → skip, bookmark 2 (in 1..3) → skip, bookmark 10 (not in 1..3) → jump to 10
        val fwdResult = paginator.jumpForward(
            recycling = true,
            silentlyLoading = true,
            silentlyResult = false
        )
        assertNotNull(fwdResult)
        assertEquals(10, fwdResult!!.first.page)
        // After jump(10), snapshot = [10], visibleRange = 10..10
        // Iterator is now at position 3 (past bookmark 10)

        // jumpBack: iterate backward from position 3:
        // candidate = BookmarkInt(10), 10 in 10..10 → skip
        // candidate = BookmarkInt(2), 2 NOT in 10..10 → jump to 2
        val result = paginator.jumpBack(silentlyLoading = true, silentlyResult = false)
        assertNotNull(result)
        assertEquals(2, result!!.first.page)
    }

    @Test
    fun `jumpBack after direct jump lands on nearest preceding bookmark`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)

        // Set up bookmarks: [1, 5, 10, 15]
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(
            listOf(BookmarkInt(1), BookmarkInt(5), BookmarkInt(10), BookmarkInt(15))
        )

        // Reproduce scenario: goNext→1, goNext→2, jump(7), goPrevious→6, jumpBack
        paginator.goNextPage(silentlyLoading = true, silentlyResult = false) // page 1
        paginator.goNextPage(silentlyLoading = true, silentlyResult = false) // page 2
        paginator.jump(BookmarkInt(7), silentlyLoading = true, silentlyResult = false)
        paginator.goPreviousPage(silentlyLoading = true, silentlyResult = false) // page 6

        // jumpBack should go to bookmark 5 (nearest bookmark before current page 6)
        val result = paginator.jumpBack(silentlyLoading = true, silentlyResult = false)
        assertNotNull(result)
        assertEquals(5, result!!.first.page)
    }

    @Test
    fun `jumpForward after direct jump lands on nearest following bookmark`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)

        // Set up bookmarks: [1, 5, 10, 15]
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(
            listOf(BookmarkInt(1), BookmarkInt(5), BookmarkInt(10), BookmarkInt(15))
        )

        // jump to page 7 — bookmarkIndex should sync to 2 (before bookmark 10)
        paginator.jump(BookmarkInt(7), silentlyLoading = true, silentlyResult = false)

        // jumpForward should go to bookmark 10 (first bookmark after page 7)
        val result = paginator.jumpForward(silentlyLoading = true, silentlyResult = false)
        assertNotNull(result)
        assertEquals(10, result!!.first.page)
    }

    @Test
    fun `jumpForward with recycling skips visible and wraps to non-visible`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)

        // Load pages 1..3 so snapshot covers 1..3
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = false)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = false) // page 2
        paginator.goNextPage(silentlyLoading = true, silentlyResult = false) // page 3

        // Set up bookmarks
        paginator.bookmarks.clear()
        paginator.bookmarks.addAll(listOf(BookmarkInt(1), BookmarkInt(10)))

        // jumpForward with recycling: bookmark 1 is visible → skip, bookmark 10 is not → jump to it
        val result = paginator.jumpForward(
            recycling = true,
            silentlyLoading = true,
            silentlyResult = false
        )
        assertNotNull(result)
        assertEquals(10, result!!.first.page)
    }
}
