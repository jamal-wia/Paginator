package com.jamal_aliev.paginator.offset.cache.eviction

import com.jamal_aliev.paginator.core.cache.eviction.CacheEvictionListener
import com.jamal_aliev.paginator.core.extension.isErrorState
import com.jamal_aliev.paginator.core.extension.isSuccessState
import com.jamal_aliev.paginator.offset.MutablePaginator
import com.jamal_aliev.paginator.offset.PagingCore
import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.dsl.mutablePaginator
import com.jamal_aliev.paginator.offset.load.LoadResult
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [NearestPagingCache] (`maxSize = 3`, `capacity = 3`) driven by a real paginator: the window
 * follows navigation in both directions, and the context window and the published snapshot stay in
 * line with the cache.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearestPagingCacheNavigationTest {

    private class Env(
        val paginator: MutablePaginator<String>,
        val cache: NearestPagingCache<String>,
        /** Pages evicted by the strategy, in order. */
        val evicted: MutableList<Int>,
        val gates: MutableMap<Int, CompletableDeferred<Unit>>,
        val failing: MutableSet<Int>,
        /** Number of items a page returns instead of the capacity: 2 makes it incomplete. */
        val lengths: MutableMap<Int, Int>,
    )

    private fun createEnv(maxSize: Int = 3): Env {
        val evicted = mutableListOf<Int>()
        val cache = NearestPagingCache<String>(
            maxSize = maxSize,
            evictionListener = CacheEvictionListener { evicted += (it as OffsetPageState).page },
        )
        val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        val failing = mutableSetOf<Int>()
        val lengths = mutableMapOf<Int, Int>()
        val paginator = MutablePaginator(
            core = PagingCore(cache = cache, initialCapacity = 3)
        ) { page: Int ->
            gates[page]?.await()
            if (page in failing) error("load of page $page failed")
            LoadResult(List(lengths[page] ?: this.core.capacity) { "p${page}_item$it" })
        }
        return Env(paginator, cache, evicted, gates, failing, lengths)
    }

    private suspend fun MutablePaginator<String>.snapshotPages(): List<Int> =
        core.snapshot.first().map { it.page }

    /** Loads pages 1..[count] by visible navigation. */
    private suspend fun MutablePaginator<String>.loadForward(count: Int) {
        jump(BookmarkInt(1))
        repeat(count - 1) { goNextPage() }
    }

    @Test
    fun `forward navigation keeps a three page window`() = runTest {
        val env = createEnv()

        env.paginator.loadForward(4)

        assertEquals(listOf(2, 3, 4), env.cache.pages)
        assertEquals(2, env.paginator.core.startContextPage)
        assertEquals(4, env.paginator.core.endContextPage)
        assertEquals(listOf(2, 3, 4), env.paginator.snapshotPages())
        assertEquals(listOf(1), env.evicted)
    }

    @Test
    fun `backward navigation evicts the upper edge`() = runTest {
        val env = createEnv()
        env.paginator.loadForward(4)

        env.paginator.goPreviousPage()

        assertEquals(listOf(1, 2, 3), env.cache.pages)
        assertEquals(1, env.paginator.core.startContextPage)
        assertEquals(3, env.paginator.core.endContextPage)
        assertEquals(listOf(1, 2, 3), env.paginator.snapshotPages())
        assertEquals(listOf(1, 4), env.evicted)
    }

    @Test
    fun `error on the forward edge keeps window and snapshot`() = runTest {
        val env = createEnv()
        env.paginator.loadForward(3)
        env.failing += 4

        val failed = env.paginator.goNextPage()

        assertTrue(failed.isErrorState())
        assertEquals(listOf(1, 2, 3, 4), env.cache.pages, "an error page evicts nothing")
        assertEquals(1, env.paginator.core.startContextPage)
        assertEquals(3, env.paginator.core.endContextPage)
        assertEquals(listOf(1, 2, 3, 4), env.paginator.snapshotPages())
        assertTrue(env.cache.peekStateOf(4).isErrorState())
        assertEquals(emptyList(), env.evicted)

        env.failing -= 4
        val retried = env.paginator.goNextPage()

        assertTrue(retried.isSuccessState())
        assertEquals(listOf(2, 3, 4), env.cache.pages)
        assertEquals(2, env.paginator.core.startContextPage)
        assertEquals(4, env.paginator.core.endContextPage)
        assertEquals(listOf(2, 3, 4), env.paginator.snapshotPages())
        assertEquals(listOf(1), env.evicted)
    }

    @Test
    fun `incomplete last page reloads in place without eviction`() = runTest {
        val env = createEnv()
        env.lengths[4] = 2 // the last page holds two of three items
        env.paginator.loadForward(3)

        val first = env.paginator.goNextPage() // enters page 4 and evicts page 1
        assertEquals(2, first.data.size)
        assertEquals(listOf(2, 3, 4), env.cache.pages)
        assertEquals(listOf(1), env.evicted)

        env.lengths[4] = 3
        val reloaded = env.paginator.goNextPage() // reloads page 4 in place

        assertEquals(4, reloaded.page)
        assertEquals(3, reloaded.data.size)
        assertEquals(listOf(2, 3, 4), env.cache.pages)
        assertEquals(listOf(1), env.evicted, "the reload of page 4 must not evict anything")
        assertEquals(2, env.paginator.core.startContextPage)
        assertEquals(4, env.paginator.core.endContextPage)
        assertEquals(listOf(2, 3, 4), env.paginator.snapshotPages())
    }

    @Test
    fun `cancelled forward load keeps the previous window`() = runTest {
        val env = createEnv()
        env.paginator.loadForward(3)
        env.gates[4] = CompletableDeferred()

        val job = launch { env.paginator.goNextPage() }
        runCurrent()
        job.cancelAndJoin()

        assertEquals(listOf(1, 2, 3), env.cache.pages)
        assertEquals(1, env.paginator.core.startContextPage)
        assertEquals(3, env.paginator.core.endContextPage)
        assertEquals(listOf(1, 2, 3), env.paginator.snapshotPages())
        assertEquals(emptyList(), env.evicted)
    }

    @Test
    fun `cancelled reload restoring the old success evicts nothing when the cache fits`() = runTest {
        val env = createEnv()
        env.lengths[4] = 2 // the last page holds two of three items
        env.paginator.loadForward(4)
        assertEquals(listOf(2, 3, 4), env.cache.pages)
        env.gates[4] = CompletableDeferred()

        val job = launch { env.paginator.goNextPage() } // reloads page 4 in place
        runCurrent()
        job.cancelAndJoin()

        // the restored Success is written over the Progress and looks like a completed load to the cache
        assertEquals(listOf(2, 3, 4), env.cache.pages)
        assertEquals(listOf(1), env.evicted, "only the first entry into page 4 evicted a page")
        assertEquals(listOf("p4_item0", "p4_item1"), env.cache.peekStateOf(4)!!.data, "the old state is back")
        // the incomplete page 4 is not part of the window; the snapshot shows it after the window
        assertEquals(2, env.paginator.core.startContextPage)
        assertEquals(3, env.paginator.core.endContextPage)
        assertEquals(listOf(2, 3, 4), env.paginator.snapshotPages())
    }

    @Test
    fun `cancelled reload restoring the old success evicts no more than the completed reload would`() = runTest {
        suspend fun TestScope.run(cancel: Boolean): Pair<List<Int>, List<Int>> {
            val env = createEnv()
            // a page written outside a load (here: CRUD style) leaves the cache over maxSize without evicting
            env.lengths[4] = 2
            env.paginator.loadForward(4)
            env.cache.setState(OffsetPageState.Success(page = 1, data = listOf("a", "b", "c")), silently = true)
            assertEquals(listOf(1, 2, 3, 4), env.cache.pages)
            env.evicted.clear()
            if (cancel) {
                env.gates[4] = CompletableDeferred()
                val job = launch { env.paginator.goNextPage() }
                runCurrent()
                job.cancelAndJoin()
            } else {
                env.paginator.goNextPage()
            }
            return env.cache.pages to env.evicted.toList()
        }

        val completed = run(cancel = false)
        val cancelled = run(cancel = true)

        assertEquals(listOf(2, 3, 4) to listOf(1), completed)
        assertEquals(completed, cancelled, "a cancelled reload must evict exactly what the completed one does")
    }

    @Test
    fun `jump to a cached page keeps the cache bounded and the next load trims around the new window`() = runTest {
        val env = createEnv()
        env.paginator.loadForward(4)
        assertEquals(listOf(2, 3, 4), env.cache.pages)

        env.paginator.jump(BookmarkInt(3)) // a cache hit writes nothing, so nothing is evicted

        assertEquals(listOf(2, 3, 4), env.cache.pages)
        assertEquals(2, env.paginator.core.startContextPage)
        assertEquals(4, env.paginator.core.endContextPage)
        assertEquals(listOf(2, 3, 4), env.paginator.snapshotPages())
        assertEquals(listOf(1), env.evicted)

        env.paginator.goPreviousPage() // loads page 1 and evicts page 4, the farthest one

        assertEquals(listOf(1, 2, 3), env.cache.pages)
        assertEquals(listOf(1, 4), env.evicted)
        assertEquals(1, env.paginator.core.startContextPage)
        assertEquals(3, env.paginator.core.endContextPage)
        assertEquals(listOf(1, 2, 3), env.paginator.snapshotPages())
    }

    @Test
    fun `refresh evicts nothing inside the window`() = runTest {
        val env = createEnv()
        env.paginator.loadForward(3)

        env.paginator.refresh(pages = listOf(1, 2, 3))

        assertEquals(listOf(1, 2, 3), env.cache.pages)
        assertEquals(emptyList(), env.evicted)
        assertEquals(1, env.paginator.core.startContextPage)
        assertEquals(3, env.paginator.core.endContextPage)
        assertEquals(listOf(1, 2, 3), env.paginator.snapshotPages())
    }

    @Test
    fun `livePages subscription ends when its page is evicted`() = runTest {
        val rows = MutableStateFlow(List(60) { "r$it" })
        val active = mutableListOf<Int>()
        val paginator = mutablePaginator<String>(capacity = 3) {
            cache = NearestPagingCache(maxSize = 3)
            livePages(backgroundScope) { page ->
                rows.map { all -> all.drop((page - 1) * core.capacity).take(core.capacity) }
                    .onStart { active += page }
                    .onCompletion { active -= page }
            }
        }

        paginator.loadForward(4)
        runCurrent()
        assertEquals(listOf(2, 3, 4), active.sorted())

        paginator.goPreviousPage()
        runCurrent()

        assertEquals(listOf(1, 2, 3), active.sorted())
        assertFalse(4 in active, "page 4 left the cache and must not keep a live query")
    }
}
