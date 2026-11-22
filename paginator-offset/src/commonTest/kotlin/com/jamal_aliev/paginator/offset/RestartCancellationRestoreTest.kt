package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.core.extension.isSuccessState
import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.load.LoadResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A cancelled `restart` puts the paginator back as it was before the call. `restart` clears the
 * cache, so restoring only the context window would leave a window over pages that are gone.
 */
class RestartCancellationRestoreTest {

    private class Env(val paginator: MutablePaginator<String>, val hangOnRestart: () -> Unit)

    private fun createEnv(): Env {
        val gate = CompletableDeferred<Unit>()
        var hang = false
        val paginator = MutablePaginator<String> { page ->
            if (hang && page == 1) {
                gate.await()
                error("unreachable")
            }
            LoadResult(MutableList(this.core.capacity) { "p${page}_item$it" })
        }
        paginator.core.resize(capacity = 3, resize = false, silently = true)
        return Env(paginator) { hang = true }
    }

    private suspend fun MutablePaginator<String>.loadPages(count: Int) {
        jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        repeat(count - 1) { goNextPage(silentlyLoading = true, silentlyResult = true) }
    }

    @Test
    fun `restart cancellation brings back the pages the restart erased`() = runTest {
        val env = createEnv()
        val paginator = env.paginator
        paginator.loadPages(count = 3)
        val dataBefore = (1..3).associateWith { paginator.cache.getStateOf(it)!!.data.toList() }

        env.hangOnRestart()
        val job = launch { paginator.restart(silentlyLoading = true, silentlyResult = true) }
        testScheduler.advanceUntilIdle()
        job.cancel()
        testScheduler.advanceUntilIdle()

        assertEquals(1, paginator.cache.startContextPage)
        assertEquals(3, paginator.cache.endContextPage)
        assertEquals(listOf(1, 2, 3), paginator.cache.pages)
        for (page in 1..3) {
            assertTrue(paginator.cache.getStateOf(page)!!.isSuccessState(), "page $page is success")
            assertEquals(dataBefore.getValue(page), paginator.cache.getStateOf(page)!!.data, "page $page data")
        }
    }

    @Test
    fun `restart cancellation publishes the restored window in the snapshot`() = runTest {
        val env = createEnv()
        val paginator = env.paginator
        paginator.loadPages(count = 3)

        env.hangOnRestart()
        val job = launch { paginator.restart(silentlyLoading = true, silentlyResult = true) }
        testScheduler.advanceUntilIdle()
        job.cancel()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(1, 2, 3), paginator.core.snapshot.first().map { it.page })
    }

    @Test
    fun `restart cancellation keeps the dirty marks`() = runTest {
        val env = createEnv()
        val paginator = env.paginator
        paginator.loadPages(count = 3)
        paginator.core.markDirty(2)

        env.hangOnRestart()
        val job = launch { paginator.restart(silentlyLoading = true, silentlyResult = true) }
        testScheduler.advanceUntilIdle()
        job.cancel()
        testScheduler.advanceUntilIdle()

        assertEquals(setOf(2), paginator.core.dirtyPages)
    }
}
