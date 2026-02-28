package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import com.jamal_aliev.paginator.extension.isErrorState
import com.jamal_aliev.paginator.extension.isSuccessState
import com.jamal_aliev.paginator.extension.refreshAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartAndRefreshTest {

    // =========================================================================
    // restart
    // =========================================================================

    @Test
    fun `restart clears cache and reloads page 1`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        assertEquals(3, paginator.core.size)

        paginator.restart(silentlyLoading = true, silentlyResult = true)

        assertEquals(1, paginator.core.size)
        assertEquals(1, paginator.core.startContextPage)
        assertEquals(1, paginator.core.endContextPage)
        assertTrue(paginator.core.getStateOf(1)!!.isSuccessState())
    }

    @Test
    fun `restart reloads fresh data from source`() = runTest {
        var callCount = 0
        val paginator = MutablePaginator<String> { page ->
            callCount++
            MutableList(this.core.capacity) { "call${callCount}_item$it" }
        }
        paginator.core.resize(capacity = 3, resize = false, silently = true)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        val firstData = paginator.core.getStateOf(1)!!.data.toList()

        paginator.restart(silentlyLoading = true, silentlyResult = true)
        val secondData = paginator.core.getStateOf(1)!!.data.toList()

        // Data should be different because source returned different data
        assertTrue(firstData != secondData)
    }

    // =========================================================================
    // refresh
    // =========================================================================

    @Test
    fun `refresh reloads specified pages`() = runTest {
        var callCount = 0
        val paginator = MutablePaginator<String> { page ->
            callCount++
            MutableList(this.core.capacity) { "call${callCount}_p${page}_item$it" }
        }
        paginator.core.resize(capacity = 3, resize = false, silently = true)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        val dataBefore = paginator.core.getStateOf(1)!!.data.toList()

        paginator.refresh(
            pages = listOf(1),
            loadingSilently = true,
            finalSilently = true
        )
        val dataAfter = paginator.core.getStateOf(1)!!.data.toList()

        assertTrue(dataBefore != dataAfter) // different data from new call
    }

    @Test
    fun `refresh multiple pages in parallel`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        paginator.refresh(
            pages = listOf(1, 2, 3),
            loadingSilently = true,
            finalSilently = true
        )

        // All 3 pages should still be success states after refresh
        assertTrue(paginator.core.getStateOf(1)!!.isSuccessState())
        assertTrue(paginator.core.getStateOf(2)!!.isSuccessState())
        assertTrue(paginator.core.getStateOf(3)!!.isSuccessState())
    }

    @Test
    fun `refresh with source error produces ErrorPage`() = runTest {
        var shouldFail = false
        val paginator = MutablePaginator<String> { page ->
            if (shouldFail && page == 1) throw RuntimeException("refresh error")
            MutableList(this.core.capacity) { "p${page}_item$it" }
        }
        paginator.core.resize(capacity = 3, resize = false, silently = true)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        assertTrue(paginator.core.getStateOf(1)!!.isSuccessState())

        shouldFail = true
        paginator.refresh(
            pages = listOf(1),
            loadingSilently = true,
            finalSilently = true
        )
        assertTrue(paginator.core.getStateOf(1)!!.isErrorState())
    }

    // =========================================================================
    // refreshAll
    // =========================================================================

    @Test
    fun `refreshAll refreshes all cached pages`() = runTest {
        var generation = 0
        val paginator = MutablePaginator<String> { page ->
            generation++
            MutableList(this.core.capacity) { "gen${generation}_p${page}_$it" }
        }
        paginator.core.resize(capacity = 3, resize = false, silently = true)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        val gen = generation

        paginator.refreshAll(loadingSilently = true, finalSilently = true)

        // Both pages should have been refreshed (generation increased)
        assertTrue(generation > gen)
        assertTrue(paginator.core.getStateOf(1)!!.isSuccessState())
        assertTrue(paginator.core.getStateOf(2)!!.isSuccessState())
    }
}
