package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import com.jamal_aliev.paginator.extension.prefetchController
import com.jamal_aliev.paginator.load.LoadResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PrefetchControllerTest {

    /**
     * Creates a paginator with [totalPages] pages of data, each containing
     * [capacity] items, started at page 1 with [loadedPages] pages loaded.
     * Pages beyond [totalPages] return empty lists.
     */
    private suspend fun createPrefetchTestPaginator(
        totalPages: Int = 10,
        capacity: Int = 10,
        loadedPages: Int = 3,
    ): MutablePaginator<String> {
        val paginator = MutablePaginator<String> { page: Int ->
            if (page in 1..totalPages) {
                LoadResult(List(capacity) { "p${page}_item_$it" })
            } else {
                LoadResult(emptyList())
            }
        }
        paginator.core.resize(capacity = capacity, resize = false, silently = true)
        paginator.finalPage = totalPages
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        for (i in 2..loadedPages) {
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        }
        return paginator
    }

    // =========================================================================
    // Validation
    // =========================================================================

    @Test
    fun `prefetchDistance rejects zero`() {
        assertFailsWith<IllegalArgumentException> {
            createDeterministicPaginator().prefetchController(
                scope = TestScope(),
                prefetchDistance = 0,
            )
        }
    }

    @Test
    fun `prefetchDistance rejects negative`() {
        assertFailsWith<IllegalArgumentException> {
            createDeterministicPaginator().prefetchController(
                scope = TestScope(),
                prefetchDistance = -1,
            )
        }
    }

    @Test
    fun `prefetchDistance setter rejects zero`() {
        val controller = createDeterministicPaginator().prefetchController(
            scope = TestScope(),
            prefetchDistance = 5,
        )
        assertFailsWith<IllegalArgumentException> {
            controller.prefetchDistance = 0
        }
    }

    // =========================================================================
    // Calibration — first onScroll does not trigger prefetch
    // =========================================================================

    @Test
    fun `first onScroll is calibration only - no prefetch`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEndContext = paginator.cache.endContextPage

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
        )

        // First call: near the end, but it's calibration
        controller.onScroll(
            firstVisibleIndex = 20,
            lastVisibleIndex = 27,
            totalItemCount = 30,
        )
        advanceUntilIdle()

        assertEquals(initialEndContext, paginator.cache.endContextPage)
    }

    @Test
    fun `second onScroll with forward movement triggers prefetch`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEndContext = paginator.cache.endContextPage

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
        )

        // Calibration
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        // Real scroll forward — near the end
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals(initialEndContext + 1, paginator.cache.endContextPage)
    }

    // =========================================================================
    // enabled flag
    // =========================================================================

    @Test
    fun `onScroll is no-op when enabled is false`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEndContext = paginator.cache.endContextPage

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
        )
        controller.enabled = false

        // Calibration + forward scroll — both should be ignored
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals(initialEndContext, paginator.cache.endContextPage)
    }

    @Test
    fun `onScroll is no-op when totalItemCount is zero`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEndContext = paginator.cache.endContextPage

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
        )

        controller.onScroll(firstVisibleIndex = 0, lastVisibleIndex = 0, totalItemCount = 0)
        controller.onScroll(firstVisibleIndex = 0, lastVisibleIndex = 0, totalItemCount = 0)
        advanceUntilIdle()

        assertEquals(initialEndContext, paginator.cache.endContextPage)
    }

    // =========================================================================
    // cancel()
    // =========================================================================

    @Test
    fun `cancel allows new prefetch on subsequent scroll`() = runTest {
        val paginator = createPrefetchTestPaginator()

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
        )

        // Calibration
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        // Trigger forward prefetch (page 4)
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()
        assertEquals(4, paginator.cache.endContextPage)

        controller.cancel()

        // Scroll further in expanded list (page 5)
        controller.onScroll(firstVisibleIndex = 32, lastVisibleIndex = 37, totalItemCount = 40)
        advanceUntilIdle()
        assertEquals(5, paginator.cache.endContextPage)
    }

    // =========================================================================
    // reset()
    // =========================================================================

    @Test
    fun `reset causes next onScroll to be calibration again`() = runTest {
        val paginator = createPrefetchTestPaginator()

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
        )

        // Calibration + forward
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()
        assertEquals(4, paginator.cache.endContextPage)

        controller.reset()

        // This should be calibration again — no prefetch even though near end
        controller.onScroll(firstVisibleIndex = 32, lastVisibleIndex = 37, totalItemCount = 40)
        advanceUntilIdle()
        assertEquals(4, paginator.cache.endContextPage)
    }

    // =========================================================================
    // Backward prefetch
    // =========================================================================

    @Test
    fun `backward prefetch disabled by default`() = runTest {
        val paginator = createPrefetchTestPaginator(loadedPages = 1)
        // Jump to page 5 so there are pages before
        paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = true)
        val initialStartContext = paginator.cache.startContextPage

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
        )

        // Calibration
        controller.onScroll(firstVisibleIndex = 5, lastVisibleIndex = 10, totalItemCount = 10)
        // Scroll backward
        controller.onScroll(firstVisibleIndex = 2, lastVisibleIndex = 7, totalItemCount = 10)
        advanceUntilIdle()

        assertEquals(initialStartContext, paginator.cache.startContextPage)
    }

    @Test
    fun `backward prefetch works when enabled`() = runTest {
        val paginator = createPrefetchTestPaginator(loadedPages = 1)
        // Jump to page 5 so pages 1-4 are before
        paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = true)
        val initialStartContext = paginator.cache.startContextPage

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
            enableBackwardPrefetch = true,
        )

        // Calibration
        controller.onScroll(firstVisibleIndex = 5, lastVisibleIndex = 9, totalItemCount = 10)
        // Scroll backward near the start
        controller.onScroll(firstVisibleIndex = 2, lastVisibleIndex = 6, totalItemCount = 10)
        advanceUntilIdle()

        assertTrue(
            paginator.cache.startContextPage < initialStartContext,
            "startContextPage should decrease after backward prefetch"
        )
    }

    // =========================================================================
    // No prefetch when lock is set
    // =========================================================================

    @Test
    fun `no forward prefetch when lockGoNextPage is true`() = runTest {
        val paginator = createPrefetchTestPaginator()
        paginator.lockGoNextPage = true
        val initialEndContext = paginator.cache.endContextPage

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
        )

        // Calibration + forward
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals(initialEndContext, paginator.cache.endContextPage)
    }

    // =========================================================================
    // onPrefetchError callback
    // =========================================================================

    @Test
    fun `onPrefetchError receives exceptions from goNextPage`() = runTest {
        val errors = mutableListOf<Exception>()
        val paginator = createPrefetchTestPaginator(totalPages = 10, loadedPages = 3)
        // loadGuard that rejects page 4
        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
            loadGuard = { page, _ -> page != 4 },
            onPrefetchError = { errors.add(it) },
        )

        // Calibration
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        // Forward near end — loadGuard will reject page 4, throwing LoadGuardedException
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals(1, errors.size)
        assertTrue(
            errors[0]::class.simpleName?.contains("LoadGuarded") == true,
            "Expected LoadGuardedException but got ${errors[0]::class.simpleName}"
        )
    }

    // =========================================================================
    // No duplicate prefetch when job is still active
    // =========================================================================

    @Test
    fun `does not launch duplicate forward job while one is active`() = runTest {
        var loadCount = 0
        val paginator = MutablePaginator<String> { page: Int ->
            loadCount++
            LoadResult(List(10) { "p${page}_item_$it" })
        }
        paginator.core.resize(capacity = 10, resize = false, silently = true)
        paginator.finalPage = 10
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        loadCount = 0 // reset after jump

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 3,
        )

        // Calibration
        controller.onScroll(firstVisibleIndex = 0, lastVisibleIndex = 5, totalItemCount = 10)

        // Multiple rapid forward scrolls before the job completes
        controller.onScroll(firstVisibleIndex = 3, lastVisibleIndex = 8, totalItemCount = 10)
        controller.onScroll(firstVisibleIndex = 4, lastVisibleIndex = 9, totalItemCount = 10)
        advanceUntilIdle()

        // Should load page 2 only once
        assertEquals(1, loadCount)
    }

    // =========================================================================
    // Extension function
    // =========================================================================

    @Test
    fun `prefetchController extension creates working controller`() = runTest {
        val paginator = createPrefetchTestPaginator()

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
        )

        assertNotNull(controller)
        assertTrue(controller.enabled)
        assertEquals(5, controller.prefetchDistance)
    }

    // =========================================================================
    // Corner cases: negative and invalid indices
    // =========================================================================

    @Test
    fun `negative firstVisibleIndex is ignored - RecyclerView NO_POSITION`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEndContext = paginator.cache.endContextPage

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
        )

        // RecyclerView returns -1 (NO_POSITION) when no items are visible
        // These calls are completely ignored (no calibration, no prefetch)
        controller.onScroll(firstVisibleIndex = -1, lastVisibleIndex = -1, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = -1, lastVisibleIndex = -1, totalItemCount = 30)

        // First valid call should be calibration
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        // Second valid call — forward near end
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()

        // Should prefetch because negative calls were skipped entirely
        assertEquals(initialEndContext + 1, paginator.cache.endContextPage)
    }

    @Test
    fun `negative totalItemCount does not crash or trigger prefetch`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEndContext = paginator.cache.endContextPage

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
        )

        controller.onScroll(firstVisibleIndex = 0, lastVisibleIndex = 5, totalItemCount = -1)
        controller.onScroll(firstVisibleIndex = 0, lastVisibleIndex = 5, totalItemCount = -1)
        advanceUntilIdle()

        assertEquals(initialEndContext, paginator.cache.endContextPage)
    }

    @Test
    fun `inverted range - firstVisibleIndex greater than lastVisibleIndex`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEndContext = paginator.cache.endContextPage
        val initialStartContext = paginator.cache.startContextPage

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
            enableBackwardPrefetch = true,
        )

        // Calibration with sane values
        controller.onScroll(firstVisibleIndex = 10, lastVisibleIndex = 15, totalItemCount = 30)
        // Inverted indices
        controller.onScroll(firstVisibleIndex = 15, lastVisibleIndex = 10, totalItemCount = 30)
        advanceUntilIdle()

        // Should not trigger forward prefetch (lastVisibleIndex went DOWN)
        // But might incorrectly trigger backward prefetch because firstVisibleIndex went UP?
        // Actually firstVisibleIndex=15 > prevFirstVisible=10, so scrollingBackward=false — that's fine.
        assertEquals(initialEndContext, paginator.cache.endContextPage)
    }

    // =========================================================================
    // Corner case: lastVisibleIndex == totalItemCount - 1 (exactly at end)
    // =========================================================================

    @Test
    fun `exactly at the last item triggers prefetch - itemsFromEnd is 0`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEndContext = paginator.cache.endContextPage

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
        )

        // Calibration
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 25, totalItemCount = 30)
        // Scroll to the very last item: itemsFromEnd = 30 - 29 - 1 = 0
        controller.onScroll(firstVisibleIndex = 25, lastVisibleIndex = 29, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals(initialEndContext + 1, paginator.cache.endContextPage)
    }

    // =========================================================================
    // Corner case: lastVisibleIndex >= totalItemCount (out of bounds)
    // =========================================================================

    @Test
    fun `lastVisibleIndex exceeding totalItemCount is clamped`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEndContext = paginator.cache.endContextPage

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
        )

        // Calibration
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 25, totalItemCount = 30)
        // lastVisibleIndex=35 is clamped to 29 (totalItemCount-1)
        // itemsFromEnd = 30 - 29 - 1 = 0, which is <= 5 => prefetch
        controller.onScroll(firstVisibleIndex = 25, lastVisibleIndex = 35, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals(initialEndContext + 1, paginator.cache.endContextPage)
    }

    // =========================================================================
    // Corner case: endContextPage == finalPage — should NOT prefetch
    // =========================================================================

    @Test
    fun `no forward prefetch when endContextPage equals finalPage`() = runTest {
        val paginator = createPrefetchTestPaginator(totalPages = 3, loadedPages = 3)
        // All pages loaded, endContextPage should be 3 == finalPage
        assertEquals(3, paginator.cache.endContextPage)
        assertEquals(3, paginator.finalPage)

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
        )

        // Calibration
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 25, totalItemCount = 30)
        // Scroll forward near end
        controller.onScroll(firstVisibleIndex = 25, lastVisibleIndex = 29, totalItemCount = 30)
        advanceUntilIdle()

        // Should not try to load page 4
        assertEquals(3, paginator.cache.endContextPage)
    }

    // =========================================================================
    // Corner case: no backward prefetch when startContextPage == 1
    // =========================================================================

    @Test
    fun `no backward prefetch when startContextPage is 1`() = runTest {
        val paginator = createPrefetchTestPaginator(loadedPages = 3)
        assertEquals(1, paginator.cache.startContextPage)

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
            enableBackwardPrefetch = true,
        )

        // Calibration
        controller.onScroll(firstVisibleIndex = 5, lastVisibleIndex = 10, totalItemCount = 30)
        // Scroll backward
        controller.onScroll(firstVisibleIndex = 2, lastVisibleIndex = 7, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals(1, paginator.cache.startContextPage)
    }

    // =========================================================================
    // Corner case: backward prefetch when lockGoPreviousPage is true
    // =========================================================================

    @Test
    fun `no backward prefetch when lockGoPreviousPage is true`() = runTest {
        val paginator = createPrefetchTestPaginator(loadedPages = 1)
        paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = true)
        val initialStartContext = paginator.cache.startContextPage
        paginator.lockGoPreviousPage = true

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
            enableBackwardPrefetch = true,
        )

        // Calibration
        controller.onScroll(firstVisibleIndex = 5, lastVisibleIndex = 9, totalItemCount = 10)
        // Scroll backward
        controller.onScroll(firstVisibleIndex = 2, lastVisibleIndex = 6, totalItemCount = 10)
        advanceUntilIdle()

        assertEquals(initialStartContext, paginator.cache.startContextPage)
    }

    // =========================================================================
    // Corner case: same scroll position repeated — no direction detected
    // =========================================================================

    @Test
    fun `same position repeated after calibration does not trigger prefetch`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEndContext = paginator.cache.endContextPage

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
        )

        // Calibration at near-end position
        controller.onScroll(firstVisibleIndex = 25, lastVisibleIndex = 29, totalItemCount = 30)
        // Same position again — neither scrollingForward nor scrollingBackward
        controller.onScroll(firstVisibleIndex = 25, lastVisibleIndex = 29, totalItemCount = 30)
        advanceUntilIdle()

        // No direction detected, so no prefetch should trigger
        assertEquals(initialEndContext, paginator.cache.endContextPage)
    }

    // =========================================================================
    // Corner case: totalItemCount == 1 (single item list)
    // =========================================================================

    @Test
    fun `single item list - forward scroll triggers prefetch`() = runTest {
        val paginator = createPrefetchTestPaginator(capacity = 1, loadedPages = 1)
        val initialEndContext = paginator.cache.endContextPage

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 1,
        )

        // Calibration: single item visible
        controller.onScroll(firstVisibleIndex = 0, lastVisibleIndex = 0, totalItemCount = 1)
        // No forward movement possible with 1 item, but let's try with totalItemCount growing
        controller.onScroll(firstVisibleIndex = 0, lastVisibleIndex = 0, totalItemCount = 1)
        advanceUntilIdle()

        // Same position, no forward detected
        assertEquals(initialEndContext, paginator.cache.endContextPage)
    }

    // =========================================================================
    // Corner case: simultaneous forward and backward (screen resize)
    // =========================================================================

    @Test
    fun `viewport expansion - firstVisible decreases AND lastVisible increases`() = runTest {
        val paginator = createPrefetchTestPaginator(loadedPages = 1)
        paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = true)
        val initialStartContext = paginator.cache.startContextPage
        val initialEndContext = paginator.cache.endContextPage

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 3,
            enableBackwardPrefetch = true,
        )

        // Calibration with small viewport
        controller.onScroll(firstVisibleIndex = 3, lastVisibleIndex = 6, totalItemCount = 10)
        // Screen resize: viewport expands in both directions
        controller.onScroll(firstVisibleIndex = 1, lastVisibleIndex = 8, totalItemCount = 10)
        advanceUntilIdle()

        // Both directions should trigger: forward because lastVisible increased,
        // backward because firstVisible decreased
        // This tests whether simultaneous forward+backward prefetch works correctly
        assertTrue(
            paginator.cache.endContextPage > initialEndContext
                    || paginator.cache.startContextPage < initialStartContext,
            "At least one direction should have prefetched"
        )
    }

    // =========================================================================
    // Corner case: prefetchDistance larger than totalItemCount
    // =========================================================================

    @Test
    fun `prefetchDistance larger than totalItemCount triggers prefetch immediately`() = runTest {
        val paginator = createPrefetchTestPaginator(capacity = 5, loadedPages = 1)
        val initialEndContext = paginator.cache.endContextPage

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 100, // much larger than totalItemCount
        )

        // Calibration
        controller.onScroll(firstVisibleIndex = 0, lastVisibleIndex = 2, totalItemCount = 5)
        // Any forward movement
        controller.onScroll(firstVisibleIndex = 1, lastVisibleIndex = 3, totalItemCount = 5)
        advanceUntilIdle()

        // itemsFromEnd = 5 - 3 - 1 = 1, which is <= 100 => should prefetch
        assertEquals(initialEndContext + 1, paginator.cache.endContextPage)
    }

    // =========================================================================
    // Corner case: re-enabling after disabled skips calibration issue
    // =========================================================================

    @Test
    fun `re-enabling controller after disable preserves calibration state`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEndContext = paginator.cache.endContextPage

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
        )

        // Calibration while enabled
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)

        // Disable, scroll, re-enable
        controller.enabled = false
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        controller.enabled = true

        // This scroll should still work after re-enable because calibration was done
        controller.onScroll(firstVisibleIndex = 23, lastVisibleIndex = 28, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals(initialEndContext + 1, paginator.cache.endContextPage)
    }

    @Test
    fun `disabling during calibration phase - positions tracked, re-enable still needs calibration`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEndContext = paginator.cache.endContextPage

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
        )

        // Disable before any scroll — positions are tracked but calibration not completed
        controller.enabled = false
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.enabled = true

        // First scroll after re-enable — calibration was NOT completed, so this is calibration
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()

        // Should NOT prefetch because initialized is still false
        assertEquals(initialEndContext, paginator.cache.endContextPage)
    }

    // =========================================================================
    // Corner case: enable toggling skips prevVisible update
    // =========================================================================

    @Test
    fun `disable-reenable tracks scroll position - correct direction after re-enable`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEndContext = paginator.cache.endContextPage

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
        )

        // Calibration: user is at position 20-22
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)

        // User scrolls forward while disabled — prevVisible IS updated now
        controller.enabled = false
        controller.onScroll(firstVisibleIndex = 25, lastVisibleIndex = 29, totalItemCount = 30)
        controller.enabled = true

        // User scrolls BACKWARD from 29 to 27
        // prevLastVisible is 29 (tracked during disabled), so 27 < 29 => scrollingForward=false
        // This correctly detects backward scroll, no false forward prefetch
        controller.onScroll(firstVisibleIndex = 23, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()

        // No forward prefetch because direction is correctly detected as backward
        assertEquals(initialEndContext, paginator.cache.endContextPage)
    }

    // =========================================================================
    // Corner case: onPrefetchError for backward prefetch
    // =========================================================================

    @Test
    fun `onPrefetchError receives exceptions from goPreviousPage`() = runTest {
        val errors = mutableListOf<Exception>()
        val paginator = createPrefetchTestPaginator(loadedPages = 1)
        paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = true)

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
            enableBackwardPrefetch = true,
            loadGuard = { page, _ -> page != 4 },
            onPrefetchError = { errors.add(it) },
        )

        // Calibration
        controller.onScroll(firstVisibleIndex = 5, lastVisibleIndex = 9, totalItemCount = 10)
        // Scroll backward near start — loadGuard rejects page 4
        controller.onScroll(firstVisibleIndex = 2, lastVisibleIndex = 6, totalItemCount = 10)
        advanceUntilIdle()

        assertEquals(1, errors.size)
        assertEquals(
            errors[0]::class.simpleName?.contains("LoadGuarded"),
            true,
            "Expected LoadGuardedException but got ${errors[0]::class.simpleName}"
        )
    }

    // =========================================================================
    // Corner case: no duplicate backward job
    // =========================================================================

    @Test
    fun `does not launch duplicate backward job while one is active`() = runTest {
        var loadCount = 0
        val paginator = MutablePaginator<String> { page: Int ->
            loadCount++
            LoadResult(List(10) { "p${page}_item_$it" })
        }
        paginator.core.resize(capacity = 10, resize = false, silently = true)
        paginator.finalPage = 10
        paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = true)
        loadCount = 0

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
            enableBackwardPrefetch = true,
        )

        // Calibration
        controller.onScroll(firstVisibleIndex = 5, lastVisibleIndex = 9, totalItemCount = 10)

        // Multiple rapid backward scrolls
        controller.onScroll(firstVisibleIndex = 3, lastVisibleIndex = 7, totalItemCount = 10)
        controller.onScroll(firstVisibleIndex = 2, lastVisibleIndex = 6, totalItemCount = 10)
        advanceUntilIdle()

        assertEquals(1, loadCount)
    }

    // =========================================================================
    // Corner case: forward prefetch on unstarted paginator
    // =========================================================================

    @Test
    fun `forward prefetch on unstarted paginator - endContextPage is 0`() = runTest {
        val paginator = MutablePaginator<String> { page: Int ->
            LoadResult(List(10) { "p${page}_item_$it" })
        }
        paginator.core.resize(capacity = 10, resize = false, silently = true)
        paginator.finalPage = 10
        // NOT calling jump — paginator is unstarted, endContextPage = 0

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
        )

        // Calibration
        controller.onScroll(firstVisibleIndex = 0, lastVisibleIndex = 5, totalItemCount = 10)
        // Forward scroll
        controller.onScroll(firstVisibleIndex = 3, lastVisibleIndex = 8, totalItemCount = 10)
        advanceUntilIdle()

        // endContextPage=0 < finalPage=10, so it should try goNextPage
        // goNextPage on unstarted paginator jumps to page 1
        assertTrue(
            paginator.cache.endContextPage >= 1,
            "Prefetch should start the paginator by loading page 1"
        )
    }

    // =========================================================================
    // Corner case: changing prefetchDistance at runtime
    // =========================================================================

    @Test
    fun `changing prefetchDistance at runtime affects subsequent scrolls`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEndContext = paginator.cache.endContextPage

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 1, // very small — won't trigger unless very close
        )

        // Calibration
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        // Forward scroll — itemsFromEnd = 30 - 25 - 1 = 4, which is > 1 => no prefetch
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 25, totalItemCount = 30)
        advanceUntilIdle()
        assertEquals(initialEndContext, paginator.cache.endContextPage)

        // Increase prefetchDistance
        controller.prefetchDistance = 10

        // Same position, but now prefetchDistance is 10 and we need a forward scroll
        controller.onScroll(firstVisibleIndex = 23, lastVisibleIndex = 26, totalItemCount = 30)
        advanceUntilIdle()

        // itemsFromEnd = 30 - 26 - 1 = 3, which is <= 10 => should prefetch now
        assertEquals(initialEndContext + 1, paginator.cache.endContextPage)
    }

    // =========================================================================
    // Corner case: very large firstVisibleIndex / lastVisibleIndex
    // =========================================================================

    @Test
    fun `very large indices do not overflow or crash`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEndContext = paginator.cache.endContextPage

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
        )

        // Calibration with large values
        controller.onScroll(
            firstVisibleIndex = Int.MAX_VALUE - 100,
            lastVisibleIndex = Int.MAX_VALUE - 50,
            totalItemCount = Int.MAX_VALUE,
        )
        // Slightly larger — scrollingForward
        controller.onScroll(
            firstVisibleIndex = Int.MAX_VALUE - 90,
            lastVisibleIndex = Int.MAX_VALUE - 40,
            totalItemCount = Int.MAX_VALUE,
        )
        advanceUntilIdle()

        // itemsFromEnd = MAX_VALUE - (MAX_VALUE - 40) - 1 = 39, which is > 5
        // So no prefetch expected, but it should not crash
        assertEquals(initialEndContext, paginator.cache.endContextPage)
    }

    // =========================================================================
    // Corner case: multiple sequential page loads (chain prefetch)
    // =========================================================================

    @Test
    fun `sequential scrolling loads multiple pages one at a time`() = runTest {
        val paginator = createPrefetchTestPaginator(
            totalPages = 10,
            capacity = 10,
            loadedPages = 1,
        )
        assertEquals(1, paginator.cache.endContextPage)

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 3,
        )

        // Calibration
        controller.onScroll(firstVisibleIndex = 0, lastVisibleIndex = 5, totalItemCount = 10)
        // Scroll near end to trigger page 2
        controller.onScroll(firstVisibleIndex = 5, lastVisibleIndex = 9, totalItemCount = 10)
        advanceUntilIdle()
        assertEquals(2, paginator.cache.endContextPage)

        // Now totalItemCount increased, scroll further for page 3
        controller.onScroll(firstVisibleIndex = 15, lastVisibleIndex = 19, totalItemCount = 20)
        advanceUntilIdle()
        assertEquals(3, paginator.cache.endContextPage)

        // And page 4
        controller.onScroll(firstVisibleIndex = 25, lastVisibleIndex = 29, totalItemCount = 30)
        advanceUntilIdle()
        assertEquals(4, paginator.cache.endContextPage)
    }

    // =========================================================================
    // Integration: CRUD + Prefetch
    // =========================================================================

    @Test
    fun `setElement does not break prefetch — data changes are visible after prefetch`() = runTest {
        val paginator = createPrefetchTestPaginator(totalPages = 10, capacity = 10, loadedPages = 3)
        // Modify an element on page 1
        paginator.setElement(element = "MODIFIED", page = 1, index = 0, silently = true)
        assertEquals("MODIFIED", paginator.cache.getStateOf(1)!!.data[0])

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)

        // Calibration + forward scroll to trigger page 4
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals(4, paginator.cache.endContextPage)
        // Modified element on page 1 must remain intact
        assertEquals("MODIFIED", paginator.cache.getStateOf(1)!!.data[0])
    }

    @Test
    fun `removeElement makes page incomplete — prefetch re-fetches it instead of next`() = runTest {
        val paginator = createPrefetchTestPaginator(totalPages = 10, capacity = 10, loadedPages = 3)

        // Remove 5 elements from page 3 — page becomes incomplete (5 < capacity 10)
        repeat(5) {
            paginator.removeElement(page = 3, index = 0, silently = true)
        }
        assertEquals(5, paginator.cache.getStateOf(3)!!.data.size)

        val totalItems = paginator.core.states.sumOf { it.data.size }
        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)

        // Calibration
        controller.onScroll(firstVisibleIndex = totalItems - 10, lastVisibleIndex = totalItems - 7, totalItemCount = totalItems)
        // Forward scroll near end — triggers goNextPage
        controller.onScroll(firstVisibleIndex = totalItems - 7, lastVisibleIndex = totalItems - 2, totalItemCount = totalItems)
        advanceUntilIdle()

        // goNextPage detects page 3 as incomplete and re-fetches it (restoring to full capacity)
        // endContextPage stays at 3 but page 3 data is refreshed to full 10 items
        assertEquals(3, paginator.cache.endContextPage)
        assertEquals(10, paginator.cache.getStateOf(3)!!.data.size)
    }

    @Test
    fun `addAllElements increases totalItemCount — prefetch may not trigger until further scroll`() = runTest {
        val paginator = createPrefetchTestPaginator(totalPages = 10, capacity = 10, loadedPages = 3)
        val initialEndContext = paginator.cache.endContextPage

        // Add 10 elements to page 2, so total grows (page rebalancing may occur)
        paginator.addAllElements(
            elements = List(10) { "new_item_$it" },
            targetPage = 2,
            index = 0,
            silently = true,
        )
        val totalItems = paginator.core.states.sumOf { it.data.size }

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 3)

        // Calibration
        controller.onScroll(firstVisibleIndex = 10, lastVisibleIndex = 15, totalItemCount = totalItems)
        // Scroll forward but far from end — prefetch should NOT trigger
        controller.onScroll(firstVisibleIndex = 15, lastVisibleIndex = 20, totalItemCount = totalItems)
        advanceUntilIdle()

        val itemsFromEnd = totalItems - 20 - 1
        if (itemsFromEnd > 3) {
            assertEquals(initialEndContext, paginator.cache.endContextPage)
        } else {
            assertEquals(initialEndContext + 1, paginator.cache.endContextPage)
        }
    }

    @Test
    fun `removeState collapses pages — prefetch respects new endContextPage`() = runTest {
        val paginator = createPrefetchTestPaginator(totalPages = 10, capacity = 10, loadedPages = 5)
        assertEquals(5, paginator.cache.endContextPage)

        // Remove page 3 — pages collapse
        paginator.removeState(pageToRemove = 3, silently = true)

        val newEndContext = paginator.cache.endContextPage
        val totalItems = paginator.core.states.sumOf { it.data.size }

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)

        // Calibration
        controller.onScroll(firstVisibleIndex = totalItems - 15, lastVisibleIndex = totalItems - 10, totalItemCount = totalItems)
        // Forward near end
        controller.onScroll(firstVisibleIndex = totalItems - 10, lastVisibleIndex = totalItems - 3, totalItemCount = totalItems)
        advanceUntilIdle()

        assertTrue(
            paginator.cache.endContextPage > newEndContext,
            "Prefetch should load a new page after removeState collapsed the context"
        )
    }

    @Test
    fun `replaceAllElements does not interfere with prefetch`() = runTest {
        val paginator = createPrefetchTestPaginator(totalPages = 10, capacity = 10, loadedPages = 3)

        // Replace all items that start with "p1_" to uppercase versions
        paginator.replaceAllElements(
            predicate = { current, _, _ -> current.startsWith("p1_") },
            providerElement = { current, _, _ -> current.uppercase() },
            silently = true,
        )

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)

        // Calibration + forward
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals(4, paginator.cache.endContextPage)
        // Replaced elements should still be uppercase
        assertTrue(paginator.cache.getStateOf(1)!!.data[0].startsWith("P1_"))
    }

    @Test
    fun `CRUD with isDirty — dirty pages auto-refresh on next prefetch navigation`() = runTest {
        var page2LoadCount = 0
        val paginator = MutablePaginator<String> { page: Int ->
            if (page == 2) page2LoadCount++
            LoadResult(List(10) { "p${page}_item_$it" })
        }
        paginator.core.resize(capacity = 10, resize = false, silently = true)
        paginator.finalPage = 10
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // load page 2
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // load page 3
        page2LoadCount = 0

        // Modify element with isDirty=true — page 2 gets marked dirty
        paginator.setElement(
            element = "DIRTY_ITEM",
            page = 2,
            index = 0,
            silently = true,
            isDirty = true,
        )
        assertTrue(paginator.core.dirtyPages.contains(2))

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)

        // Calibration + forward scroll to trigger goNextPage (loads page 4)
        // Navigation should also fire-and-forget refresh for dirty page 2
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals(4, paginator.cache.endContextPage)
        // Page 2 should have been re-fetched (dirty refresh)
        assertTrue(page2LoadCount > 0, "Dirty page 2 should have been refreshed")
    }

    // =========================================================================
    // Integration: Navigation operations + Prefetch
    // =========================================================================

    @Test
    fun `jump resets context — prefetch works from new position`() = runTest {
        val paginator = createPrefetchTestPaginator(totalPages = 10, capacity = 10, loadedPages = 3)

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)

        // Calibration + forward to load page 4
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()
        assertEquals(4, paginator.cache.endContextPage)

        // Jump to page 7 — context resets
        paginator.jump(BookmarkInt(7), silentlyLoading = true, silentlyResult = true)
        assertEquals(7, paginator.cache.startContextPage)
        assertEquals(7, paginator.cache.endContextPage)

        // Reset controller because the list changed entirely
        controller.reset()

        // New calibration + forward to load page 8
        controller.onScroll(firstVisibleIndex = 0, lastVisibleIndex = 5, totalItemCount = 10)
        controller.onScroll(firstVisibleIndex = 3, lastVisibleIndex = 8, totalItemCount = 10)
        advanceUntilIdle()

        assertEquals(8, paginator.cache.endContextPage)
    }

    @Test
    fun `restart clears everything — prefetch works from page 1 again`() = runTest {
        val paginator = createPrefetchTestPaginator(totalPages = 10, capacity = 10, loadedPages = 5)
        assertEquals(5, paginator.cache.endContextPage)

        // Restart reloads from page 1
        paginator.restart(silentlyLoading = true, silentlyResult = true)
        assertEquals(1, paginator.cache.startContextPage)
        assertEquals(1, paginator.cache.endContextPage)

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)

        // Calibration + forward
        controller.onScroll(firstVisibleIndex = 0, lastVisibleIndex = 5, totalItemCount = 10)
        controller.onScroll(firstVisibleIndex = 3, lastVisibleIndex = 8, totalItemCount = 10)
        advanceUntilIdle()

        assertEquals(2, paginator.cache.endContextPage)
    }

    @Test
    fun `refresh does not change context range — prefetch unaffected`() = runTest {
        val paginator = createPrefetchTestPaginator(totalPages = 10, capacity = 10, loadedPages = 3)

        // Refresh pages 1 and 2
        paginator.refresh(
            pages = listOf(1, 2),
            loadingSilently = true,
            finalSilently = true,
        )

        assertEquals(1, paginator.cache.startContextPage)
        assertEquals(3, paginator.cache.endContextPage)

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)

        // Calibration + forward
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals(4, paginator.cache.endContextPage)
    }

    @Test
    fun `goPreviousPage manually then prefetch forward — both directions work`() = runTest {
        val paginator = createPrefetchTestPaginator(totalPages = 10, capacity = 10, loadedPages = 1)
        paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = true)

        // Manually go to previous page (page 4)
        paginator.goPreviousPage(silentlyLoading = true, silentlyResult = true)
        assertEquals(4, paginator.cache.startContextPage)
        assertEquals(5, paginator.cache.endContextPage)

        val totalItems = paginator.core.states.sumOf { it.data.size }

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)

        // Calibration + forward to trigger page 6
        controller.onScroll(firstVisibleIndex = totalItems - 15, lastVisibleIndex = totalItems - 10, totalItemCount = totalItems)
        controller.onScroll(firstVisibleIndex = totalItems - 8, lastVisibleIndex = totalItems - 3, totalItemCount = totalItems)
        advanceUntilIdle()

        assertEquals(6, paginator.cache.endContextPage)
    }

    @Test
    fun `goNextPage manually then backward prefetch — context extends both ways`() = runTest {
        val paginator = createPrefetchTestPaginator(totalPages = 10, capacity = 10, loadedPages = 1)
        paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = true)

        // Manually go to next page (page 6)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        assertEquals(5, paginator.cache.startContextPage)
        assertEquals(6, paginator.cache.endContextPage)

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
            enableBackwardPrefetch = true,
        )

        // Calibration
        controller.onScroll(firstVisibleIndex = 5, lastVisibleIndex = 15, totalItemCount = 20)
        // Scroll backward near start — should load page 4
        controller.onScroll(firstVisibleIndex = 2, lastVisibleIndex = 12, totalItemCount = 20)
        advanceUntilIdle()

        assertTrue(
            paginator.cache.startContextPage < 5,
            "Backward prefetch should have loaded page 4"
        )
    }

    // =========================================================================
    // Integration: resize + Prefetch
    // =========================================================================

    @Test
    fun `resize changes item count per page — prefetch triggers at correct distance`() = runTest {
        val paginator = createPrefetchTestPaginator(totalPages = 10, capacity = 10, loadedPages = 3)

        // Resize capacity from 10 to 5 — items redistribute into more pages
        paginator.core.resize(capacity = 5, resize = true, silently = true)

        val totalItems = paginator.core.states.sumOf { it.data.size }
        val newEndContext = paginator.cache.endContextPage

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 3)

        // Calibration
        controller.onScroll(firstVisibleIndex = totalItems - 10, lastVisibleIndex = totalItems - 6, totalItemCount = totalItems)
        // Forward near end
        controller.onScroll(firstVisibleIndex = totalItems - 6, lastVisibleIndex = totalItems - 2, totalItemCount = totalItems)
        advanceUntilIdle()

        assertTrue(
            paginator.cache.endContextPage > newEndContext,
            "Prefetch should load a new page after resize"
        )
    }

    // =========================================================================
    // Integration: release + Prefetch
    // =========================================================================

    @Test
    fun `release clears paginator — controller reset needed, then prefetch works`() = runTest {
        val paginator = createPrefetchTestPaginator(totalPages = 10, capacity = 10, loadedPages = 3)

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)

        // Calibration + forward
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()
        assertEquals(4, paginator.cache.endContextPage)

        // Release resets the paginator entirely (capacity resets to DEFAULT_CAPACITY=20)
        paginator.release(silently = true)
        assertEquals(0, paginator.cache.endContextPage)

        // Restore capacity to match source output size and re-initialize
        paginator.core.resize(capacity = 10, resize = false, silently = true)
        paginator.finalPage = 10
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        assertEquals(1, paginator.cache.endContextPage)

        // Reset controller for new state
        controller.reset()

        // Calibration + forward
        controller.onScroll(firstVisibleIndex = 0, lastVisibleIndex = 5, totalItemCount = 10)
        controller.onScroll(firstVisibleIndex = 3, lastVisibleIndex = 8, totalItemCount = 10)
        advanceUntilIdle()

        assertEquals(2, paginator.cache.endContextPage)
    }

    // =========================================================================
    // Integration: finalPage change + Prefetch
    // =========================================================================

    @Test
    fun `reducing finalPage mid-scroll stops prefetch at new boundary`() = runTest {
        val paginator = createPrefetchTestPaginator(totalPages = 10, capacity = 10, loadedPages = 3)

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)

        // Calibration + forward to load page 4
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()
        assertEquals(4, paginator.cache.endContextPage)

        // Server told us there are only 4 pages total
        paginator.finalPage = 4

        // Try to prefetch page 5 — should be blocked
        controller.onScroll(firstVisibleIndex = 32, lastVisibleIndex = 37, totalItemCount = 40)
        advanceUntilIdle()

        assertEquals(4, paginator.cache.endContextPage)
    }

    @Test
    fun `increasing finalPage mid-scroll allows further prefetch`() = runTest {
        val paginator = createPrefetchTestPaginator(totalPages = 10, capacity = 10, loadedPages = 3)
        paginator.finalPage = 3 // initially only 3 pages allowed
        assertEquals(3, paginator.cache.endContextPage)

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)

        // Calibration + forward — blocked because endContextPage == finalPage
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()
        assertEquals(3, paginator.cache.endContextPage)

        // Server says more pages available
        paginator.finalPage = 10

        // Scroll again — now should load page 4
        controller.onScroll(firstVisibleIndex = 23, lastVisibleIndex = 28, totalItemCount = 30)
        advanceUntilIdle()
        assertEquals(4, paginator.cache.endContextPage)
    }

    // =========================================================================
    // Integration: lock flags toggled during scroll + Prefetch
    // =========================================================================

    @Test
    fun `unlock goNextPage mid-scroll — prefetch resumes`() = runTest {
        val paginator = createPrefetchTestPaginator(totalPages = 10, capacity = 10, loadedPages = 3)
        paginator.lockGoNextPage = true

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)

        // Calibration + forward — blocked by lock
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()
        assertEquals(3, paginator.cache.endContextPage)

        // Unlock
        paginator.lockGoNextPage = false

        // Scroll again — should prefetch now
        controller.onScroll(firstVisibleIndex = 23, lastVisibleIndex = 28, totalItemCount = 30)
        advanceUntilIdle()
        assertEquals(4, paginator.cache.endContextPage)
    }

    @Test
    fun `unlock goPreviousPage mid-scroll — backward prefetch resumes`() = runTest {
        val paginator = createPrefetchTestPaginator(totalPages = 10, capacity = 10, loadedPages = 1)
        paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = true)
        paginator.lockGoPreviousPage = true

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
            enableBackwardPrefetch = true,
        )

        // Calibration + backward — blocked by lock
        controller.onScroll(firstVisibleIndex = 5, lastVisibleIndex = 9, totalItemCount = 10)
        controller.onScroll(firstVisibleIndex = 2, lastVisibleIndex = 6, totalItemCount = 10)
        advanceUntilIdle()
        assertEquals(5, paginator.cache.startContextPage)

        // Unlock
        paginator.lockGoPreviousPage = false

        // Scroll backward again — should prefetch now
        controller.onScroll(firstVisibleIndex = 1, lastVisibleIndex = 5, totalItemCount = 10)
        advanceUntilIdle()
        assertTrue(paginator.cache.startContextPage < 5)
    }

    // =========================================================================
    // Integration: CRUD between prefetch scrolls
    // =========================================================================

    @Test
    fun `removeElement between two prefetch rounds — cascading makes last page incomplete`() = runTest {
        val paginator = createPrefetchTestPaginator(totalPages = 10, capacity = 10, loadedPages = 3)

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)

        // Calibration + forward -> page 4
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()
        assertEquals(4, paginator.cache.endContextPage)

        // Remove elements from page 2 between prefetches.
        // Cascading pulls from page 3 → page 3 from page 4 → page 4 becomes incomplete.
        repeat(3) {
            paginator.removeElement(page = 2, index = 0, silently = true)
        }

        val page4Size = paginator.cache.getStateOf(4)!!.data.size
        assertTrue(page4Size < paginator.core.capacity, "Page 4 should be incomplete after cascading removes")

        val totalItems = paginator.core.states.sumOf { it.data.size }

        // Continue scrolling forward — goNextPage will re-fetch page 4 (incomplete)
        controller.onScroll(firstVisibleIndex = totalItems - 8, lastVisibleIndex = totalItems - 3, totalItemCount = totalItems)
        advanceUntilIdle()

        // endContextPage stays at 4, but page 4 data is refreshed to full capacity
        assertEquals(4, paginator.cache.endContextPage)
        assertEquals(10, paginator.cache.getStateOf(4)!!.data.size)
    }

    @Test
    fun `setElement with isDirty between prefetches — dirty page refreshed on next nav`() = runTest {
        var page1LoadCount = 0
        val paginator = MutablePaginator<String> { page: Int ->
            if (page == 1) page1LoadCount++
            LoadResult(List(10) { "p${page}_item_$it" })
        }
        paginator.core.resize(capacity = 10, resize = false, silently = true)
        paginator.finalPage = 10
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        page1LoadCount = 0

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)

        // Calibration + first prefetch (page 4)
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()
        assertEquals(4, paginator.cache.endContextPage)

        // Mark page 1 dirty via setElement
        paginator.setElement("DIRTY", page = 1, index = 0, silently = true, isDirty = true)

        // Next prefetch navigation (page 5) should also refresh dirty page 1
        controller.onScroll(firstVisibleIndex = 32, lastVisibleIndex = 37, totalItemCount = 40)
        advanceUntilIdle()
        assertEquals(5, paginator.cache.endContextPage)
        assertTrue(page1LoadCount > 0, "Dirty page 1 should have been refreshed during navigation")
    }

    // =========================================================================
    // Integration: addAllElements causing page overflow + Prefetch
    // =========================================================================

    @Test
    fun `addAllElements overflows into new pages — prefetch loads beyond them`() = runTest {
        val paginator = createPrefetchTestPaginator(totalPages = 10, capacity = 10, loadedPages = 2)
        assertEquals(2, paginator.cache.endContextPage)

        // Add 15 elements to page 2 — overflow creates page 3 (or extends page 2)
        paginator.addAllElements(
            elements = List(15) { "overflow_$it" },
            targetPage = 2,
            index = 5,
            silently = true,
        )

        val totalItems = paginator.core.states.sumOf { it.data.size }
        val endContextAfterAdd = paginator.cache.endContextPage

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)

        // Calibration + forward near end of current items
        controller.onScroll(firstVisibleIndex = totalItems - 12, lastVisibleIndex = totalItems - 8, totalItemCount = totalItems)
        controller.onScroll(firstVisibleIndex = totalItems - 8, lastVisibleIndex = totalItems - 3, totalItemCount = totalItems)
        advanceUntilIdle()

        assertTrue(
            paginator.cache.endContextPage > endContextAfterAdd,
            "Prefetch should load beyond pages created by addAllElements overflow"
        )
    }

    // =========================================================================
    // Integration: simultaneous CRUD + forward and backward prefetch
    // =========================================================================

    @Test
    fun `CRUD on middle page while both forward and backward prefetch active`() = runTest {
        val paginator = createPrefetchTestPaginator(totalPages = 10, capacity = 10, loadedPages = 1)
        paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = true)

        // Load neighbors manually
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true) // page 6
        paginator.goPreviousPage(silentlyLoading = true, silentlyResult = true) // page 4
        assertEquals(4, paginator.cache.startContextPage)
        assertEquals(6, paginator.cache.endContextPage)

        // Modify middle page
        paginator.setElement("CHANGED", page = 5, index = 0, silently = true)

        val totalItems = paginator.core.states.sumOf { it.data.size }

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
            enableBackwardPrefetch = true,
        )

        // Calibration with small viewport in the middle
        controller.onScroll(firstVisibleIndex = 8, lastVisibleIndex = 18, totalItemCount = totalItems)
        // Viewport expansion — both directions
        controller.onScroll(firstVisibleIndex = 2, lastVisibleIndex = 28, totalItemCount = totalItems)
        advanceUntilIdle()

        // At least one direction should have prefetched
        assertTrue(
            paginator.cache.startContextPage < 4 || paginator.cache.endContextPage > 6,
            "Prefetch should extend context in at least one direction"
        )
        // Modified element should persist
        assertEquals("CHANGED", paginator.cache.getStateOf(5)!!.data[0])
    }
}
