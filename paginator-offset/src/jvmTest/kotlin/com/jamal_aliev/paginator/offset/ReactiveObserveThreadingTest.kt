package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.core.cache.reactive.InitialSyncPolicy
import com.jamal_aliev.paginator.core.cache.reactive.InsertPosition
import com.jamal_aliev.paginator.core.cache.reactive.PaginatorReactiveCache
import com.jamal_aliev.paginator.core.cache.reactive.ReactiveEvent
import com.jamal_aliev.paginator.core.exception.FinalPageExceededException
import com.jamal_aliev.paginator.core.exception.LockedException
import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.extension.observe
import com.jamal_aliev.paginator.offset.load.LoadResult
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
class ReactiveObserveThreadingTest {

    private data class Item(val id: Int, val label: String)

    @Test
    fun observe_on_Default_does_not_race_navigation_on_the_caller_thread() = runBlocking {
        val errors: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
        val channel = Channel<ReactiveEvent<Item, Int>>(Channel.UNLIMITED)
        val source = object : PaginatorReactiveCache<Item, Int> {
            override fun identity(item: Item) = item.id
            override fun changes(): Flow<ReactiveEvent<Item, Int>> = channel.receiveAsFlow()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> errors += e })
        val paginator = MutablePaginator<Item> { page ->
            LoadResult(MutableList(3) { i -> Item((page - 1) * 3 + i + 1, "v") })
        }.apply {
            core.resize(capacity = 3, resize = false, silently = true)
            finalPage = 20 // a bounded feed: the stress is the interleaving, not an ever-growing cache
        }
        paginator.jump(BookmarkInt(1))
        paginator.observe(scope, source, initialSync = InitialSyncPolicy.None, onError = { errors += it })
        val producer = scope.launch {
            repeat(200_000) { i ->
                val id = 1 + i % 30
                channel.send(
                    when (i % 3) {
                        0 -> ReactiveEvent.Updated(Item(id, "u$i"))
                        1 -> ReactiveEvent.Removed(id)
                        else -> ReactiveEvent.Inserted(Item(id, "i$i"), InsertPosition.Head)
                    }
                )
            }
        }
        withTimeout(60_000) {
            repeat(20_000) { i ->
                try {
                    when (i % 4) {
                        0 -> paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
                        1 -> paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
                        2 -> paginator.refresh(listOf(1), loadingSilently = true, finalSilently = true)
                        else -> if (i % 40 == 3) paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
                    }
                    for (pg in paginator.cache.pages) {
                        val st = paginator.cache.peekStateOf(pg)
                        if (st != null && st.page != pg) errors += IllegalStateException("page $pg holds state of ${st.page}")
                    }
                } catch (e: Throwable) {
                    if (e !is LockedException && e !is FinalPageExceededException) errors += e
                }
            }
        }
        producer.join()
        scope.cancel()
        assertTrue(errors.isEmpty(), "races (${errors.size}): ${errors.distinctBy { it::class }.take(6).map { it.toString() }}")
    }
}
