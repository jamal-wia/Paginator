package com.jamal_aliev.paginator.strategy

import com.jamal_aliev.paginator.MutablePaginator
import com.jamal_aliev.paginator.PagingCore
import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import com.jamal_aliev.paginator.page.PageState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * In-memory mock of [PersistentPagingCache] for testing.
 */
private class InMemoryPersistentCache<T> : PersistentPagingCache<T> {
    val store = mutableMapOf<Int, PageState<T>>()

    override suspend fun save(state: PageState<T>) {
        store[state.page] = state
    }

    override suspend fun load(page: Int): PageState<T>? = store[page]

    override suspend fun loadAll(): List<PageState<T>> = store.values.toList()

    override suspend fun remove(page: Int) {
        store.remove(page)
    }

    override suspend fun clear() {
        store.clear()
    }
}

class PersistentPagingCacheTest {

    private val capacity = 3
    private val totalPages = 10

    private fun createPaginatorWithPersistentCache(
        persistentCache: InMemoryPersistentCache<String> = InMemoryPersistentCache(),
        sourceCallTracker: MutableList<Int>? = null,
    ): Pair<MutablePaginator<String>, InMemoryPersistentCache<String>> {
        val paginator = MutablePaginator(
            core = PagingCore(
                cache = DefaultPagingCache(),
                persistentCache = persistentCache,
                initialCapacity = capacity,
            )
        ) { page: Int ->
            sourceCallTracker?.add(page)
            if (page in 1..totalPages) {
                List(this.core.capacity) { "p${page}_item$it" }
            } else {
                emptyList()
            }
        }
        return paginator to persistentCache
    }

    // =========================================================================
    // Read fallback: jump
    // =========================================================================

    @Test
    fun `jump uses persistent cache when in-memory is empty`() = runTest {
        val sourceCalls = mutableListOf<Int>()
        val persistent = InMemoryPersistentCache<String>()

        // Pre-populate persistent cache with page 1
        persistent.save(
            PageState.SuccessPage(page = 1, data = listOf("cached_a", "cached_b", "cached_c"))
        )

        val (paginator, _) = createPaginatorWithPersistentCache(persistent, sourceCalls)

        // jump should find page 1 in persistent cache — no source call
        val (_, state) = paginator.jump(
            bookmark = BookmarkInt(1),
            silentlyLoading = true,
            silentlyResult = true,
        )

        assertTrue(state is PageState.SuccessPage)
        assertEquals(listOf("cached_a", "cached_b", "cached_c"), state.data)
        assertTrue(sourceCalls.isEmpty(), "Source should NOT have been called")
    }

    @Test
    fun `jump falls back to source when persistent cache is also empty`() = runTest {
        val sourceCalls = mutableListOf<Int>()
        val (paginator, _) = createPaginatorWithPersistentCache(
            sourceCallTracker = sourceCalls
        )

        val (_, state) = paginator.jump(
            bookmark = BookmarkInt(1),
            silentlyLoading = true,
            silentlyResult = true,
        )

        assertTrue(state is PageState.SuccessPage)
        assertEquals(1, sourceCalls.size, "Source should have been called once")
        assertEquals(1, sourceCalls[0])
    }

    // =========================================================================
    // Read fallback: goNextPage
    // =========================================================================

    @Test
    fun `goNextPage uses persistent cache when next page is only in L2`() = runTest {
        val sourceCalls = mutableListOf<Int>()
        val persistent = InMemoryPersistentCache<String>()

        // Pre-populate persistent with pages 1 and 2
        persistent.save(
            PageState.SuccessPage(page = 1, data = listOf("p1_a", "p1_b", "p1_c"))
        )
        persistent.save(
            PageState.SuccessPage(page = 2, data = listOf("p2_a", "p2_b", "p2_c"))
        )

        val (paginator, _) = createPaginatorWithPersistentCache(persistent, sourceCalls)

        // Start by jumping to page 1 (from persistent, no source call)
        paginator.jump(
            bookmark = BookmarkInt(1),
            silentlyLoading = true,
            silentlyResult = true,
        )
        assertTrue(sourceCalls.isEmpty(), "Page 1 should come from persistent")

        // goNextPage should find page 2 in persistent — no source call
        val state = paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        assertTrue(state is PageState.SuccessPage)
        assertEquals(2, state.page)
        assertEquals(listOf("p2_a", "p2_b", "p2_c"), state.data)
        assertTrue(sourceCalls.isEmpty(), "Source should NOT have been called for page 2")
    }

