package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.cache.persistent.CursorPersistentPagingCache
import com.jamal_aliev.paginator.cursor.load.CursorLoadResult
import com.jamal_aliev.paginator.cursor.page.CursorPageState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class CursorJumpCacheHitTest {

    private class FakeCursorPersistentCache<K : Any, T> : CursorPersistentPagingCache<K, T> {
        val store = mutableMapOf<K, Pair<CursorBookmark<K>, CursorPageState<K, T>>>()
        override suspend fun save(cursor: CursorBookmark<K>, state: CursorPageState<K, T>) {
            store[cursor.self] = cursor to state
        }
        override suspend fun load(self: K): Pair<CursorBookmark<K>, CursorPageState<K, T>>? = store[self]
        override suspend fun loadAll(): List<Pair<CursorBookmark<K>, CursorPageState<K, T>>> = store.values.toList()
        override suspend fun remove(self: K) { store.remove(self) }
        override suspend fun clear() { store.clear() }
    }

    /**
     * Regression: the cache-hit fast path in cursor [jump] must keep `asFlow()` in sync, like the
     * load path does. A jump resolved via the persistent (L2) cache promotes the page into L1, so
     * the cache content changes and subscribers must be notified. (Cursor analog of the offset fix.)
     */
    @Test
    fun cache_hit_jump_promoting_a_page_from_L2_updates_the_cache_flow() = runTest {
        val persistent = FakeCursorPersistentCache<String, String>()
        val cursor = CursorBookmark(prev = "p1", self = "p2", next = "p3")
        // p2 lives only in L2 (persistent), filled (3 == capacity).
        persistent.store["p2"] = cursor to CursorPageState.Success(
            bookmark = cursor,
            data = listOf("a", "b", "c"),
        )

        val core = CursorPagingCore<String, String>(initialCapacity = 3, persistentCache = persistent)
        val paginator = CursorPaginator<String, String>(core = core) { _ ->
            CursorLoadResult(data = listOf("x", "y", "z"), bookmark = cursor)
        }

        paginator.core.asFlow() // enable the full cache flow

        // L1 miss -> promoted from L2 -> filled-success -> cache-hit fast path.
        paginator.jump(
            CursorBookmark(prev = null, self = "p2", next = null),
            enableCacheFlow = true,
            silentlyLoading = true,
            silentlyResult = true,
        )

        val cacheFlowSelves = paginator.core.asFlow().first().map { it.bookmark.self }
        assertTrue(
            "p2" in cacheFlowSelves,
            "cache-hit jump must refresh asFlow() after L2->L1 promotion, was $cacheFlowSelves",
        )
    }
}
