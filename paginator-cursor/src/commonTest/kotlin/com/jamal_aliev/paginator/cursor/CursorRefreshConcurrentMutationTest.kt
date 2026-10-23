package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.core.extension.isProgressState
import com.jamal_aliev.paginator.core.extension.isSuccessState
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.cache.eviction.CursorQueuedPagingCache
import com.jamal_aliev.paginator.cursor.load.CursorLoadResult
import com.jamal_aliev.paginator.cursor.page.CursorPageState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cursor counterpart of the offset `RefreshConcurrentMutationTest`: `refresh` releases
 * `navigationMutex` while loading, so a page touched in between must not be overwritten (or
 * resurrected) by the stale in-flight result or by the cancellation rollback.
 */
class CursorRefreshConcurrentMutationTest {

    /**
     * Tags data with the [version] current when the load **started**; the first load of a
     * `self` listed in [gates] hangs until that gate is completed.
     */
    private class GatedSource {
        val backend = FakeCursorBackend()
        var version = 1
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

        fun gate(self: String): CompletableDeferred<Unit> =
            CompletableDeferred<Unit>().also { gates[self] = it }

        val load: suspend CursorPaginator<String, String>.(CursorBookmark<String>?) -> CursorLoadResult<String, String> =
            { cursor ->
                val startedAt = version
                val page = backend.resolve(cursor)
                gates.remove(page.self)?.await()
                CursorLoadResult(
                    data = page.items.map { "v${startedAt}_$it" }.toMutableList(),
                    bookmark = CursorBookmark(prev = page.prev, self = page.self, next = page.next),
                )
            }
    }

    private suspend fun populated(
        source: GatedSource,
        core: CursorPagingCore<String, String> = CursorPagingCore(initialCapacity = 3),
        pageCount: Int = 3,
    ): MutableCursorPaginator<String, String> {
        val paginator = MutableCursorPaginator(core = core, load = source.load)
        paginator.restart(silentlyLoading = true, silentlyResult = true)
        repeat(pageCount - 1) {
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        }
        return paginator
    }

    private fun MutableCursorPaginator<String, String>.cursorOf(self: String): CursorBookmark<String> =
        checkNotNull(cache.getCursorOf(self))

    private val MutableCursorPaginator<String, String>.selves: List<String>
        get() = cache.cursors.map { it.self }.sorted()

    @Test
    fun `restart during refresh does not resurrect the refreshed pages`() = runTest {
        val source = GatedSource()
        val paginator = populated(source)
        val gate = source.gate("p2")

        val refresh = launch { paginator.refresh(cursors = listOf(paginator.cursorOf("p2"))) }
        testScheduler.advanceUntilIdle()

        source.version = 2
        paginator.restart(silentlyLoading = true, silentlyResult = true)
        gate.complete(Unit)
        refresh.join()

        assertEquals(listOf("p0"), paginator.selves)
        assertEquals("v2_p0_item0", paginator.cache.getStateOf("p0")!!.data.first())
    }

    @Test
    fun `release during refresh leaves the cache empty`() = runTest {
        val source = GatedSource()
        val paginator = populated(source)
        val gate = source.gate("p1")

        val refresh = launch {
            paginator.refresh(cursors = listOf(paginator.cursorOf("p1"), paginator.cursorOf("p2")))
        }
        testScheduler.advanceUntilIdle()

        paginator.release(silently = true)
        gate.complete(Unit)
        refresh.join()

        assertEquals(emptyList(), paginator.selves)
    }

    @Test
    fun `a page evicted during refresh is not written back`() = runTest {
        val source = GatedSource()
        val paginator = populated(
            source = source,
            core = CursorPagingCore(
                cache = CursorQueuedPagingCache(maxSize = 3, protectContextWindow = false),
                initialCapacity = 3,
            ),
        )
        val gate = source.gate("p0")

        val refresh = launch { paginator.refresh(cursors = listOf(paginator.cursorOf("p0"))) }
        testScheduler.advanceUntilIdle()

        // Loading p3 pushes the cache over maxSize; FIFO evicts p0 (the oldest insert).
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        assertNull(paginator.cache.getStateOf("p0"))

        gate.complete(Unit)
        refresh.join()

        assertNull(paginator.cache.getStateOf("p0"), "evicted page must stay evicted")
        assertEquals(listOf("p1", "p2", "p3"), paginator.selves)
    }

    @Test
    fun `a jump that reloads the refreshing page wins over the stale refresh result`() = runTest {
        val source = GatedSource()
        val paginator = populated(source)
        val gate = source.gate("p1")

        val refresh = launch { paginator.refresh(cursors = listOf(paginator.cursorOf("p1"))) }
        testScheduler.advanceUntilIdle()

        source.version = 2
        paginator.jump(paginator.cursorOf("p1"), silentlyLoading = true, silentlyResult = true)
        assertEquals("v2_p1_item0", paginator.cache.getStateOf("p1")!!.data.first())

        gate.complete(Unit)
        refresh.join()

        val p1 = paginator.cache.getStateOf("p1")!!
        assertTrue(p1.isSuccessState())
        assertEquals("v2_p1_item0", p1.data.first())
    }

    @Test
    fun `an external write during refresh wins over the stale refresh result`() = runTest {
        val source = GatedSource()
        val paginator = populated(source)
        val gate = source.gate("p1")
        val p1Cursor = paginator.cursorOf("p1")

        val refresh = launch { paginator.refresh(cursors = listOf(p1Cursor)) }
        testScheduler.advanceUntilIdle()

        paginator.core.setState(
            cursor = p1Cursor,
            state = CursorPageState.Success(bookmark = p1Cursor, data = mutableListOf("x0", "x1", "x2")),
            silently = true,
        )
        gate.complete(Unit)
        refresh.join()

        assertEquals(listOf("x0", "x1", "x2"), paginator.cache.getStateOf("p1")!!.data)
    }

    @Test
    fun `cancelling a refresh does not restore a page removed in the meantime`() = runTest {
        val source = GatedSource()
        val paginator = populated(source)
        source.gate("p2")

        val refresh = launch { paginator.refresh(cursors = listOf(paginator.cursorOf("p2"))) }
        testScheduler.advanceUntilIdle()

        paginator.restart(silentlyLoading = true, silentlyResult = true)
        refresh.cancel()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("p0"), paginator.selves)
    }

    @Test
    fun `cancelling a refresh still restores pages it owns`() = runTest {
        val source = GatedSource()
        val paginator = populated(source)
        val p2Before = paginator.cache.getStateOf("p2")!!
        source.gate("p2")

        val refresh = launch { paginator.refresh(cursors = listOf(paginator.cursorOf("p2"))) }
        testScheduler.advanceUntilIdle()
        assertTrue(paginator.cache.getStateOf("p2").isProgressState())

        refresh.cancel()
        testScheduler.advanceUntilIdle()

        assertEquals(p2Before, paginator.cache.getStateOf("p2"))
    }
}
