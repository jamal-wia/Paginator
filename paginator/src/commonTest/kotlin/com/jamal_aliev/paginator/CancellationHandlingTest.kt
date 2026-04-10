package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import com.jamal_aliev.paginator.extension.isSuccessState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CancellationHandlingTest {

    // =========================================================================
    // jump
    // =========================================================================

    @Test
    fun `jump cancellation restores context window and page state`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var hangOnPage = -1

        val paginator = MutablePaginator<String> { page ->
            if (page == hangOnPage) {
                gate.await() // suspend forever until canceled
                error("unreachable")
            }
            MutableList(this.core.capacity) { "p${page}_item$it" }
        }
        paginator.core.resize(capacity = 3, resize = false, silently = true)

        // Pre-populate pages 1-3
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        val startBefore = paginator.core.startContextPage
        val endBefore = paginator.core.endContextPage
        val page1Before = paginator.core.getStateOf(1)
        val page2Before = paginator.core.getStateOf(2)
        val page3Before = paginator.core.getStateOf(3)

        // Attempt jump to page 5 — will hang
        hangOnPage = 5
        val job = launch {
            paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = true)
        }

        // Let the coroutine start and reach the suspend point
        testScheduler.advanceUntilIdle()

        // Cancel the jump
        job.cancel()
        testScheduler.advanceUntilIdle()

        // Context window should be restored
        assertEquals(startBefore, paginator.core.startContextPage)
        assertEquals(endBefore, paginator.core.endContextPage)

        // Page 5 should not remain in cache
        assertNull(paginator.core.getStateOf(5))

        // Original pages should be intact
        assertTrue(paginator.core.getStateOf(1)!!.isSuccessState())
        assertTrue(paginator.core.getStateOf(2)!!.isSuccessState())
        assertTrue(paginator.core.getStateOf(3)!!.isSuccessState())
        assertEquals(page1Before!!.data, paginator.core.getStateOf(1)!!.data)
        assertEquals(page2Before!!.data, paginator.core.getStateOf(2)!!.data)
        assertEquals(page3Before!!.data, paginator.core.getStateOf(3)!!.data)
    }

    // =========================================================================
    // goNextPage
    // =========================================================================

    @Test
    fun `goNextPage cancellation restores page state`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var hangOnPage = -1

        val paginator = MutablePaginator<String> { page ->
            if (page == hangOnPage) {
                gate.await()
                error("unreachable")
            }
            MutableList(this.core.capacity) { "p${page}_item$it" }
        }
        paginator.core.resize(capacity = 3, resize = false, silently = true)

        // Pre-populate pages 1-3
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        val endBefore = paginator.core.endContextPage

        // goNextPage will try to load page 4 — will hang
        hangOnPage = 4
        val job = launch {
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        }

        testScheduler.advanceUntilIdle()
        job.cancel()
        testScheduler.advanceUntilIdle()

        // Page 4 should not remain in cache
        assertNull(paginator.core.getStateOf(4))

        // Pages 1-3 should be intact
        assertTrue(paginator.core.getStateOf(1)!!.isSuccessState())
        assertTrue(paginator.core.getStateOf(2)!!.isSuccessState())
        assertTrue(paginator.core.getStateOf(3)!!.isSuccessState())
    }

    // =========================================================================
    // goPreviousPage
    // =========================================================================

    @Test
    fun `goPreviousPage cancellation restores page state`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var hangOnPage = -1

        val paginator = MutablePaginator<String> { page ->
            if (page == hangOnPage) {
                gate.await()
                error("unreachable")
            }
            MutableList(this.core.capacity) { "p${page}_item$it" }
        }
        paginator.core.resize(capacity = 3, resize = false, silently = true)

        // Jump to page 3 directly
        paginator.jump(BookmarkInt(3), silentlyLoading = true, silentlyResult = true)

        val startBefore = paginator.core.startContextPage

        // goPreviousPage will try to load page 2 — will hang
        hangOnPage = 2
        val job = launch {
            paginator.goPreviousPage(silentlyLoading = true, silentlyResult = true)
        }

        testScheduler.advanceUntilIdle()
        job.cancel()
        testScheduler.advanceUntilIdle()

        // Page 2 should not remain in cache
        assertNull(paginator.core.getStateOf(2))

        // Page 3 should be intact
        assertTrue(paginator.core.getStateOf(3)!!.isSuccessState())
    }

    // =========================================================================
    // restart
    // =========================================================================

    @Test
    fun `restart cancellation restores context window and page state`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var hangOnRestart = false

        val paginator = MutablePaginator<String> { page ->
            if (hangOnRestart && page == 1) {
                gate.await()
                error("unreachable")
            }
            MutableList(this.core.capacity) { "p${page}_item$it" }
        }
        paginator.core.resize(capacity = 3, resize = false, silently = true)

        // Pre-populate pages 1-3
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        val startBefore = paginator.core.startContextPage
        val endBefore = paginator.core.endContextPage
        val page1DataBefore = paginator.core.getStateOf(1)!!.data.toList()

        // restart will hang on page 1 reload
        hangOnRestart = true
        val job = launch {
            paginator.restart(silentlyLoading = true, silentlyResult = true)
        }

        testScheduler.advanceUntilIdle()
        job.cancel()
        testScheduler.advanceUntilIdle()

        // Context window should be restored
        assertEquals(startBefore, paginator.core.startContextPage)
        assertEquals(endBefore, paginator.core.endContextPage)

        // Page 1 should still have its original data
        assertTrue(paginator.core.getStateOf(1)!!.isSuccessState())
        assertEquals(page1DataBefore, paginator.core.getStateOf(1)!!.data)
    }

    // =========================================================================
    // refresh
    // =========================================================================

    @Test
    fun `refresh cancellation restores all page states`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var hangOnRefresh = false

        val paginator = MutablePaginator<String> { page ->
            if (hangOnRefresh) {
                gate.await()
                error("unreachable")
            }
            MutableList(this.core.capacity) { "p${page}_item$it" }
        }
        paginator.core.resize(capacity = 3, resize = false, silently = true)

        // Pre-populate pages 1-3
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        val page1DataBefore = paginator.core.getStateOf(1)!!.data.toList()
        val page2DataBefore = paginator.core.getStateOf(2)!!.data.toList()

        // refresh pages 1 and 2 — will hang during reload
        hangOnRefresh = true
        val job = launch {
            paginator.refresh(
                pages = listOf(1, 2),
                loadingSilently = true,
                finalSilently = true,
            )
        }

        testScheduler.advanceUntilIdle()
        job.cancel()
        testScheduler.advanceUntilIdle()

        // Pages 1 and 2 should be restored to their original success states
        assertTrue(paginator.core.getStateOf(1)!!.isSuccessState())
        assertTrue(paginator.core.getStateOf(2)!!.isSuccessState())
        assertEquals(page1DataBefore, paginator.core.getStateOf(1)!!.data)
        assertEquals(page2DataBefore, paginator.core.getStateOf(2)!!.data)

        // Page 3 should be untouched
        assertTrue(paginator.core.getStateOf(3)!!.isSuccessState())
    }

    // =========================================================================
    // CancellationException propagation
    // =========================================================================

    @Test
    fun `cancelled jump completes job with cancellation`() = runTest {
        val gate = CompletableDeferred<Unit>()

        val paginator = MutablePaginator<String> { page ->
            if (page == 5) {
                gate.await()
                error("unreachable")
            }
            MutableList(this.core.capacity) { "p${page}_item$it" }
        }
        paginator.core.resize(capacity = 3, resize = false, silently = true)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        val job = launch {
            paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = true)
        }

        testScheduler.advanceUntilIdle()
        job.cancel()
        testScheduler.advanceUntilIdle()

        assertTrue(job.isCancelled)
    }
}
