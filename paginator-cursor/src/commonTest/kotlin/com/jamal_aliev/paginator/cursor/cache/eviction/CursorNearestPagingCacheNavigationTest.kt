package com.jamal_aliev.paginator.cursor.cache.eviction

import com.jamal_aliev.paginator.core.cache.eviction.CacheEvictionListener
import com.jamal_aliev.paginator.core.extension.isErrorState
import com.jamal_aliev.paginator.core.extension.isSuccessState
import com.jamal_aliev.paginator.cursor.CursorPaginator
import com.jamal_aliev.paginator.cursor.CursorPagingCore
import com.jamal_aliev.paginator.cursor.FakeCursorBackend
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.load.CursorLoadResult
import com.jamal_aliev.paginator.cursor.page.CursorPageState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [CursorNearestPagingCache] (`maxSize = 3`, `capacity = 3`) driven by a real paginator over the
 * chain `p0 <-> p1 <-> ... <-> p7`: the window follows navigation in both directions, and the
 * context window and the published snapshot stay in line with the cache. There is no cursor
 * counterpart of `observePages`, so there is no live subscription test here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CursorNearestPagingCacheNavigationTest {

    private class Env(
        val paginator: CursorPaginator<String, String>,
        val cache: CursorNearestPagingCache<String, String>,
        /** `self` of the pages evicted by the strategy, in order. */
        val evicted: MutableList<String>,
        val gates: MutableMap<String, CompletableDeferred<Unit>>,
        val failing: MutableSet<String>,
        /** Number of items a page returns instead of the capacity: 2 makes it incomplete. */
        val lengths: MutableMap<String, Int>,
    )

    private fun createEnv(maxSize: Int = 3): Env {
        val backend = FakeCursorBackend(FakeCursorBackend.defaultPages(pageCount = 8, capacity = 3))
        val evicted = mutableListOf<String>()
        val cache = CursorNearestPagingCache<String, String>(
            maxSize = maxSize,
            evictionListener = CacheEvictionListener { evicted += (it as CursorPageState<*, *>).bookmark.self as String },
        )
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val failing = mutableSetOf<String>()
        val lengths = mutableMapOf<String, Int>()
        val core = CursorPagingCore<String, String>(cache = cache, initialCapacity = 3)
        val paginator = CursorPaginator<String, String>(core = core) { cursor ->
            cursor?.self?.let { gates[it] }?.await()
            if (cursor?.self in failing) error("load of ${cursor?.self} failed")
            val result = backend.loadResult(cursor)
            val length = lengths[result.bookmark.self] ?: return@CursorPaginator result
            CursorLoadResult(data = result.data.take(length), bookmark = result.bookmark)
        }
        return Env(paginator, cache, evicted, gates, failing, lengths)
    }

    private fun cb(self: String) = CursorBookmark<String>(prev = null, self = self, next = null)

    private fun Env.cachedSelves(): List<String> = cache.cursors.map { it.self }.sorted()

    private suspend fun CursorPaginator<String, String>.snapshotSelves(): List<String> =
        core.snapshot.first().map { it.bookmark.self }

    private fun CursorPaginator<String, String>.window(): Pair<String?, String?> =
        core.startContextCursor?.self to core.endContextCursor?.self

    /** Loads pages p0..p[count - 1] by visible navigation. */
    private suspend fun CursorPaginator<String, String>.loadForward(count: Int) {
        jump(cb("p0"))
        repeat(count - 1) { goNextPage() }
    }

    @Test
    fun forward_navigation_keeps_a_three_page_window() = runTest {
        val env = createEnv()

        env.paginator.loadForward(4)

        assertEquals(listOf("p1", "p2", "p3"), env.cachedSelves())
        assertEquals("p1" to "p3", env.paginator.window())
        assertEquals(listOf("p1", "p2", "p3"), env.paginator.snapshotSelves())
        assertEquals(listOf("p0"), env.evicted)
    }

    @Test
    fun backward_navigation_evicts_the_tail_edge() = runTest {
        val env = createEnv()
        env.paginator.loadForward(4)

        env.paginator.goPreviousPage()

        assertEquals(listOf("p0", "p1", "p2"), env.cachedSelves())
        assertEquals("p0" to "p2", env.paginator.window())
        assertEquals(listOf("p0", "p1", "p2"), env.paginator.snapshotSelves())
        assertEquals(listOf("p0", "p3"), env.evicted)
    }

    @Test
    fun error_on_the_forward_edge_keeps_window_and_snapshot() = runTest {
        val env = createEnv()
        env.paginator.loadForward(3)
        env.failing += "p3"

        val failed = env.paginator.goNextPage()

        assertTrue(failed.isErrorState())
        assertEquals(listOf("p0", "p1", "p2", "p3"), env.cachedSelves(), "an error page evicts nothing")
        assertEquals("p0" to "p2", env.paginator.window())
        assertEquals(listOf("p0", "p1", "p2", "p3"), env.paginator.snapshotSelves())
        assertEquals(emptyList(), env.evicted)

        env.failing -= "p3"
        val retried = env.paginator.goNextPage()

        assertTrue(retried.isSuccessState())
        assertEquals(listOf("p1", "p2", "p3"), env.cachedSelves())
        assertEquals("p1" to "p3", env.paginator.window())
        assertEquals(listOf("p1", "p2", "p3"), env.paginator.snapshotSelves())
        assertEquals(listOf("p0"), env.evicted)
    }

    @Test
    fun incomplete_tail_page_reloads_in_place_without_eviction() = runTest {
        val env = createEnv()
        env.lengths["p3"] = 2 // the tail page holds two of three items
        env.paginator.loadForward(3)

        val first = env.paginator.goNextPage() // enters p3 and evicts p0
        assertEquals(2, first.data.size)
        assertEquals(listOf("p1", "p2", "p3"), env.cachedSelves())
        assertEquals(listOf("p0"), env.evicted)

        env.lengths["p3"] = 3
        val reloaded = env.paginator.goNextPage() // reloads p3 in place

        assertEquals("p3", reloaded.bookmark.self)
        assertEquals(3, reloaded.data.size)
        assertEquals(listOf("p1", "p2", "p3"), env.cachedSelves())
        assertEquals(listOf("p0"), env.evicted, "the reload of p3 must not evict anything")
        assertEquals("p1" to "p3", env.paginator.window())
        assertEquals(listOf("p1", "p2", "p3"), env.paginator.snapshotSelves())
    }

    @Test
    fun cancelled_forward_load_keeps_the_previous_window() = runTest {
        val env = createEnv()
        env.paginator.loadForward(3)
        env.gates["p3"] = CompletableDeferred()

        val job = launch { env.paginator.goNextPage() }
        runCurrent()
        job.cancelAndJoin()

        assertEquals(listOf("p0", "p1", "p2"), env.cachedSelves())
        assertEquals("p0" to "p2", env.paginator.window())
        assertEquals(listOf("p0", "p1", "p2"), env.paginator.snapshotSelves())
        assertEquals(emptyList(), env.evicted)
    }

    @Test
    fun cancelled_reload_restoring_the_old_success_evicts_nothing_when_the_cache_fits() = runTest {
        val env = createEnv()
        env.lengths["p3"] = 2 // the tail page holds two of three items
        env.paginator.loadForward(4)
        assertEquals(listOf("p1", "p2", "p3"), env.cachedSelves())
        env.gates["p3"] = CompletableDeferred()

        val job = launch { env.paginator.goNextPage() } // reloads p3 in place
        runCurrent()
        job.cancelAndJoin()

        // the restored Success is written over the Progress and looks like a completed load to the cache
        assertEquals(listOf("p1", "p2", "p3"), env.cachedSelves())
        assertEquals(listOf("p0"), env.evicted, "only the first entry into p3 evicted a page")
        assertEquals(listOf("p3_item0", "p3_item1"), env.cache.peekStateOf("p3")!!.data, "the old state is back")
        // the incomplete p3 is not part of the window; the snapshot shows it after the window
        assertEquals("p1" to "p2", env.paginator.window())
        assertEquals(listOf("p1", "p2", "p3"), env.paginator.snapshotSelves())
    }

    @Test
    fun cancelled_reload_restoring_the_old_success_evicts_no_more_than_the_completed_reload_would() = runTest {
        suspend fun TestScope.run(cancel: Boolean): Pair<List<String>, List<String>> {
            val env = createEnv()
            env.lengths["p3"] = 2
            env.paginator.loadForward(4)
            // a page written outside a load (CRUD style) leaves the cache over maxSize without evicting
            val head = CursorBookmark(prev = null, self = "p0", next = "p1")
            env.cache.setState(head, CursorPageState.Success(head, listOf("a", "b", "c")), silently = true)
            assertEquals(listOf("p0", "p1", "p2", "p3"), env.cachedSelves())
            env.evicted.clear()
            if (cancel) {
                env.gates["p3"] = CompletableDeferred()
                val job = launch { env.paginator.goNextPage() }
                runCurrent()
                job.cancelAndJoin()
            } else {
                env.paginator.goNextPage()
            }
            return env.cachedSelves() to env.evicted.toList()
        }

        val completed = run(cancel = false)
        val cancelled = run(cancel = true)

        assertEquals(listOf("p1", "p2", "p3") to listOf("p0"), completed)
        assertEquals(completed, cancelled, "a cancelled reload must evict exactly what the completed one does")
    }
}
