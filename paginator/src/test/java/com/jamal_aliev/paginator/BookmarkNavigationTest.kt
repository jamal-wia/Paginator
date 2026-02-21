package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import com.jamal_aliev.paginator.extension.isSuccessState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkNavigationTest {

    @Test
    fun `BookmarkInt requires page gte 1`() {
        val bookmark = BookmarkInt(page = 1)
        assertEquals(1, bookmark.page)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `BookmarkInt rejects page 0`() {
        BookmarkInt(page = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `BookmarkInt rejects negative page`() {
        BookmarkInt(page = -1)
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
        val paginator = MutablePaginator<String> { MutableList(this.capacity) { "item$it" } }
        paginator.resize(capacity = 5, resize = false, silently = true)
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
            MutableList(this.capacity) { "item_$it" }
        }
        paginator.resize(capacity = 5, resize = false, silently = true)

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
        assertTrue(paginator.getStateOf(3)!!.isSuccessState())
        assertEquals(3, paginator.startContextPage)
        assertEquals(3, paginator.endContextPage)

        paginator.jump(BookmarkInt(7), silentlyLoading = true, silentlyResult = true)
        assertTrue(paginator.getStateOf(7)!!.isSuccessState())
        assertEquals(7, paginator.startContextPage)
        assertEquals(7, paginator.endContextPage)
    }
}