    @Test
    fun `goNextPage falls back to source when persistent cache misses`() = runTest {
        val sourceCalls = mutableListOf<Int>()
        val persistent = InMemoryPersistentCache<String>()

        // Only page 1 in persistent
        persistent.save(
            PageState.SuccessPage(page = 1, data = listOf("p1_a", "p1_b", "p1_c"))
        )

        val (paginator, _) = createPaginatorWithPersistentCache(persistent, sourceCalls)

        paginator.jump(
            bookmark = BookmarkInt(1),
            silentlyLoading = true,
            silentlyResult = true,
        )
        sourceCalls.clear()

        // Page 2 is NOT in persistent — source should be called
        val state = paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        assertTrue(state is PageState.SuccessPage)
        assertEquals(2, state.page)
        assertEquals(1, sourceCalls.size)
        assertEquals(2, sourceCalls[0])
    }

    // =========================================================================
    // Read fallback: goPreviousPage
    // =========================================================================

    @Test
    fun `goPreviousPage uses persistent cache`() = runTest {
        val sourceCalls = mutableListOf<Int>()
        val persistent = InMemoryPersistentCache<String>()

        // Pre-populate persistent with pages 1 and 2
        persistent.save(
            PageState.SuccessPage(page = 1, data = listOf("p1_a", "p1_b", "p1_c"))
        )
        persistent.save(
            PageState.SuccessPage(page = 2, data = listOf("p2_a", "p2_b", "p2_c"))
        )

        val (paginator, _) = createPaginatorWithPersistentCache(persistent, sourceCalls)

        // Jump to page 2 (from persistent)
        paginator.jump(
            bookmark = BookmarkInt(2),
            silentlyLoading = true,
            silentlyResult = true,
        )
        assertTrue(sourceCalls.isEmpty())

        // goPreviousPage should find page 1 in persistent
        val state = paginator.goPreviousPage(silentlyLoading = true, silentlyResult = true)

        assertTrue(state is PageState.SuccessPage)
        assertEquals(1, state.page)
        assertEquals(listOf("p1_a", "p1_b", "p1_c"), state.data)
        assertTrue(sourceCalls.isEmpty(), "Source should NOT have been called")
    }

    // =========================================================================
    // Auto-persist after source load
    // =========================================================================

    @Test
    fun `successful source load is auto-persisted`() = runTest {
        val (paginator, persistent) = createPaginatorWithPersistentCache()

        assertTrue(persistent.store.isEmpty(), "Persistent cache should start empty")

        // Load page 1 from source (persistent is empty)
        paginator.jump(
            bookmark = BookmarkInt(1),
            silentlyLoading = true,
            silentlyResult = true,
        )

        // Page 1 should now be in persistent cache
        val persisted = persistent.store[1]
        assertNotNull(persisted, "Page 1 should have been persisted")
        assertTrue(persisted is PageState.SuccessPage)
        assertEquals(capacity, persisted.data.size)
    }

    @Test
    fun `goNextPage auto-persists loaded pages`() = runTest {
        val (paginator, persistent) = createPaginatorWithPersistentCache()

        paginator.jump(
            bookmark = BookmarkInt(1),
            silentlyLoading = true,
            silentlyResult = true,
        )
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        assertNotNull(persistent.store[1], "Page 1 should be persisted")
        assertNotNull(persistent.store[2], "Page 2 should be persisted")
    }

    @Test
    fun `error pages are not persisted`() = runTest {
        val persistent = InMemoryPersistentCache<String>()
        val paginator = MutablePaginator(
            core = PagingCore(
                cache = DefaultPagingCache(),
                persistentCache = persistent,
                initialCapacity = capacity,
            )
        ) { page: Int ->
            if (page == 1) listOf("a", "b", "c")
            else throw RuntimeException("network error")
        }

        paginator.jump(
            bookmark = BookmarkInt(1),
            silentlyLoading = true,
            silentlyResult = true,
        )
        // Page 2 will fail
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        assertNotNull(persistent.store[1], "Page 1 (success) should be persisted")
        assertNull(persistent.store[2], "Page 2 (error) should NOT be persisted")
    }

    @Test
    fun `restart auto-persists fresh page 1`() = runTest {
        val (paginator, persistent) = createPaginatorWithPersistentCache()

        paginator.jump(
            bookmark = BookmarkInt(1),
            silentlyLoading = true,
            silentlyResult = true,
        )
        val originalPage1 = persistent.store[1]
        assertNotNull(originalPage1)

        // Restart reloads page 1 from source
        paginator.restart(silentlyLoading = true, silentlyResult = true)

        val refreshedPage1 = persistent.store[1]
        assertNotNull(refreshedPage1, "Page 1 should still be persisted after restart")
    }

