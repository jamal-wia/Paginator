package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.core.extension.isProgressState
import com.jamal_aliev.paginator.core.extension.isSuccessState
import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.cache.eviction.QueuedPagingCache
import com.jamal_aliev.paginator.offset.load.LoadResult
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `refresh` releases `navigationMutex` while its loads are in flight (phase 2) and re-acquires it
 * to commit the results (phase 3). Anything that touches a refreshed page in between — restart,
 * release, eviction, a jump, an external write — must win over the stale in-flight result: the
 * commit (and the cancellation rollback) only applies to a page that still holds the Progress
 * state the refresh itself installed.
 */
class RefreshConcurrentMutationTest {

    /**
     * A source whose data is tagged with the [version] current when the load **started**, and
     * whose first load of a page listed in [gates] hangs until that gate is completed.
     */
    private class GatedSource {
        var version = 1
        val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()

        fun gate(page: Int): CompletableDeferred<Unit> =
            CompletableDeferred<Unit>().also { gates[page] = it }

        val load: suspend Paginator<String>.(Int) -> LoadResult<String> = { page ->
            val startedAt = version
            gates.remove(page)?.await()
            LoadResult(MutableList(core.capacity) { "v${startedAt}_p${page}_$it" })
        }
    }

    private suspend fun populated(
        source: GatedSource,
        core: PagingCore<String> = PagingCore(),
        pageCount: Int = 3,
    ): MutablePaginator<String> {
        val paginator = MutablePaginator(core = core, load = source.load)
        paginator.core.resize(capacity = 3, resize = false, silently = true)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        repeat(pageCount - 1) {
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        }
        return paginator
    }

    @Test
    fun `restart during refresh does not resurrect the refreshed pages`() = runTest {
        val source = GatedSource()
        val paginator = populated(source)
        val gate = source.gate(3)

        val refresh = launch { paginator.refresh(pages = listOf(3)) }
        testScheduler.advanceUntilIdle()

        source.version = 2
        paginator.restart(silentlyLoading = true, silentlyResult = true)
        gate.complete(Unit)
        refresh.join()

        assertEquals(listOf(1), paginator.cache.pages)
        assertEquals("v2_p1_0", paginator.cache.getStateOf(1)!!.data.first())
    }

    @Test
    fun `release during refresh leaves the cache empty`() = runTest {
        val source = GatedSource()
        val paginator = populated(source)
        val gate = source.gate(2)

        val refresh = launch { paginator.refresh(pages = listOf(2, 3)) }
        testScheduler.advanceUntilIdle()

        paginator.release(silently = true)
        gate.complete(Unit)
        refresh.join()

        assertEquals(emptyList(), paginator.cache.pages)
        assertEquals(0, paginator.cache.startContextPage)
        assertEquals(0, paginator.cache.endContextPage)
    }

    @Test
    fun `a page evicted during refresh is not written back`() = runTest {
        val source = GatedSource()
        val paginator = populated(
            source = source,
            core = PagingCore(cache = QueuedPagingCache(maxSize = 3, protectContextWindow = false)),
        )
        val gate = source.gate(1)

        val refresh = launch { paginator.refresh(pages = listOf(1)) }
        testScheduler.advanceUntilIdle()

        // Loading page 4 pushes the cache over maxSize; FIFO evicts page 1 (the oldest insert).
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        assertNull(paginator.cache.getStateOf(1))

        gate.complete(Unit)
        refresh.join()

        assertNull(paginator.cache.getStateOf(1), "evicted page must stay evicted")
        assertEquals(listOf(2, 3, 4), paginator.cache.pages)
    }

    @Test
    fun `a jump that reloads the refreshing page wins over the stale refresh result`() = runTest {
        val source = GatedSource()
        val paginator = populated(source)
        val gate = source.gate(2)

        val refresh = launch { paginator.refresh(pages = listOf(2)) }
        testScheduler.advanceUntilIdle()

        // Page 2 is Progress, so jump loads it itself — with newer data.
        source.version = 2
        paginator.jump(BookmarkInt(2), silentlyLoading = true, silentlyResult = true)
        assertEquals("v2_p2_0", paginator.cache.getStateOf(2)!!.data.first())

        gate.complete(Unit)
        refresh.join()

        val page2 = paginator.cache.getStateOf(2)!!
        assertTrue(page2.isSuccessState())
        assertEquals("v2_p2_0", page2.data.first())
    }

    @Test
    fun `an external write during refresh wins over the stale refresh result`() = runTest {
        val source = GatedSource()
        val paginator = populated(source)
        val gate = source.gate(2)

        val refresh = launch { paginator.refresh(pages = listOf(2)) }
        testScheduler.advanceUntilIdle()

        paginator.core.setState(
            OffsetPageState.Success(page = 2, data = mutableListOf("x0", "x1", "x2")),
            silently = true,
        )
        gate.complete(Unit)
        refresh.join()

        assertEquals(listOf("x0", "x1", "x2"), paginator.cache.getStateOf(2)!!.data)
    }

    @Test
    fun `pages relabelled by removeState during refresh keep their new content`() = runTest {
        val source = GatedSource()
        val paginator = populated(source, pageCount = 4)
        val gate = source.gate(3)

        val refresh = launch { paginator.refresh(pages = listOf(3)) }
        testScheduler.advanceUntilIdle()

        // Removing page 2 relabels the old page 4 to page 3 — a different state with its own id.
        paginator.removeState(2, silently = true)
        gate.complete(Unit)
        refresh.join()

        assertEquals(listOf(1, 2, 3), paginator.cache.pages)
        assertEquals("v1_p4_0", paginator.cache.getStateOf(3)!!.data.first())
    }

    @Test
    fun `CRUD on the refreshing page does not leave it stuck in Progress`() = runTest {
        val source = GatedSource()
        val paginator = populated(source)
        val gate = source.gate(2)

        val refresh = launch { paginator.refresh(pages = listOf(2)) }
        testScheduler.advanceUntilIdle()

        // copy() keeps the page id, so the refresh still owns the page and commits over it.
        paginator.setElement(element = "edited", page = 2, index = 0, silently = true)
        assertTrue(paginator.cache.getStateOf(2).isProgressState())

        gate.complete(Unit)
        refresh.join()

        assertTrue(paginator.cache.getStateOf(2).isSuccessState())
    }

    @Test
    fun `cancelling a refresh does not restore a page removed in the meantime`() = runTest {
        val source = GatedSource()
        val paginator = populated(source)
        source.gate(3)

        val refresh = launch { paginator.refresh(pages = listOf(3)) }
        testScheduler.advanceUntilIdle()

        paginator.restart(silentlyLoading = true, silentlyResult = true)
        refresh.cancel()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(1), paginator.cache.pages)
    }

    @Test
    fun `cancelling a refresh still restores pages it owns`() = runTest {
        val source = GatedSource()
        val paginator = populated(source)
        val page3Before = paginator.cache.getStateOf(3)!!
        source.gate(3)

        val refresh = launch { paginator.refresh(pages = listOf(3)) }
        testScheduler.advanceUntilIdle()
        assertTrue(paginator.cache.getStateOf(3).isProgressState())

        refresh.cancel()
        testScheduler.advanceUntilIdle()

        assertEquals(page3Before, paginator.cache.getStateOf(3))
    }
}
