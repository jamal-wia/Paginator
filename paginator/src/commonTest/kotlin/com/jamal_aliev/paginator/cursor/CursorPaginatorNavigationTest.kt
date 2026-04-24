package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.exception.CursorLoadGuardedException
import com.jamal_aliev.paginator.exception.EndOfCursorFeedException
import com.jamal_aliev.paginator.exception.LockedException
import com.jamal_aliev.paginator.extension.isErrorState
import com.jamal_aliev.paginator.extension.isSuccessState
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CursorPaginatorNavigationTest {

    // ── restart ─────────────────────────────────────────────────────────────

    @Test
    fun restart_without_initialCursor_loads_first_page_and_sets_context() = runTest {
        val backend = FakeCursorBackend()
        val paginator = cursorPaginatorOf(backend)

        paginator.restart(silentlyLoading = true, silentlyResult = true)

        assertEquals("p0", paginator.core.startContextCursor?.self)
        assertEquals("p0", paginator.core.endContextCursor?.self)
        assertTrue(paginator.cache.getStateOf("p0")!!.isSuccessState())
        assertEquals(1, backend.callCount)
    }

    @Test
    fun restart_with_initialCursor_jumps_to_anchor() = runTest {
        val backend = FakeCursorBackend()
        val paginator = cursorPaginatorOf(backend).apply {
            initialCursor = CursorBookmark(prev = null, self = "p2", next = null)
        }

        paginator.restart(silentlyLoading = true, silentlyResult = true)

        assertEquals("p2", paginator.core.startContextCursor?.self)
        assertEquals("p2", paginator.core.endContextCursor?.self)
    }

    @Test
    fun restart_lock_throws() = runTest {
        val paginator = cursorPaginatorOf()
        paginator.lockRestart = true
        assertFailsWith<LockedException.RestartWasLockedException> {
            paginator.restart(silentlyLoading = true, silentlyResult = true)
        }
    }

    @Test
    fun restart_propagates_load_failure_as_error_page_return_value() = runTest {
        // Simulates a server error on the initial load. The error page should surface
        // and the cache / context should remain consistent (not half-populated).
        var attempts = 0
        val paginator = failingCursorPaginator(RuntimeException("initial failed"))
        paginator.restart(silentlyLoading = true, silentlyResult = true)
        // After a failed restart the context must not be left pointing at a sentinel.
        assertNull(paginator.core.startContextCursor)
        assertNull(paginator.core.endContextCursor)
    }

    // ── jump ────────────────────────────────────────────────────────────────

    @Test
    fun jump_to_unknown_cursor_loads_from_source() = runTest {
        val backend = FakeCursorBackend()
        val paginator = cursorPaginatorOf(backend)

        val (cursor, state) = paginator.jump(
            bookmark = CursorBookmark(prev = null, self = "p3", next = null),
            silentlyLoading = true,
            silentlyResult = true,
        )
        assertEquals("p3", cursor.self)
        assertEquals("p2", cursor.prev)
        assertEquals("p4", cursor.next)
        assertTrue(state.isSuccessState())
    }

    @Test
    fun jump_cache_hit_skips_source_call() = runTest {
        val backend = FakeCursorBackend()
        val paginator = cursorPaginatorOf(backend)
        paginator.jump(
            bookmark = CursorBookmark(prev = null, self = "p1", next = null),
            silentlyLoading = true,
            silentlyResult = true,
        )
        val callsAfterFirst = backend.callCount

        paginator.jump(
            bookmark = CursorBookmark(prev = null, self = "p1", next = null),
            silentlyLoading = true,
            silentlyResult = true,
        )
        assertEquals(callsAfterFirst, backend.callCount, "cache hit must not re-invoke load")
    }

    @Test
    fun jump_locked_throws() = runTest {
        val paginator = cursorPaginatorOf()
        paginator.lockJump = true
        assertFailsWith<LockedException.JumpWasLockedException> {
            paginator.jump(
                bookmark = CursorBookmark(prev = null, self = "p0", next = null),
                silentlyLoading = true,
                silentlyResult = true,
            )
        }
    }

    @Test
    fun jump_load_guard_rejects_with_cursor_exception() = runTest {
        val paginator = cursorPaginatorOf()
        val exception = assertFailsWith<CursorLoadGuardedException> {
            paginator.jump(
                bookmark = CursorBookmark(prev = null, self = "p0", next = null),
                loadGuard = { _, _ -> false },
                silentlyLoading = true,
                silentlyResult = true,
            )
        }
        assertEquals("p0", exception.attemptedCursor.self)
    }

    // ── goNextPage ──────────────────────────────────────────────────────────

    @Test
    fun goNextPage_walks_forward_through_source() = runTest {
        val backend = FakeCursorBackend()
        val paginator = cursorPaginatorOf(backend)
        paginator.restart(silentlyLoading = true, silentlyResult = true)

        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        assertEquals("p1", paginator.core.endContextCursor?.self)

        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        assertEquals("p2", paginator.core.endContextCursor?.self)
    }

    @Test
    fun goNextPage_throws_end_of_feed_at_tail() = runTest {
        val backend = FakeCursorBackend()
        val paginator = cursorPaginatorOf(backend)
        paginator.restart(silentlyLoading = true, silentlyResult = true)
        repeat(4) { paginator.goNextPage(silentlyLoading = true, silentlyResult = true) }
        // Now we're on p4 (the tail with next=null).
        assertEquals("p4", paginator.core.endContextCursor?.self)

        val exception = assertFailsWith<EndOfCursorFeedException> {
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        }
        assertEquals("p4", exception.attemptedCursorKey)
        assertEquals(EndOfCursorFeedException.Direction.FORWARD, exception.direction)
    }

    @Test
    fun goNextPage_bootstraps_when_not_started() = runTest {
        val backend = FakeCursorBackend()
        val paginator = cursorPaginatorOf(backend)
        // No explicit restart/jump — goNextPage must bootstrap.
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        assertEquals("p0", paginator.core.endContextCursor?.self)
    }

    @Test
    fun goNextPage_locked_throws() = runTest {
        val paginator = cursorPaginatorOf()
        paginator.lockGoNextPage = true
        assertFailsWith<LockedException.GoNextPageWasLockedException> {
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        }
    }

    // ── goPreviousPage ──────────────────────────────────────────────────────

    @Test
    fun goPreviousPage_walks_backward_through_source() = runTest {
        val backend = FakeCursorBackend()
        val paginator = cursorPaginatorOf(backend).apply {
            initialCursor = CursorBookmark(prev = null, self = "p2", next = null)
        }
        paginator.restart(silentlyLoading = true, silentlyResult = true)

        paginator.goPreviousPage(silentlyLoading = true, silentlyResult = true)
        assertEquals("p1", paginator.core.startContextCursor?.self)
    }

    @Test
    fun goPreviousPage_throws_end_of_feed_at_head() = runTest {
        val backend = FakeCursorBackend()
        val paginator = cursorPaginatorOf(backend)
        paginator.restart(silentlyLoading = true, silentlyResult = true)
        // At p0 with prev=null.
        val exception = assertFailsWith<EndOfCursorFeedException> {
            paginator.goPreviousPage(silentlyLoading = true, silentlyResult = true)
        }
        assertEquals("p0", exception.attemptedCursorKey)
        assertEquals(EndOfCursorFeedException.Direction.BACKWARD, exception.direction)
    }

    @Test
    fun goPreviousPage_throws_when_not_started() = runTest {
        val paginator = cursorPaginatorOf()
        assertFailsWith<IllegalStateException> {
            paginator.goPreviousPage(silentlyLoading = true, silentlyResult = true)
        }
    }

    @Test
    fun goPreviousPage_locked_throws() = runTest {
        val backend = FakeCursorBackend()
        val paginator = cursorPaginatorOf(backend)
        paginator.restart(silentlyLoading = true, silentlyResult = true)
        paginator.lockGoPreviousPage = true
        assertFailsWith<LockedException.GoPreviousPageWasLockedException> {
            paginator.goPreviousPage(silentlyLoading = true, silentlyResult = true)
        }
    }

    // ── load error produces ErrorPage ───────────────────────────────────────

    @Test
    fun load_error_on_goNext_results_in_ErrorPage_cached_for_target() = runTest {
        // Use a paginator whose 2nd call throws.
        var call = 0
        val paginator = com.jamal_aliev.paginator.CursorPaginator<String>(
            core = com.jamal_aliev.paginator.CursorPagingCore(initialCapacity = 3),
        ) { cursor ->
            call++
            if (call == 1) {
                com.jamal_aliev.paginator.load.CursorLoadResult(
                    data = listOf("a", "b", "c"),
                    bookmark = CursorBookmark(prev = null, self = "p0", next = "p1"),
                )
            } else {
                throw RuntimeException("network down")
            }
        }
        paginator.restart(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        // The target of the failed load is p1 — it should be cached as an ErrorPage.
        val state = paginator.cache.getStateOf("p1")
        assertNotNull(state)
        assertTrue(state.isErrorState())
    }

    // ── jumpForward / jumpBack ──────────────────────────────────────────────

    @Test
    fun jumpForward_uses_bookmarks_list() = runTest {
        val backend = FakeCursorBackend()
        val paginator = cursorPaginatorOf(backend)
        // NOTE: silentlyResult MUST be false so the snapshot is emitted — jumpForward
        // skips bookmarks whose self key is in the last emitted snapshot.
        paginator.restart(silentlyLoading = true, silentlyResult = false)
        paginator.bookmarks.addAll(
            listOf(
                CursorBookmark(prev = null, self = "p0", next = null),
                CursorBookmark(prev = null, self = "p2", next = null),
                CursorBookmark(prev = null, self = "p4", next = null),
            )
        )

        val result = paginator.jumpForward(silentlyLoading = true, silentlyResult = true)
        assertNotNull(result)
        // p0 is visible → skipped; p2 is the next candidate outside the visible range.
        assertEquals("p2", result.first.self)
    }

    @Test
    fun jumpForward_returns_null_when_no_bookmarks() = runTest {
        val paginator = cursorPaginatorOf()
        assertNull(paginator.jumpForward(silentlyLoading = true, silentlyResult = true))
    }

    // ── successful load in unlimited capacity fetches a single page ─────────

    @Test
    fun unlimited_capacity_treats_any_size_as_filled() = runTest {
        val pages = listOf(
            FakeCursorBackend.Page(
                self = "only",
                prev = null,
                next = null,
                items = listOf("x"),
            ),
        )
        val backend = FakeCursorBackend(pages = pages)
        val core = com.jamal_aliev.paginator.CursorPagingCore<String>(initialCapacity = 0)
        val paginator = com.jamal_aliev.paginator.CursorPaginator<String>(core = core) { cursor ->
            backend.loadResult(cursor)
        }
        paginator.restart(silentlyLoading = true, silentlyResult = true)
        val state = paginator.cache.getStateOf("only")
        assertTrue(
            state is SuccessPage,
            "any non-empty data is a filled success under unlimited cap"
        )
        assertEquals(1, state.data.size)
    }
}
