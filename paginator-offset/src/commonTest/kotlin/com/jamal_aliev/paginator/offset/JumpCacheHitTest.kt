package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.cache.InMemoryPagingCache
import com.jamal_aliev.paginator.offset.cache.persistent.PersistentPagingCache
import com.jamal_aliev.paginator.offset.load.LoadResult
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class JumpCacheHitTest {

    private class FakePersistentCache<T> : PersistentPagingCache<T> {
        val store = mutableMapOf<Int, OffsetPageState<T>>()
        override suspend fun save(state: OffsetPageState<T>) {
            store[state.page] = state
        }

        override suspend fun load(page: Int): OffsetPageState<T>? = store[page]
        override suspend fun loadAll(): List<OffsetPageState<T>> = store.values.toList()
        override suspend fun remove(page: Int) {
            store.remove(page)
        }

        override suspend fun clear() {
            store.clear()
        }
    }

    /**
     * Regression: the cache-hit fast path in [jump] must keep `asFlow()` (the full cache flow)
     * in sync, just like the load path does. When a jump resolves via the persistent (L2) cache,
     * the page is promoted into L1, so the cache content changes and subscribers must be notified.
     */
    @Test
    fun `cache-hit jump promoting a page from L2 updates the cache flow`() = runTest {
        val persistent = FakePersistentCache<String>()
        // Page 2 lives only in L2 (persistent), never loaded into L1.
        persistent.store[2] = OffsetPageState.Success(page = 2, data = listOf("a", "b", "c"))

        val paginator = MutablePaginator(
            core = PagingCore(
                cache = InMemoryPagingCache(),
                persistentCache = persistent,
                initialCapacity = 3,
            )
        ) { page -> LoadResult(List(3) { "p${page}_$it" }) }

        paginator.core.asFlow() // enable the full cache flow

        // L1 miss -> promoted from L2 -> filled-success -> cache-hit fast path.
        paginator.jump(
            BookmarkInt(2),
            enableCacheFlow = true,
            silentlyLoading = true,
            silentlyResult = true,
        )

        val cacheFlowPages = paginator.core.asFlow().first().map { it.page }
        assertTrue(
            2 in cacheFlowPages,
            "cache-hit jump must refresh asFlow() after L2->L1 promotion, was $cacheFlowPages",
        )
    }
}
