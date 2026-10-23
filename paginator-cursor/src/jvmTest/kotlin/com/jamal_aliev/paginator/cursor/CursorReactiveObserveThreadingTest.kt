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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.Test
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
                        2 -> paginator.cache.cursors.firstOrNull()?.let {
                            paginator.refresh(listOf(it), loadingSilently = true, finalSilently = true)
                        }
                        else -> if (i % 40 == 3) paginator.jump(
                            CursorBookmark(prev = null, self = "p0", next = null), silentlyLoading = true, silentlyResult = true,
                        )
                    }
                } catch (e: Throwable) {
                    if (e !is LockedException && e !is EndOfCursorFeedException) errors += e
                }
            }
        }
        producer.join()
        scope.cancel()
        assertTrue(errors.isEmpty(), "races (${errors.size}): ${errors.distinctBy { it::class }.take(6).map { it.toString() }}")
    }
}
