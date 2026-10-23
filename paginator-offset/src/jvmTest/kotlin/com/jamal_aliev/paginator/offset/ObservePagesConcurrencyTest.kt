package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.cache.eviction.MostRecentPagingCache
import com.jamal_aliev.paginator.offset.extension.observePages
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * observePages on a multi-threaded dispatcher (viewModelScope + Dispatchers.Default / IO): navigation,
 * refresh and table changes race; the job must survive and the pages end up live and in sync.
 */
class ObservePagesConcurrencyTest {

    @Test
    fun observePages_survives_navigation_and_table_changes_on_Dispatchers_Default() = runBlocking {
        val errors: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
        repeat(20) { round ->
            val rows = MutableStateFlow(List(120) { "r$it" })
            val active = ConcurrentHashMap<Int, AtomicInteger>()
            val handler = CoroutineExceptionHandler { _, e -> errors += e }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
            val paginator = MutablePaginator<String>(
                core = PagingCore(), // plain InMemoryPagingCache: rules out the pre-existing LRU races
            ) { error("unused") }.apply { core.resize(capacity = 3, resize = false, silently = true) }
            val job = paginator.observePages(scope, onError = { _, e -> errors += e }) { page ->
                rows.map { it.drop((page - 1) * 3).take(3) }
                    .onStart { active.getOrPut(page) { AtomicInteger() }.incrementAndGet() }
                    .onCompletion { active[page]!!.decrementAndGet() }
            }
            val random = Random(round)
            withTimeout(30_000) {
                listOf(
                    scope.launch {
                        repeat(300) {
                            try {
                                paginator.jump(BookmarkInt(random.nextInt(1, 40)))
                                paginator.goNextPage()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                errors += e
                            }
                        }
                    },
                    scope.launch {
                        repeat(300) {
                            try {
                                paginator.refresh(listOf(random.nextInt(1, 40)))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                errors += e
                            }
                        }
                    },
                    scope.launch {
                        repeat(2000) { i ->
                            rows.update { list -> list.toMutableList().also { it[i % list.size] = "v$i" } }
                        }
                    },
                ).joinAll()
            }
            delay(300)
            synchronized(errors) { errors.take(3).forEach { System.err.println("ERR round $round"); it.printStackTrace() } }
            assertTrue(job.isActive, "round $round: the observePages job died; errors=${errors.take(3)}")
            val pages = paginator.cache.pages
            for (page in pages) {
                val state = paginator.cache.peekStateOf(page)
                if (state !is OffsetPageState.Success) continue
                assertEquals(rows.value.drop((page - 1) * 3).take(3), state.data, "round $round page $page stale")
            }
            val live = active.filterValues { it.get() > 0 }.keys.sorted()
            val leaked = live.filter { it !in pages }
            assertTrue(leaked.isEmpty(), "round $round: queries of uncached pages still open: $leaked (cached $pages)")
            assertTrue(
                active.values.all { it.get() <= 1 },
                "round $round: duplicate queries ${active.filterValues { it.get() > 1 }}",
            )
            scope.cancel()
        }
        assertTrue(errors.isEmpty(), "errors: ${errors.take(5)}")
    }

    /** Baseline: the same navigation workload WITHOUT observePages (plain load). Separates pre-existing races. */
    @Test
    fun baseline_without_observePages_on_Dispatchers_Default() = runBlocking {
        val errors: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
        repeat(20) { round ->
            val handler = CoroutineExceptionHandler { _, e -> errors += e }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
            val paginator = MutablePaginator<String>(
                core = PagingCore(), // plain InMemoryPagingCache: rules out the pre-existing LRU races
            ) { page -> kotlinx.coroutines.yield(); com.jamal_aliev.paginator.offset.load.LoadResult(MutableList(3) { "p${page}_$it" }) }
                .apply { core.resize(capacity = 3, resize = false, silently = true) }
            // a snapshot collector, like any UI
            scope.launch { paginator.core.snapshot.collect { } }
            val random = Random(round)
            withTimeout(30_000) {
                listOf(
                    scope.launch {
                        repeat(300) {
                            try {
                                paginator.jump(BookmarkInt(random.nextInt(1, 40)))
                                paginator.goNextPage()
                            } catch (e: CancellationException) { throw e } catch (e: Throwable) { errors += e }
                        }
                    },
                    scope.launch {
                        repeat(300) {
                            try {
                                paginator.refresh(listOf(random.nextInt(1, 40)))
                            } catch (e: CancellationException) { throw e } catch (e: Throwable) { errors += e }
                        }
                    },
                ).joinAll()
            }
            scope.cancel()
        }
        synchronized(errors) { errors.take(3).forEach { System.err.println("BASELINE-ERR"); it.printStackTrace() } }
        assertTrue(errors.isEmpty(), "errors: ${errors.take(5)}")
    }
}
