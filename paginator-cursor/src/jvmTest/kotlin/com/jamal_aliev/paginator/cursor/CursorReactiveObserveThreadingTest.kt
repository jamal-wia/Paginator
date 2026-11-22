package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.core.cache.reactive.InitialSyncPolicy
import com.jamal_aliev.paginator.core.exception.LockedException
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.cache.reactive.CursorInsertPosition
import com.jamal_aliev.paginator.cursor.cache.reactive.CursorPaginatorReactiveCache
import com.jamal_aliev.paginator.cursor.cache.reactive.CursorReactiveEvent
import com.jamal_aliev.paginator.cursor.exception.EndOfCursorFeedException
import com.jamal_aliev.paginator.cursor.extension.observe
import com.jamal_aliev.paginator.cursor.load.CursorLoadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * observe() applies its CRUD under the navigation lock, so a background dispatcher
 * (viewModelScope + Dispatchers.Default / a Room flow on IO) does not race navigation that the
 * caller runs on its own thread.
 */
class CursorReactiveObserveThreadingTest {

    private data class Item(val id: Int, val label: String)

    @Test
    fun observe_on_Default_does_not_race_navigation_on_the_caller_thread() = runBlocking {
        val errors: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
        val channel = Channel<CursorReactiveEvent<Item, Int>>(Channel.UNLIMITED)
        val source = object : CursorPaginatorReactiveCache<Item, Int> {
            override fun identity(item: Item) = item.id
            override fun changes(): Flow<CursorReactiveEvent<Item, Int>> = channel.receiveAsFlow()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> errors += e })
        val paginator = MutableCursorPaginator<String, Item>(core = CursorPagingCore(initialCapacity = 3)) { cursor ->
            val page = when {
                cursor == null -> 0
                cursor.self is String -> (cursor.self as String).removePrefix("p").toInt()
                else -> 0
            }.let { p -> if (cursor?.prev != null && cursor.self == null) (cursor.prev as String).removePrefix("p").toInt() + 1 else p }
            CursorLoadResult(
                data = MutableList(3) { i -> Item(page * 3 + i + 1, "v") },
                // A bounded feed: the stress is the interleaving, not an ever-growing cache.
                bookmark = CursorBookmark(prev = if (page == 0) null else "p${page - 1}", self = "p$page", next = if (page == 19) null else "p${page + 1}"),
            )
        }
        paginator.jump(CursorBookmark(prev = null, self = "p0", next = null))
        paginator.observe(scope, source, initialSync = InitialSyncPolicy.None, onError = { errors += it })
        val producer = scope.launch {
            repeat(200_000) { i ->
                val id = 1 + i % 30
                channel.send(
                    when (i % 3) {
                        0 -> CursorReactiveEvent.Updated(Item(id, "u$i"))
                        1 -> CursorReactiveEvent.Removed(id)
                        else -> CursorReactiveEvent.Inserted(Item(id, "i$i"), CursorInsertPosition.Head)
                    }
                )
            }
        }
        withTimeout(60_000) {
            repeat(20_000) { i ->
                try {
                    when (i % 4) {
                        0, 1 -> paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
                        // The bridge writes on another thread under the navigation lock: read under it too.
                        2 -> paginator.withNavigationLock { paginator.cache.cursors.firstOrNull() }?.let {
                            paginator.refresh(listOf(it), loadingSilently = true, finalSilently = true)
                        }
                        else -> if (i % 40 == 3) paginator.jump(
                            CursorBookmark(prev = null, self = "p0", next = null), silentlyLoading = true, silentlyResult = true,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (e !is LockedException && e !is EndOfCursorFeedException) errors += e
                }
            }
        }
        producer.join()
        scope.cancel()
        assertTrue(errors.isEmpty(), "races (${errors.size}): ${errors.firstOrNull()?.stackTraceToString()}")
    }

    private data class Counter(val id: Int, val n: Int)

    /** Concurrent Patched events compose under the navigation lock: no increment is lost. */
    @Test
    fun concurrent_patches_on_Default_lose_no_update() = runBlocking {
        val producers = 8
        val perProducer = 10_000
        val errors: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
        val channel = Channel<CursorReactiveEvent<Counter, Int>>(Channel.UNLIMITED)
        val source = object : CursorPaginatorReactiveCache<Counter, Int> {
            override fun identity(item: Counter) = item.id
            override fun changes(): Flow<CursorReactiveEvent<Counter, Int>> = channel.receiveAsFlow()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> errors += e })
        val paginator = MutableCursorPaginator<String, Counter>(core = CursorPagingCore(initialCapacity = 3)) {
            CursorLoadResult(
                data = MutableList(3) { i -> Counter(i + 1, 0) },
                bookmark = CursorBookmark(prev = null, self = "p0", next = null),
            )
        }
        val head = CursorBookmark<String>(prev = null, self = "p0", next = null)
        paginator.jump(head)
        paginator.observe(scope, source, initialSync = InitialSyncPolicy.None, onError = { errors += it })
        val jobs = List(producers) {
            scope.launch {
                repeat(perProducer) { channel.send(CursorReactiveEvent.Patched(1) { c: Counter -> c.copy(n = c.n + 1) }) }
            }
        }
        suspend fun counter(): Int = paginator.withNavigationLock {
            paginator.cache.peekStateOf("p0")!!.data.first { it.id == 1 }.n
        }
        withTimeout(60_000) {
            // Navigate to the cached page while the bridge writes on another thread.
            while (counter() < producers * perProducer) {
                try {
                    paginator.jump(head, silentlyLoading = true, silentlyResult = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LockedException) {
                    // navigation raced the bridge's critical section; try again
                }
                delay(1)
            }
        }
        jobs.forEach { it.join() }
        scope.cancel()
        assertEquals(producers * perProducer, counter())
        assertTrue(errors.isEmpty(), "errors (${errors.size}): ${errors.firstOrNull()?.stackTraceToString()}")
    }
}
