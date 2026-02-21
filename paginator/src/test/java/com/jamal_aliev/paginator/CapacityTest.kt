package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapacityTest {

    @Test
    fun `default capacity is 20`() {
        val paginator = MutablePaginator<String> { emptyList() }
        assertEquals(Paginator.DEFAULT_CAPACITY, paginator.capacity)
        assertEquals(20, paginator.capacity)
    }

    @Test
    fun `isCapacityUnlimited when capacity is 0`() {
        val paginator = MutablePaginator<String> { emptyList() }
        paginator.resize(capacity = Paginator.UNLIMITED_CAPACITY, resize = false, silently = true)
        assertTrue(paginator.isCapacityUnlimited)
    }

    @Test
    fun `isCapacityUnlimited is false for non-zero capacity`() {
        val paginator = MutablePaginator<String> { emptyList() }
        assertFalse(paginator.isCapacityUnlimited)
    }

    @Test
    fun `isFilledSuccessState with matching capacity`() {
        val paginator = MutablePaginator<String> { emptyList() }
        paginator.resize(capacity = 3, resize = false, silently = true)

        val filledState = SuccessPage(page = 1, data = listOf("a", "b", "c"))
        assertTrue(paginator.isFilledSuccessState(filledState))

        val partialState = SuccessPage(page = 1, data = listOf("a", "b"))
        assertFalse(paginator.isFilledSuccessState(partialState))
    }

    @Test
    fun `isFilledSuccessState always true for unlimited capacity`() {
        val paginator = MutablePaginator<String> { emptyList() }
        paginator.resize(capacity = Paginator.UNLIMITED_CAPACITY, resize = false, silently = true)

        val state = SuccessPage(page = 1, data = listOf("a"))
        assertTrue(paginator.isFilledSuccessState(state))
    }

    @Test
    fun `isFilledSuccessState false for non-SuccessPage`() {
        val paginator = MutablePaginator<String> { emptyList() }
        paginator.resize(capacity = 3, resize = false, silently = true)

        assertFalse(paginator.isFilledSuccessState(null))
    }

    @Test
    fun `goNextPage reloads incomplete page (not filled)`() = runTest {
        var loadCount = 0
        val paginator = MutablePaginator<String> { page ->
            loadCount++
            // Return fewer items than capacity for page 2
            if (page == 2) MutableList(2) { "item_$it" } // capacity is 5
            else MutableList(this.capacity) { "item_$it" }
        }
        paginator.resize(capacity = 5, resize = false, silently = true)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // page 2, partial

        val loadCountAfterPage2 = loadCount

        // goNextPage again should reload page 2 since it's not "filled"
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        // The endContextPage was at page 2 which was not filled, so next should reload page 2
        assertTrue(loadCount > loadCountAfterPage2)
    }
}
