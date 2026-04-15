package com.jamal_aliev.paginator.cache

import com.jamal_aliev.paginator.MutablePaginator
import com.jamal_aliev.paginator.PagingCore
import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import com.jamal_aliev.paginator.load.LoadResult
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
        // Deep-copy the data list to break reference aliasing with L1,
        // matching the behavior of a real serializing backend (Room, SQLite).
        store[state.page] = state.copy(data = state.data.toMutableList())
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
                LoadResult(List(this.core.capacity) { "p${page}_item$it" })
            } else {
                LoadResult(emptyList())
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
            if (page == 1) LoadResult(listOf("a", "b", "c"))
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
                LoadResult(List(this.core.capacity) { "p${page}_item$it" })
            } else {
                LoadResult(emptyList())
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
                LoadResult(List(this.core.capacity) { "p${page}_item$it" })
            } else {
                LoadResult(emptyList())
            }
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        assertNotNull(paginator.cache.getStateOf(1))
        assertNotNull(paginator.cache.getStateOf(2))
    }

    // =========================================================================
    // CRUD + L2: setElement
    // =========================================================================

    @Test
    fun `setElement persists to L2 after persistModifiedPages`() = runTest {
        val (paginator, persistent) = createPaginatorWithPersistentCache()

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        val originalData = persistent.store[1]!!.data.toList()

        paginator.setElement("REPLACED", page = 1, index = 0, silently = true)
        // Before flush, L2 should still have original data
        assertEquals(originalData, persistent.store[1]!!.data)

        paginator.flush()
        // After flush, L2 should have the updated element
        assertEquals("REPLACED", persistent.store[1]!!.data[0])
    }

    // =========================================================================
    // CRUD + L2: removeElement
    // =========================================================================

    @Test
    fun `removeElement persists rebalanced pages to L2`() = runTest {
        val (paginator, persistent) = createPaginatorWithPersistentCache()

        // Load pages 1 and 2
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        val page2FirstItem = persistent.store[2]!!.data[0]

        // Remove from page 1 — should rebalance from page 2
        paginator.removeElement(page = 1, index = 0, silently = true)
        paginator.flush()

        // Page 1 should now contain the first item from the old page 2
        val page1Data = persistent.store[1]!!.data
        assertEquals(capacity, page1Data.size)
        assertEquals(page2FirstItem, page1Data.last())

        // Page 2 should have one fewer item
        val page2Data = persistent.store[2]!!.data
        assertEquals(capacity - 1, page2Data.size)
    }

    @Test
    fun `removeElement that empties a page removes it from L2`() = runTest {
        val persistent = InMemoryPersistentCache<String>()
        val paginator = MutablePaginator(
            core = PagingCore(
                cache = DefaultPagingCache(),
                persistentCache = persistent,
                initialCapacity = 1, // capacity=1 so single removal empties the page
            )
        ) { page: Int ->
            if (page in 1..3) LoadResult(listOf("item_$page"))
            else LoadResult(emptyList())
        }

        // Load page 1 only (no page 2 loaded, so no rebalancing)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        assertNotNull(persistent.store[1])

        paginator.removeElement(page = 1, index = 0, silently = true)
        paginator.flush()

        // Page 1 was emptied → should be removed from L2
        assertNull(persistent.store[1], "Empty page should be removed from L2")
    }

    // =========================================================================
    // CRUD + L2: addAllElements
    // =========================================================================

    @Test
    fun `addAllElements with overflow persists all affected pages to L2`() = runTest {
        val (paginator, persistent) = createPaginatorWithPersistentCache()

        // Load pages 1 and 2
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        // Add elements to page 1 that will overflow to page 2
        paginator.addAllElements(
            elements = listOf("NEW_A", "NEW_B", "NEW_C"),
            targetPage = 1,
            index = 0,
            silently = true,
        )
        paginator.flush()

        // Page 1 should have capacity items starting with the new ones
        val page1Data = persistent.store[1]!!.data
        assertEquals(capacity, page1Data.size)
        assertEquals("NEW_A", page1Data[0])

        // Page 2 should have received overflow
        val page2Data = persistent.store[2]!!.data
        assertTrue(page2Data.size > 0)
    }

    // =========================================================================
    // CRUD + L2: removeState
    // =========================================================================

    @Test
    fun `removeState removes page from L2 and persists renumbered pages`() = runTest {
        val (paginator, persistent) = createPaginatorWithPersistentCache()

        // Load pages 1, 2, 3
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        val oldPage2Data = persistent.store[2]!!.data.toList()
        val oldPage3Data = persistent.store[3]!!.data.toList()

        // Remove page 1 — pages 2,3 should collapse to 1,2
        paginator.removeState(pageToRemove = 1, silently = true)
        paginator.flush()

        // Old page 1 should be gone (its number no longer exists after collapse)
        // New page 1 should have old page 2 data
        val newPage1 = persistent.store[1]
        assertNotNull(newPage1)
        assertEquals(oldPage2Data, newPage1.data)

        // New page 2 should have old page 3 data
        val newPage2 = persistent.store[2]
        assertNotNull(newPage2)
        assertEquals(oldPage3Data, newPage2.data)

        // Old page 3 should be removed from L2
        assertNull(persistent.store[3], "Old page 3 should be removed from L2")
    }

    // =========================================================================
    // CRUD + L2: replaceAllElements
    // =========================================================================

    @Test
    fun `replaceAllElements persists all modified pages to L2`() = runTest {
        val (paginator, persistent) = createPaginatorWithPersistentCache()

        // Load pages 1 and 2
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        // Replace first element on every page
        paginator.replaceAllElements(
            providerElement = { _, _, index -> if (index == 0) "REPLACED" else null },
            silently = true,
            predicate = { _, _, index -> index == 0 },
        )
        paginator.flush()

        assertEquals("REPLACED", persistent.store[1]!!.data[0])
        assertEquals("REPLACED", persistent.store[2]!!.data[0])
    }

    // =========================================================================
    // CRUD + L2: plusAssign
    // =========================================================================

    @Test
    fun `plusAssign persists page to L2`() = runTest {
        val (paginator, persistent) = createPaginatorWithPersistentCache()

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        val customPage = PageState.SuccessPage(
            page = 5,
            data = mutableListOf("custom_a", "custom_b")
        )
        paginator += customPage
        paginator.flush()

        val persisted = persistent.store[5]
        assertNotNull(persisted)
        assertEquals(listOf("custom_a", "custom_b"), persisted.data)
    }

    // =========================================================================
    // CRUD + L2: transaction auto-persist
    // =========================================================================

    @Test
    fun `transaction success auto-persists CRUD changes to L2`() = runTest {
        val (paginator, persistent) = createPaginatorWithPersistentCache()

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        paginator.transaction {
            (this as MutablePaginator).setElement("TX_ITEM", page = 1, index = 0, silently = true)
        }

        // Auto-persisted by transaction — no manual flush needed
        assertEquals("TX_ITEM", persistent.store[1]!!.data[0])
    }

    @Test
    fun `transaction failure does not persist CRUD changes to L2`() = runTest {
        val (paginator, persistent) = createPaginatorWithPersistentCache()

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        val originalData = persistent.store[1]!!.data.toList()

        assertFailsWith<IllegalStateException> {
            paginator.transaction {
                (this as MutablePaginator).setElement(
                    "SHOULD_NOT_PERSIST",
                    page = 1,
                    index = 0,
                    silently = true,
                )
                error("rollback!")
            }
        }

        // L2 should still have original data (unchanged by failed transaction)
        assertEquals(originalData, persistent.store[1]!!.data)
    }

    @Test
    fun `pre-transaction pending pages are preserved after transaction`() = runTest {
        val (paginator, persistent) = createPaginatorWithPersistentCache()

        // Load pages 1 and 2
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        // Modify page 1 outside transaction (pending flush)
        paginator.setElement("OUTSIDE_TX", page = 1, index = 0, silently = true)

        // Transaction modifies page 2
        paginator.transaction {
            (this as MutablePaginator).setElement("INSIDE_TX", page = 2, index = 0, silently = true)
        }

        // Page 2 should be auto-persisted by transaction
        assertEquals("INSIDE_TX", persistent.store[2]!!.data[0])

        // Page 1 should NOT be persisted yet (pre-transaction pending)
        assertEquals("p1_item0", persistent.store[1]!!.data[0])

        // Now flush the pre-transaction pending
        paginator.flush()
        assertEquals("OUTSIDE_TX", persistent.store[1]!!.data[0])
    }

    // =========================================================================
    // CRUD + L2: edge cases
    // =========================================================================

    @Test
    fun `persistModifiedPages is no-op without persistent cache`() = runTest {
        val paginator = MutablePaginator(
            core = PagingCore(initialCapacity = capacity)
        ) { page: Int ->
            if (page in 1..totalPages) {
                LoadResult(List(this.core.capacity) { "p${page}_item$it" })
            } else {
                LoadResult(emptyList())
            }
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.setElement("MODIFIED", page = 1, index = 0, silently = true)

        // Should not throw
        paginator.flush()
    }

    @Test
    fun `persistModifiedPages is no-op with empty affected set`() = runTest {
        val (paginator, persistent) = createPaginatorWithPersistentCache()

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        val dataBefore = persistent.store[1]!!.data.toList()

        // No CRUD operations — flush should be no-op
        paginator.flush()

        assertEquals(dataBefore, persistent.store[1]!!.data)
    }
}
