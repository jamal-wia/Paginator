package com.jamal_aliev.paginator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoadControllerTest {

    @Test
    fun resolvesPendingRequestWithUserChoice() = runBlocking {
        val controller = LoadController()

        val load = async { controller.awaitChoice(page = 1) }
        controller.pending.first { it?.page == 1 }

        controller.resolve(LoadChoice.FULL)

        assertEquals(LoadChoice.FULL, load.await())
        assertNull(controller.pending.value)
    }

    @Test
    fun queuesConcurrentRequestsAndResolvesThemFifo() = runBlocking {
        val controller = LoadController()

        val first = async { controller.awaitChoice(page = 1) }
        controller.pending.first { it?.page == 1 }
        val second = async { controller.awaitChoice(page = 2) }
        controller.pending.first { it?.waitingCount == 2 }

        // The head (page 1) is shown first, even though page 2 is already queued.
        assertEquals(1, controller.pending.value?.page)

        controller.resolve(LoadChoice.INCOMPLETE)
        assertEquals(LoadChoice.INCOMPLETE, first.await())

        // Now page 2 becomes the head.
        controller.pending.first { it?.page == 2 }
        controller.resolve(LoadChoice.ERROR)
        assertEquals(LoadChoice.ERROR, second.await())
        assertNull(controller.pending.value)
    }

    @Test
    fun cancelledRequestIsRemovedFromQueue() = runBlocking {
        val controller = LoadController()

        val cancelled = async { controller.awaitChoice(page = 1) }
        controller.pending.first { it?.page == 1 }

        cancelled.cancel()
        try {
            cancelled.await()
        } catch (_: CancellationException) {
            // expected
        }

        // A follow-up request takes over cleanly.
        val next = async { controller.awaitChoice(page = 2) }
        controller.pending.first { it?.page == 2 }
        assertEquals(1, controller.pending.value?.waitingCount)

        controller.resolve(LoadChoice.EMPTY)
        assertEquals(LoadChoice.EMPTY, next.await())
        assertNull(controller.pending.value)
    }
}
