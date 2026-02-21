package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import com.jamal_aliev.paginator.exception.FinalPageExceededException
import com.jamal_aliev.paginator.extension.isErrorState
import com.jamal_aliev.paginator.extension.isSuccessState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationEdgeCasesTest {

    @Test
    fun `goNextPage without jump auto-starts at page 1`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        assertFalse(paginator.isStarted)

        val result = paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        assertTrue(paginator.isStarted)
        assertTrue(result.isSuccessState())
        assertEquals(1, result.page)
    }

    @Test
    fun `goPreviousPage without jump throws IllegalStateException`() {
        val paginator = createDeterministicPaginator()
        assertThrows(IllegalStateException::class.java) {
            runTest {
                paginator.goPreviousPage(silentlyLoading = true, silentlyResult = true)
            }
        }
    }

    @Test
    fun `goPreviousPage at page 1 throws IllegalStateException`(): Unit = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        assertThrows(IllegalStateException::class.java) {
            runTest {
                paginator.goPreviousPage(silentlyLoading = true, silentlyResult = true)
            }
        }
        Unit
    }

    @Test
    fun `goNextPage expands context window`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        assertEquals(1, paginator.startContextPage)
        assertEquals(1, paginator.endContextPage)

        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        assertEquals(1, paginator.startContextPage)
        assertEquals(2, paginator.endContextPage)

        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        assertEquals(1, paginator.startContextPage)
        assertEquals(3, paginator.endContextPage)
    }

    @Test
    fun `goPreviousPage expands context window backward`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = true)
        assertEquals(5, paginator.startContextPage)
        assertEquals(5, paginator.endContextPage)

        paginator.goPreviousPage(silentlyLoading = true, silentlyResult = true)
        assertEquals(4, paginator.startContextPage)
        assertEquals(5, paginator.endContextPage)
    }

    @Test
    fun `jump resets context to target page`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        assertEquals(1, paginator.startContextPage)
        assertEquals(3, paginator.endContextPage)

        // Jump to page 10 — context should reset
        paginator.jump(BookmarkInt(10), silentlyLoading = true, silentlyResult = true)
        assertEquals(10, paginator.startContextPage)
        assertEquals(10, paginator.endContextPage)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `jump with page 0 throws IllegalArgumentException`() = runTest {
        val paginator = createDeterministicPaginator()
        // BookmarkInt requires page >= 1, this should throw in BookmarkInt constructor
        paginator.jump(BookmarkInt(0))
        Unit
    }

    @Test
    fun `goNextPage throws FinalPageExceededException at boundary`(): Unit = runTest {
        val paginator = createDeterministicPaginator(capacity = 5, totalItems = 100)
        paginator.finalPage = 3

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // page 2
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // page 3 (filled)

        // Page 4 exceeds finalPage=3
        assertThrows(FinalPageExceededException::class.java) {
            runBlocking {
                paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
            }
        }
        Unit
    }

    @Test
    fun `source error produces ErrorPage`() = runTest {
        val paginator = MutablePaginator<String> { page ->
            if (page == 2) throw RuntimeException("network error")
            MutableList(this.capacity) { "item_$it" }
        }
        paginator.resize(capacity = 5, resize = false, silently = true)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        val errorPage = paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        assertTrue(errorPage.isErrorState())
        assertEquals(2, errorPage.page)
    }

    @Test
    fun `isStarted is false initially and true after jump`() = runTest {
        val paginator = createDeterministicPaginator()
        assertFalse(paginator.isStarted)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        assertTrue(paginator.isStarted)
    }

    @Test
    fun `isFilledSuccessState with unlimited capacity`() = runTest {
        // Use a custom source that returns data regardless of capacity
        val paginator = MutablePaginator<String> { page ->
            MutableList(10) { "item_$it" }
        }
        paginator.resize(capacity = Paginator.UNLIMITED_CAPACITY, resize = false, silently = true)
        assertTrue(paginator.isCapacityUnlimited)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        val state = paginator.getStateOf(1)!!
        assertTrue(state.isSuccessState())
        assertTrue(paginator.isFilledSuccessState(state))
    }
}
