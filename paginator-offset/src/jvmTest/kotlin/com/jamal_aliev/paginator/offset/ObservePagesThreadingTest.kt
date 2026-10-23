package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.extension.observePages
import com.jamal_aliev.paginator.offset.load.LoadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObservePagesThreadingTest {

    private val callerThread = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    /**
     * Live writes run on the observePages scope's dispatcher under the (internal) navigation lock;
     * element CRUD, `removeState`, `resize`, `release` and transaction rollback take no lock. So a
     * caller doing CRUD runs observePages on its own thread (`viewModelScope` on Main): live writes
     * then only interleave at suspension points, never inside a CRUD call. The table itself changes
     * on another thread.
     */
    @Test
    fun crud_on_the_observing_thread_does_not_race_live_writes() = runBlocking(callerThread) {
        val errors: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
        val rows = MutableStateFlow(List(60) { "r$it" })
        val scope = CoroutineScope(SupervisorJob() + callerThread)
        val paginator = MutablePaginator<String> { page -> LoadResult(rows.value.drop((page - 1) * 3).take(3)) }
            .apply { core.resize(capacity = 3, resize = false, silently = true) }
        paginator.observePages(scope, onError = { _, e -> errors += e }) { page ->
            rows.map { it.drop((page - 1) * 3).take(3) }
        }
        paginator.jump(BookmarkInt(1))
        repeat(8) { paginator.goNextPage() }
        val writer = CoroutineScope(Dispatchers.Default).launch {
            repeat(200_000) { i -> rows.update { it.toMutableList().also { l -> l[i % 27] = "v$i" } } }
        }
        withTimeout(30_000) {
            // The caller's own thread, shared with observePages: plain CRUD, as any UI would do.
            repeat(200_000) { i ->
                try {
                    paginator.setElement(element = "crud$i", page = 1 + i % 9, index = i % 3, silently = true)
                    paginator.removeState(9, silently = true)
                    paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
                    for (pg in paginator.cache.pages) {
                        val st = paginator.cache.peekStateOf(pg)
                        if (st != null && st.page != pg) errors += IllegalStateException("page $pg holds state of ${st.page}")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (e !is com.jamal_aliev.paginator.core.exception.LockedException) errors += e
                }
            }
        }
        writer.join()
        delay(200)
        scope.cancel()
        assertTrue(errors.isEmpty(), "races: ${errors.take(5).map { it.toString() }}")
        val pages = paginator.cache.pages
        assertEquals(pages.sorted().distinct(), pages, "cache structure corrupted: $pages")
    }

    @Test
    fun unconfined_dispatcher_works() = runBlocking {
        val rows = MutableStateFlow(List(30) { "r$it" })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val paginator = MutablePaginator<String> { page -> LoadResult(rows.value.drop((page - 1) * 3).take(3)) }
            .apply { core.resize(capacity = 3, resize = false, silently = true) }
        paginator.observePages(scope) { page -> rows.map { it.drop((page - 1) * 3).take(3) } }
        withTimeout(5_000) {
            paginator.jump(BookmarkInt(1))
            paginator.goNextPage()
            paginator.refresh(listOf(1, 2))
            rows.update { it.toMutableList().also { l -> l[4] = "edited" } }
            paginator.restart()
            paginator.goNextPage()
        }
        assertEquals("edited", paginator.cache.peekStateOf(2)!!.data[1])
        scope.cancel()
    }

    @Test
    fun cancelled_refresh_on_Default_leaves_page_live() = runBlocking {
        var lost = 0
        repeat(1000) {
            val rows = MutableStateFlow(List(30) { "r$it" })
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val subscribed = java.util.concurrent.atomic.AtomicInteger()
            val active2 = java.util.concurrent.atomic.AtomicInteger()
            val paginator = MutablePaginator<String> { page ->
                // The second load of page 2 (the refresh) hangs until it is cancelled.
                if (page == 2 && subscribed.incrementAndGet() == 2) gate.await()
                LoadResult(rows.value.drop((page - 1) * 3).take(3))
            }.apply { core.resize(capacity = 3, resize = false, silently = true) }
            paginator.observePages(scope) { page ->
                rows.map { it.drop((page - 1) * 3).take(3) }
                    .onStart { if (page == 2) active2.incrementAndGet() }
                    .onCompletion { if (page == 2) active2.decrementAndGet() }
            }
            paginator.jump(BookmarkInt(1))
            paginator.goNextPage()
            val refresh = scope.launch { paginator.refresh(listOf(2)) }
            while (subscribed.get() < 2) delay(1)
            refresh.cancel()
            refresh.join()
            delay(50)
            if (active2.get() != 1) { lost++; println("PROBE lost: active=${active2.get()} state=${paginator.cache.peekStateOf(2)}") }
            scope.cancel()
        }
        assertEquals(0, lost, "page 2 left without a live query after a cancelled refresh")
    }
}
