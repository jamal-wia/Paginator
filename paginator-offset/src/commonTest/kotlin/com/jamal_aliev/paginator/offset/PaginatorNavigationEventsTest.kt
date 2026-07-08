package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.core.navigation.PaginatorNavigationEvent
import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.load.LoadResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaginatorNavigationEventsTest {

    @Test
    fun jump_emits_a_Jumped_navigation_event_with_the_target() = runTest {
        val paginator = MutablePaginator<Int>(
            load = { page -> LoadResult(List(20) { page * 100 + it }) }
        )
        val events = mutableListOf<PaginatorNavigationEvent<BookmarkInt>>()

        val job = launch { paginator.navigationEvents.collect { events += it } }
        testScheduler.runCurrent() // let the collector subscribe before the jump emits

        paginator.jump(BookmarkInt(5))
        testScheduler.advanceUntilIdle()

        assertEquals(1, events.size)
        val event = events.single()
        assertTrue(event is PaginatorNavigationEvent.Jumped)
        assertEquals(5, event.target.page)

        job.cancel()
    }

    @Test
    fun goNextPage_does_not_emit_a_navigation_event() = runTest {
        val paginator = MutablePaginator<Int>(
            load = { page -> LoadResult(List(20) { page * 100 + it }) }
        )
        val events = mutableListOf<PaginatorNavigationEvent<BookmarkInt>>()

        val job = launch { paginator.navigationEvents.collect { events += it } }
        testScheduler.runCurrent()

        // Start the window, then append — appending must not produce a navigation event.
        paginator.jump(BookmarkInt(1))
        testScheduler.advanceUntilIdle()
        events.clear()

        paginator.goNextPage()
        testScheduler.advanceUntilIdle()

        assertTrue(events.isEmpty())

        job.cancel()
    }
}