    @Test
    fun `refresh auto-persists reloaded pages`() = runTest {
        val (paginator, persistent) = createPaginatorWithPersistentCache()

        paginator.jump(
            bookmark = BookmarkInt(1),
            silentlyLoading = true,
            silentlyResult = true,
        )
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        val beforeRefresh1 = persistent.store[1]
        val beforeRefresh2 = persistent.store[2]

        paginator.refresh(
            pages = listOf(1, 2),
            loadingSilently = true,
            finalSilently = true,
        )

        val afterRefresh1 = persistent.store[1]
        val afterRefresh2 = persistent.store[2]
        assertNotNull(afterRefresh1)
        assertNotNull(afterRefresh2)
        // IDs should differ because fresh pages were created
        assertTrue(afterRefresh1.id != beforeRefresh1!!.id, "Page 1 should be a fresh state")
        assertTrue(afterRefresh2.id != beforeRefresh2!!.id, "Page 2 should be a fresh state")
    }

    // =========================================================================
    // Restart does NOT clear persistent cache
    // =========================================================================

    @Test
    fun `restart does not clear persistent cache`() = runTest {
        val (paginator, persistent) = createPaginatorWithPersistentCache()

        // Load pages 1-3
        paginator.jump(
            bookmark = BookmarkInt(1),
            silentlyLoading = true,
            silentlyResult = true,
        )
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        assertEquals(3, persistent.store.size, "3 pages should be persisted")

        paginator.restart(silentlyLoading = true, silentlyResult = true)

        // Pages 2, 3 should still be in persistent cache (not cleared)
        assertNotNull(persistent.store[2], "Page 2 should remain in persistent after restart")
        assertNotNull(persistent.store[3], "Page 3 should remain in persistent after restart")
    }

    // =========================================================================
    // Transaction rollback does NOT affect persistent cache
    // =========================================================================

    @Test
    fun `transaction rollback does not affect persistent cache`() = runTest {
        val (paginator, persistent) = createPaginatorWithPersistentCache()

        // Load page 1
        paginator.jump(
            bookmark = BookmarkInt(1),
            silentlyLoading = true,
            silentlyResult = true,
        )
        val persistedBefore = persistent.store[1]
        assertNotNull(persistedBefore)

        // Transaction that modifies and then fails
        assertFailsWith<IllegalStateException> {
            paginator.transaction {
                (this as MutablePaginator).setElement(
                    "modified",
                    page = 1,
                    index = 0,
                    silently = true
                )
                error("rollback!")
            }
        }

        // In-memory cache should be rolled back
        assertEquals("p1_item0", paginator.cache.getElement(1, 0))

        // Persistent cache should NOT be rolled back — still has the original
        val persistedAfter = persistent.store[1]
        assertNotNull(persistedAfter)
        assertEquals(persistedBefore.id, persistedAfter.id)
    }

    // =========================================================================
    // Interaction with eviction strategies
    // =========================================================================

    @Test
    fun `page evicted from LRU is restored from persistent on next access`() = runTest {
        val sourceCalls = mutableListOf<Int>()
        val persistent = InMemoryPersistentCache<String>()

        // LRU cache with maxSize=3
        val paginator = MutablePaginator(
            core = PagingCore(
                cache = LruPagingCache(
                    maxSize = 3,
                    protectContextWindow = false
                ),
                persistentCache = persistent,
                initialCapacity = capacity,
            )
        ) { page: Int ->
            sourceCalls.add(page)
            if (page in 1..totalPages) {
                List(this.core.capacity) { "p${page}_item$it" }
            } else {
                emptyList()
            }
        }

        // Load pages 1-4 (page 1 will be evicted from LRU due to maxSize=3)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        // Page 1 should be auto-persisted but evicted from in-memory
        assertNull(paginator.cache.getStateOf(1), "Page 1 should be evicted from LRU")
        assertNotNull(persistent.store[1], "Page 1 should still be in persistent cache")

        sourceCalls.clear()

        // Jump back to page 1 — should restore from persistent, NOT call source
        val (_, state) = paginator.jump(
            BookmarkInt(1),
            silentlyLoading = true,
            silentlyResult = true,
        )

        assertTrue(state is PageState.SuccessPage)
        assertEquals(1, state.page)
        assertTrue(sourceCalls.isEmpty(), "Source should NOT be called — data restored from persistent")
    }

    // =========================================================================
    // Paginator without persistent cache (backward compat)
    // =========================================================================

    @Test
    fun `paginator without persistent cache works normally`() = runTest {
        val paginator = MutablePaginator(
            core = PagingCore(initialCapacity = capacity)
        ) { page: Int ->
            if (page in 1..totalPages) {
                List(this.core.capacity) { "p${page}_item$it" }
            } else {
                emptyList()
            }
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        assertNotNull(paginator.cache.getStateOf(1))
        assertNotNull(paginator.cache.getStateOf(2))
    }
}
