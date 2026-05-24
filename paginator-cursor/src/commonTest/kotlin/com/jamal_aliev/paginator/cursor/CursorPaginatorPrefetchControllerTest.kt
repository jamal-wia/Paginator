package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.extension.prefetchController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CursorPaginatorPrefetchControllerTest {

    /**
     * Builds a cursor paginator with [totalPages] linked pages of [capacity] items
     * each, with [loadedPages] pages already in cache (head + walk forward).
     */
    private suspend fun createPrefetchTestPaginator(
        totalPages: Int = 10,
        capacity: Int = 10,
        loadedPages: Int = 3,
    ): CursorPaginator<String> {
        val backend = FakeCursorBackend(FakeCursorBackend.defaultPages(totalPages, capacity))
        val paginator = cursorPaginatorOf(backend, capacity = capacity)
        paginator.jump(
            bookmark = CursorBookmark(prev = null, self = backend.head.self, next = null),
            silentlyLoading = true,
            silentlyResult = true,
        )
        repeat(loadedPages - 1) {
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        }
        return paginator
    }

    /** Builds a paginator anchored at the middle page so prev/next links both exist. */
    private suspend fun createMiddleAnchoredPaginator(
        totalPages: Int = 10,
        capacity: Int = 10,
        anchorIndex: Int = 5,
    ): CursorPaginator<String> {
        val backend = FakeCursorBackend(FakeCursorBackend.defaultPages(totalPages, capacity))
        val paginator = cursorPaginatorOf(backend, capacity = capacity)
        paginator.jump(
            bookmark = CursorBookmark(prev = null, self = "p$anchorIndex", next = null),
            silentlyLoading = true,
            silentlyResult = true,
        )
        return paginator
    }

    // =========================================================================
    // Validation
    // =========================================================================

    @Test
    fun `prefetchDistance rejects zero`() {
        assertFailsWith<IllegalArgumentException> {
            cursorPaginatorOf().prefetchController(
                scope = TestScope(),
                prefetchDistance = 0,
            )
        }
    }

    @Test
    fun `prefetchDistance rejects negative`() {
        assertFailsWith<IllegalArgumentException> {
            cursorPaginatorOf().prefetchController(
                scope = TestScope(),
                prefetchDistance = -1,
            )
        }
    }

    @Test
    fun `prefetchDistance setter rejects zero`() {
        val controller = cursorPaginatorOf().prefetchController(
            scope = TestScope(),
            prefetchDistance = 5,
        )
        assertFailsWith<IllegalArgumentException> {
            controller.prefetchDistance = 0
        }
    }

    @Test
    fun `prefetchDistance setter rejects negative`() {
        val controller = cursorPaginatorOf().prefetchController(
            scope = TestScope(),
            prefetchDistance = 5,
        )
        assertFailsWith<IllegalArgumentException> {
            controller.prefetchDistance = -3
        }
    }

    // =========================================================================
    // Calibration — first onScroll does not trigger prefetch
    // =========================================================================

    @Test
    fun `first onScroll is calibration only - no prefetch`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEnd = paginator.core.endContextCursor?.self

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals(initialEnd, paginator.core.endContextCursor?.self)
    }

    @Test
    fun `second onScroll with forward movement triggers prefetch`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEnd = paginator.core.endContextCursor?.self

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals("p3", paginator.core.endContextCursor?.self)
        assertEquals("p2", initialEnd)
    }

    // =========================================================================
    // enabled flag
    // =========================================================================

    @Test
    fun `onScroll is no-op when enabled is false`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEnd = paginator.core.endContextCursor?.self

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)
        controller.enabled = false

        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals(initialEnd, paginator.core.endContextCursor?.self)
    }

    @Test
    fun `onScroll is no-op when totalItemCount is zero`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEnd = paginator.core.endContextCursor?.self

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)
        controller.onScroll(firstVisibleIndex = 0, lastVisibleIndex = 0, totalItemCount = 0)
        controller.onScroll(firstVisibleIndex = 0, lastVisibleIndex = 0, totalItemCount = 0)
        advanceUntilIdle()

        assertEquals(initialEnd, paginator.core.endContextCursor?.self)
    }

    // =========================================================================
    // cancel() / reset()
    // =========================================================================

    @Test
    fun `cancel allows new prefetch on subsequent scroll`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)

        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()
        assertEquals("p3", paginator.core.endContextCursor?.self)

        controller.cancel()

        controller.onScroll(firstVisibleIndex = 32, lastVisibleIndex = 37, totalItemCount = 40)
        advanceUntilIdle()
        assertEquals("p4", paginator.core.endContextCursor?.self)
    }

    @Test
    fun `reset causes next onScroll to be calibration again`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)

        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()
        assertEquals("p3", paginator.core.endContextCursor?.self)

        controller.reset()

        // Next call is calibration — no prefetch despite being near end.
        controller.onScroll(firstVisibleIndex = 32, lastVisibleIndex = 37, totalItemCount = 40)
        advanceUntilIdle()
        assertEquals("p3", paginator.core.endContextCursor?.self)
    }

    // =========================================================================
    // Backward prefetch
    // =========================================================================

    @Test
    fun `backward prefetch disabled by default`() = runTest {
        val paginator = createMiddleAnchoredPaginator()
        val initialStart = paginator.core.startContextCursor?.self

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)
        controller.onScroll(firstVisibleIndex = 5, lastVisibleIndex = 10, totalItemCount = 10)
        controller.onScroll(firstVisibleIndex = 2, lastVisibleIndex = 7, totalItemCount = 10)
        advanceUntilIdle()

        assertEquals(initialStart, paginator.core.startContextCursor?.self)
    }

    @Test
    fun `backward prefetch works when enabled`() = runTest {
        val paginator = createMiddleAnchoredPaginator()
        val initialStart = paginator.core.startContextCursor?.self

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
            enableBackwardPrefetch = true,
        )
        controller.onScroll(firstVisibleIndex = 5, lastVisibleIndex = 9, totalItemCount = 10)
        controller.onScroll(firstVisibleIndex = 2, lastVisibleIndex = 6, totalItemCount = 10)
        advanceUntilIdle()

        assertEquals("p5", initialStart)
        assertEquals("p4", paginator.core.startContextCursor?.self)
    }

    // =========================================================================
    // Locks
    // =========================================================================

    @Test
    fun `no forward prefetch when lockGoNextPage is true`() = runTest {
        val paginator = createPrefetchTestPaginator()
        paginator.lockGoNextPage = true
        val initialEnd = paginator.core.endContextCursor?.self

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals(initialEnd, paginator.core.endContextCursor?.self)
    }

    @Test
    fun `no backward prefetch when lockGoPreviousPage is true`() = runTest {
        val paginator = createMiddleAnchoredPaginator()
        paginator.lockGoPreviousPage = true
        val initialStart = paginator.core.startContextCursor?.self

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
            enableBackwardPrefetch = true,
        )
        controller.onScroll(firstVisibleIndex = 5, lastVisibleIndex = 9, totalItemCount = 10)
        controller.onScroll(firstVisibleIndex = 2, lastVisibleIndex = 6, totalItemCount = 10)
        advanceUntilIdle()

        assertEquals(initialStart, paginator.core.startContextCursor?.self)
    }

    // =========================================================================
    // onPrefetchError
    // =========================================================================

    @Test
    fun `onPrefetchError receives exceptions from goNextPage`() = runTest {
        val errors = mutableListOf<Exception>()
        val paginator = createPrefetchTestPaginator()

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
            loadGuard = { cursor, _ -> cursor.self != "p3" },
            onPrefetchError = { errors.add(it) },
        )

        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals(1, errors.size)
        assertTrue(
            errors[0]::class.simpleName?.contains("LoadGuarded") == true,
            "Expected CursorLoadGuardedException but got ${errors[0]::class.simpleName}",
        )
    }

    @Test
    fun `onPrefetchError receives exceptions from goPreviousPage`() = runTest {
        val errors = mutableListOf<Exception>()
        val paginator = createMiddleAnchoredPaginator()

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
            enableBackwardPrefetch = true,
            loadGuard = { cursor, _ -> cursor.self != "p4" },
            onPrefetchError = { errors.add(it) },
        )

        controller.onScroll(firstVisibleIndex = 5, lastVisibleIndex = 9, totalItemCount = 10)
        controller.onScroll(firstVisibleIndex = 2, lastVisibleIndex = 6, totalItemCount = 10)
        advanceUntilIdle()

        assertEquals(1, errors.size)
        assertTrue(
            errors[0]::class.simpleName?.contains("LoadGuarded") == true,
            "Expected CursorLoadGuardedException but got ${errors[0]::class.simpleName}",
        )
    }

    // =========================================================================
    // Duplicate-job guard
    // =========================================================================

    @Test
    fun `does not launch duplicate forward job while one is active`() = runTest {
        val backend = FakeCursorBackend(FakeCursorBackend.defaultPages(10, 10))
        val paginator = cursorPaginatorOf(backend, capacity = 10)
        paginator.jump(
            bookmark = CursorBookmark(prev = null, self = "p0", next = null),
            silentlyLoading = true,
            silentlyResult = true,
        )
        val callsBefore = backend.callCount

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 3)
        controller.onScroll(firstVisibleIndex = 0, lastVisibleIndex = 5, totalItemCount = 10)
        controller.onScroll(firstVisibleIndex = 3, lastVisibleIndex = 8, totalItemCount = 10)
        controller.onScroll(firstVisibleIndex = 4, lastVisibleIndex = 9, totalItemCount = 10)
        advanceUntilIdle()

        // Exactly one extra backend call (load of p1) despite three onScroll triggers.
        assertEquals(callsBefore + 1, backend.callCount)
    }

    @Test
    fun `does not launch duplicate backward job while one is active`() = runTest {
        val backend = FakeCursorBackend(FakeCursorBackend.defaultPages(10, 10))
        val paginator = cursorPaginatorOf(backend, capacity = 10)
        paginator.jump(
            bookmark = CursorBookmark(prev = null, self = "p5", next = null),
            silentlyLoading = true,
            silentlyResult = true,
        )
        val callsBefore = backend.callCount

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
            enableBackwardPrefetch = true,
        )
        controller.onScroll(firstVisibleIndex = 5, lastVisibleIndex = 9, totalItemCount = 10)
        controller.onScroll(firstVisibleIndex = 3, lastVisibleIndex = 7, totalItemCount = 10)
        controller.onScroll(firstVisibleIndex = 2, lastVisibleIndex = 6, totalItemCount = 10)
        advanceUntilIdle()

        assertEquals(callsBefore + 1, backend.callCount)
    }

    // =========================================================================
    // Extension factory
    // =========================================================================

    @Test
    fun `prefetchController extension creates working controller`() = runTest {
        val paginator = createPrefetchTestPaginator()

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)

        assertNotNull(controller)
        assertTrue(controller.enabled)
        assertEquals(5, controller.prefetchDistance)
    }

    // =========================================================================
    // Negative / out-of-bounds indices
    // =========================================================================

    @Test
    fun `negative firstVisibleIndex is ignored - RecyclerView NO_POSITION`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEnd = paginator.core.endContextCursor?.self

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)
        // RecyclerView returns NO_POSITION (-1); these calls must be ignored entirely.
        controller.onScroll(firstVisibleIndex = -1, lastVisibleIndex = -1, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = -1, lastVisibleIndex = -1, totalItemCount = 30)
        // Then a normal calibration + forward scroll.
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals("p2", initialEnd)
        assertEquals("p3", paginator.core.endContextCursor?.self)
    }

    @Test
    fun `negative totalItemCount does not crash or trigger prefetch`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEnd = paginator.core.endContextCursor?.self

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)
        controller.onScroll(firstVisibleIndex = 0, lastVisibleIndex = 5, totalItemCount = -1)
        controller.onScroll(firstVisibleIndex = 0, lastVisibleIndex = 5, totalItemCount = -1)
        advanceUntilIdle()

        assertEquals(initialEnd, paginator.core.endContextCursor?.self)
    }

    @Test
    fun `inverted range - firstVisibleIndex greater than lastVisibleIndex`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEnd = paginator.core.endContextCursor?.self

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
            enableBackwardPrefetch = true,
        )
        controller.onScroll(firstVisibleIndex = 10, lastVisibleIndex = 15, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 15, lastVisibleIndex = 10, totalItemCount = 30)
        advanceUntilIdle()

        // lastVisible went DOWN (no forward); firstVisible went UP (no backward).
        assertEquals(initialEnd, paginator.core.endContextCursor?.self)
    }

    @Test
    fun `exactly at the last item triggers prefetch - itemsFromEnd is 0`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)

        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 25, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 25, lastVisibleIndex = 29, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals("p3", paginator.core.endContextCursor?.self)
    }

    @Test
    fun `lastVisibleIndex exceeding totalItemCount is clamped`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)

        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 25, totalItemCount = 30)
        // 35 is clamped to 29 (totalItemCount - 1); itemsFromEnd = 0 <= 5 => prefetch.
        controller.onScroll(firstVisibleIndex = 25, lastVisibleIndex = 35, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals("p3", paginator.core.endContextCursor?.self)
    }

    // =========================================================================
    // End-of-feed: cursor-specific stopping condition
    // =========================================================================

    @Test
    fun `no forward prefetch when endContextCursor next link is null`() = runTest {
        // Load ALL pages so endContextCursor.next == null.
        val paginator = createPrefetchTestPaginator(totalPages = 3, capacity = 10, loadedPages = 3)
        assertEquals("p2", paginator.core.endContextCursor?.self)
        assertNull(paginator.core.endContextCursor?.next)

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 25, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 25, lastVisibleIndex = 29, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals("p2", paginator.core.endContextCursor?.self)
    }

    @Test
    fun `no backward prefetch when startContextCursor prev link is null`() = runTest {
        val paginator = createPrefetchTestPaginator(totalPages = 10, capacity = 10, loadedPages = 3)
        assertEquals("p0", paginator.core.startContextCursor?.self)
        assertNull(paginator.core.startContextCursor?.prev)

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 5,
            enableBackwardPrefetch = true,
        )
        controller.onScroll(firstVisibleIndex = 5, lastVisibleIndex = 10, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 2, lastVisibleIndex = 7, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals("p0", paginator.core.startContextCursor?.self)
    }

    // =========================================================================
    // Direction detection corner cases
    // =========================================================================

    @Test
    fun `same position repeated after calibration does not trigger prefetch`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEnd = paginator.core.endContextCursor?.self

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)
        controller.onScroll(firstVisibleIndex = 25, lastVisibleIndex = 29, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 25, lastVisibleIndex = 29, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals(initialEnd, paginator.core.endContextCursor?.self)
    }

    @Test
    fun `viewport expansion - firstVisible decreases AND lastVisible increases`() = runTest {
        val paginator = createMiddleAnchoredPaginator(capacity = 10)
        val initialStart = paginator.core.startContextCursor?.self
        val initialEnd = paginator.core.endContextCursor?.self

        val controller = paginator.prefetchController(
            scope = this,
            prefetchDistance = 3,
            enableBackwardPrefetch = true,
        )
        controller.onScroll(firstVisibleIndex = 3, lastVisibleIndex = 6, totalItemCount = 10)
        controller.onScroll(firstVisibleIndex = 1, lastVisibleIndex = 8, totalItemCount = 10)
        advanceUntilIdle()

        assertTrue(
            paginator.core.endContextCursor?.self != initialEnd
                    || paginator.core.startContextCursor?.self != initialStart,
            "At least one direction should have prefetched",
        )
    }

    // =========================================================================
    // prefetchDistance variants
    // =========================================================================

    @Test
    fun `prefetchDistance larger than totalItemCount triggers prefetch immediately`() = runTest {
        val paginator = createPrefetchTestPaginator(capacity = 5, loadedPages = 1)
        val initialEnd = paginator.core.endContextCursor?.self

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 100)
        controller.onScroll(firstVisibleIndex = 0, lastVisibleIndex = 2, totalItemCount = 5)
        controller.onScroll(firstVisibleIndex = 1, lastVisibleIndex = 3, totalItemCount = 5)
        advanceUntilIdle()

        assertEquals("p0", initialEnd)
        assertEquals("p1", paginator.core.endContextCursor?.self)
    }

    @Test
    fun `changing prefetchDistance at runtime affects subsequent scrolls`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEnd = paginator.core.endContextCursor?.self

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 1)
        // itemsFromEnd = 30 - 25 - 1 = 4 > 1 => no prefetch.
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 25, totalItemCount = 30)
        advanceUntilIdle()
        assertEquals(initialEnd, paginator.core.endContextCursor?.self)

        controller.prefetchDistance = 10
        // itemsFromEnd = 30 - 26 - 1 = 3 <= 10 => prefetch.
        controller.onScroll(firstVisibleIndex = 23, lastVisibleIndex = 26, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals("p3", paginator.core.endContextCursor?.self)
    }

    // =========================================================================
    // Single-item list
    // =========================================================================

    @Test
    fun `single item list - same position repeated does not trigger prefetch`() = runTest {
        val paginator = createPrefetchTestPaginator(capacity = 1, loadedPages = 1)
        val initialEnd = paginator.core.endContextCursor?.self

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 1)
        controller.onScroll(firstVisibleIndex = 0, lastVisibleIndex = 0, totalItemCount = 1)
        controller.onScroll(firstVisibleIndex = 0, lastVisibleIndex = 0, totalItemCount = 1)
        advanceUntilIdle()

        assertEquals(initialEnd, paginator.core.endContextCursor?.self)
    }

    // =========================================================================
    // disable / re-enable interactions
    // =========================================================================

    @Test
    fun `re-enabling controller after disable preserves calibration state`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEnd = paginator.core.endContextCursor?.self

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)

        controller.enabled = false
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        controller.enabled = true

        controller.onScroll(firstVisibleIndex = 23, lastVisibleIndex = 28, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals("p2", initialEnd)
        assertEquals("p3", paginator.core.endContextCursor?.self)
    }

    @Test
    fun `disabling during calibration phase - re-enable still requires calibration`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEnd = paginator.core.endContextCursor?.self

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)
        // Disable BEFORE first scroll — calibration never completes.
        controller.enabled = false
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)
        controller.enabled = true

        // First scroll after re-enable acts as calibration.
        controller.onScroll(firstVisibleIndex = 22, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals(initialEnd, paginator.core.endContextCursor?.self)
    }

    @Test
    fun `disable-reenable tracks scroll position - correct direction after re-enable`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEnd = paginator.core.endContextCursor?.self

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)
        controller.onScroll(firstVisibleIndex = 20, lastVisibleIndex = 22, totalItemCount = 30)

        controller.enabled = false
        controller.onScroll(firstVisibleIndex = 25, lastVisibleIndex = 29, totalItemCount = 30)
        controller.enabled = true

        // From 29 back to 27 is BACKWARD scroll — must not trigger forward prefetch.
        controller.onScroll(firstVisibleIndex = 23, lastVisibleIndex = 27, totalItemCount = 30)
        advanceUntilIdle()

        assertEquals(initialEnd, paginator.core.endContextCursor?.self)
    }

    // =========================================================================
    // Unstarted paginator — cursor-specific behavior
    // =========================================================================

    @Test
    fun `forward prefetch on unstarted paginator does nothing - endContextCursor is null`() =
        runTest {
            val backend = FakeCursorBackend(FakeCursorBackend.defaultPages(10, 10))
            val paginator = cursorPaginatorOf(backend, capacity = 10)
            // Intentionally not calling jump/restart — endContextCursor is null.
            val callsBefore = backend.callCount

            val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)
            controller.onScroll(firstVisibleIndex = 0, lastVisibleIndex = 5, totalItemCount = 10)
            controller.onScroll(firstVisibleIndex = 3, lastVisibleIndex = 8, totalItemCount = 10)
            advanceUntilIdle()

            assertNull(paginator.core.endContextCursor)
            assertEquals(callsBefore, backend.callCount)
        }

    // =========================================================================
    // Large indices safety
    // =========================================================================

    @Test
    fun `very large indices do not overflow or crash`() = runTest {
        val paginator = createPrefetchTestPaginator()
        val initialEnd = paginator.core.endContextCursor?.self

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)
        controller.onScroll(
            firstVisibleIndex = Int.MAX_VALUE - 100,
            lastVisibleIndex = Int.MAX_VALUE - 50,
            totalItemCount = Int.MAX_VALUE,
        )
        controller.onScroll(
            firstVisibleIndex = Int.MAX_VALUE - 90,
            lastVisibleIndex = Int.MAX_VALUE - 40,
            totalItemCount = Int.MAX_VALUE,
        )
        advanceUntilIdle()

        // itemsFromEnd = MAX_VALUE - (MAX_VALUE - 40) - 1 = 39 > 5; no prefetch but no crash.
        assertEquals(initialEnd, paginator.core.endContextCursor?.self)
    }

    // =========================================================================
    // Chained prefetch
    // =========================================================================

    @Test
    fun `sequential scrolling loads multiple pages one at a time`() = runTest {
        val paginator = createPrefetchTestPaginator(totalPages = 10, capacity = 10, loadedPages = 1)
        assertEquals("p0", paginator.core.endContextCursor?.self)

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 3)

        controller.onScroll(firstVisibleIndex = 0, lastVisibleIndex = 5, totalItemCount = 10)
        controller.onScroll(firstVisibleIndex = 5, lastVisibleIndex = 9, totalItemCount = 10)
        advanceUntilIdle()
        assertEquals("p1", paginator.core.endContextCursor?.self)

        controller.onScroll(firstVisibleIndex = 15, lastVisibleIndex = 19, totalItemCount = 20)
        advanceUntilIdle()
        assertEquals("p2", paginator.core.endContextCursor?.self)

        controller.onScroll(firstVisibleIndex = 25, lastVisibleIndex = 29, totalItemCount = 30)
        advanceUntilIdle()
        assertEquals("p3", paginator.core.endContextCursor?.self)
    }

    @Test
    fun `cursor link is followed - prefetch lands on next self`() = runTest {
        val paginator = createPrefetchTestPaginator(totalPages = 5, capacity = 10, loadedPages = 2)
        val currentEnd = paginator.core.endContextCursor
        assertEquals("p1", currentEnd?.self)
        assertEquals("p2", currentEnd?.next)

        val controller = paginator.prefetchController(scope = this, prefetchDistance = 5)
        controller.onScroll(firstVisibleIndex = 10, lastVisibleIndex = 15, totalItemCount = 20)
        controller.onScroll(firstVisibleIndex = 14, lastVisibleIndex = 19, totalItemCount = 20)
        advanceUntilIdle()

        // After prefetch the endContextCursor must equal the previous cursor's next.
        assertEquals("p2", paginator.core.endContextCursor?.self)
    }
}
