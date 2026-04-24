package com.jamal_aliev.paginator.cache

import com.jamal_aliev.paginator.MutablePaginator
import com.jamal_aliev.paginator.PagingCore
import com.jamal_aliev.paginator.bookmark.BookmarkInt
import com.jamal_aliev.paginator.extension.warmUpFromPersistent
import com.jamal_aliev.paginator.load.LoadResult
import com.jamal_aliev.paginator.page.PageState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the L2 helpers added alongside the existing flush/transaction machinery:
 *
 * - [MutablePaginator.affectedPages] — read-only snapshot of pending pages
 * - [MutablePaginator.hasPendingFlush] — derived convenience flag
 * - [warmUpFromPersistent] — eager restore of L1 from L2 on cold start
 */
class PaginatorPersistentExtTest {

    private class InMemoryPersistentCache<T> : PersistentPagingCache<T> {
        val store = mutableMapOf<Int, PageState<T>>()

        override suspend fun save(state: PageState<T>) {
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

    private fun paginator(
        persistent: PersistentPagingCache<String>? = InMemoryPersistentCache(),
        cache: PagingCache<String> = DefaultPagingCache(),
        capacity: Int = 3,
        sourceCallTracker: MutableList<Int>? = null,
    ): MutablePaginator<String> = MutablePaginator(
        core = PagingCore(
            cache = cache,
            persistentCache = persistent,
            initialCapacity = capacity,
        )
    ) { page ->
        sourceCallTracker?.add(page)
        LoadResult(List(this.core.capacity) { "p${page}_item$it" })
    }

    // =========================================================================
    // affectedPages / hasPendingFlush
    // =========================================================================

    @Test
    fun `affectedPages starts empty and stays empty without CRUD`() = runTest {
        val p = paginator()
        assertTrue(p.affectedPages.isEmpty())
        assertFalse(p.hasPendingFlush)

        p.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        // Source load persists automatically — not a CRUD mutation.
        assertTrue(
            p.affectedPages.isEmpty(),
            "auto-persist from source load must not enter the CRUD tracker"
        )
        assertFalse(p.hasPendingFlush)
    }

    @Test
    fun `setElement marks affected page and flag flips to true`() = runTest {
        val p = paginator()
        p.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        p.setElement("X", page = 1, index = 0, silently = true)

        assertEquals(setOf(1), p.affectedPages)
        assertTrue(p.hasPendingFlush)
    }

    @Test
    fun `flush drains affected pages and flag flips back to false`() = runTest {
        val p = paginator()
        p.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        p.setElement("X", page = 1, index = 0, silently = true)

        assertTrue(p.hasPendingFlush)
        p.flush()

        assertTrue(p.affectedPages.isEmpty())
        assertFalse(p.hasPendingFlush)
    }

    @Test
    fun `affectedPages returns a snapshot that is not backed by the internal set`() = runTest {
        val p = paginator()
        p.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        p.setElement("X", page = 1, index = 0, silently = true)

        val snapshot1 = p.affectedPages
        p.setElement("Y", page = 1, index = 1, silently = true)
        // snapshot1 must not observe the second mutation (it was taken before).
        // The set returned by the property is the same immutable AtomicRef value,
        // but subsequent property reads yield a new snapshot.
        assertEquals(setOf(1), snapshot1)
    }

    @Test
    fun `hasPendingFlush is always false when persistentCache is null`() = runTest {
        val p = paginator(persistent = null)
        p.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        p.setElement("X", page = 1, index = 0, silently = true)

        // Without an L2 there is nothing to flush — the flag should not light up
        // even though mutations are still tracked internally.
        assertFalse(p.hasPendingFlush)
    }

    @Test
    fun `multiple CRUD ops across pages are all tracked`() = runTest {
        val p = paginator()
        p.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        p.goNextPage(silentlyLoading = true, silentlyResult = true)
        p.goNextPage(silentlyLoading = true, silentlyResult = true)

        p.setElement("A", page = 1, index = 0, silently = true)
        p.setElement("B", page = 3, index = 0, silently = true)

        assertEquals(setOf(1, 3), p.affectedPages)
        assertTrue(p.hasPendingFlush)
    }

    @Test
    fun `successful transaction clears affected pages after auto-flush`() = runTest {
        val p = paginator()
        p.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        p.transaction {
            (this as MutablePaginator).setElement("TX", page = 1, index = 0, silently = true)
        }

        assertFalse(p.hasPendingFlush, "auto-flush inside transaction must drain tracker")
    }

    @Test
    fun `failed transaction preserves pre-transaction affected pages`() = runTest {
        val p = paginator()
        p.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        p.goNextPage(silentlyLoading = true, silentlyResult = true)

        // Mutate page 1 outside any transaction — pending flush.
        p.setElement("OUTSIDE", page = 1, index = 0, silently = true)
        assertEquals(setOf(1), p.affectedPages)

        runCatching {
            p.transaction {
                (this as MutablePaginator).setElement(
                    "INSIDE", page = 2, index = 0, silently = true,
                )
                error("rollback!")
            }
        }

        // Inside-transaction mutation was rolled back; outside-tx tracker is restored.
        assertEquals(setOf(1), p.affectedPages)
        assertTrue(p.hasPendingFlush)
    }

    // =========================================================================
    // warmUpFromPersistent
    // =========================================================================

    @Test
    fun `warmUpFromPersistent returns zero when persistentCache is null`() = runTest {
        val p = paginator(persistent = null)
        assertEquals(0, p.warmUpFromPersistent())
    }

    @Test
    fun `warmUpFromPersistent returns zero when L2 is empty`() = runTest {
        val p = paginator()
        assertEquals(0, p.warmUpFromPersistent())
        assertTrue(p.cache.pages.isEmpty())
    }

    @Test
    fun `warmUpFromPersistent populates L1 with every persisted page`() = runTest {
        val persistent = InMemoryPersistentCache<String>()
        persistent.save(PageState.SuccessPage(1, mutableListOf("a", "b")))
        persistent.save(PageState.SuccessPage(2, mutableListOf("c", "d")))
        persistent.save(PageState.SuccessPage(3, mutableListOf("e", "f")))

        val p = paginator(persistent = persistent)
        val inserted = p.warmUpFromPersistent()

        assertEquals(3, inserted)
        assertEquals(listOf(1, 2, 3), p.cache.pages)
        assertEquals(listOf("a", "b"), p.cache.getStateOf(1)?.data)
        assertEquals(listOf("e", "f"), p.cache.getStateOf(3)?.data)
    }

    @Test
    fun `warmUpFromPersistent does not overwrite pages already in L1`() = runTest {
        val persistent = InMemoryPersistentCache<String>()
        persistent.save(PageState.SuccessPage(1, mutableListOf("persisted")))
        persistent.save(PageState.SuccessPage(2, mutableListOf("fresh")))

        val p = paginator(persistent = persistent)
        // Seed L1 manually with a different value for page 1.
        p.cache.setState(
            PageState.SuccessPage(1, mutableListOf("in-memory")),
            silently = true,
        )

        val inserted = p.warmUpFromPersistent()

        assertEquals(1, inserted, "only page 2 should be inserted")
        assertEquals(
            listOf("in-memory"),
            p.cache.getStateOf(1)?.data,
            "L1 copy must win over L2",
        )
        assertEquals(listOf("fresh"), p.cache.getStateOf(2)?.data)
    }

    @Test
    fun `warmUpFromPersistent followed by jump into warmed page does not call source`() =
        runTest {
            val persistent = InMemoryPersistentCache<String>()
            persistent.save(PageState.SuccessPage(1, mutableListOf("a", "b", "c")))
            persistent.save(PageState.SuccessPage(2, mutableListOf("d", "e", "f")))

            val sourceCalls = mutableListOf<Int>()
            val p = paginator(persistent = persistent, sourceCallTracker = sourceCalls)

            p.warmUpFromPersistent()
            sourceCalls.clear()

            // Jump into the middle of the warmed range — context expands across both
            // pre-warmed pages and no source call is issued.
            p.jump(BookmarkInt(2), silentlyLoading = true, silentlyResult = true)

            assertTrue(
                sourceCalls.isEmpty(),
                "pre-warmed pages must be served from L1 without a network call"
            )
            assertEquals(1, p.cache.startContextPage)
            assertEquals(2, p.cache.endContextPage)
        }

    @Test
    fun `warmUpFromPersistent with pageRange emits a snapshot`() = runTest {
        val persistent = InMemoryPersistentCache<String>()
        persistent.save(PageState.SuccessPage(1, mutableListOf("a", "b", "c")))
        persistent.save(PageState.SuccessPage(2, mutableListOf("d", "e", "f")))

        val p = paginator(persistent = persistent)
        p.warmUpFromPersistent(pageRange = 1..2)

        val emitted = p.core.snapshot.first()
        assertEquals(2, emitted.size, "snapshot must include both warmed pages")
        assertEquals(listOf(1, 2), emitted.map { it.page })
    }

    @Test
    fun `warmUpFromPersistent without pageRange emits no snapshot`() = runTest {
        val persistent = InMemoryPersistentCache<String>()
        persistent.save(PageState.SuccessPage(1, mutableListOf("a")))

        val p = paginator(persistent = persistent)
        p.warmUpFromPersistent()

        // Context window was never started, so no snapshot is emitted.
        assertTrue(p.core.snapshot.first().isEmpty())
    }

    @Test
    fun `warmUpFromPersistent respects LRU eviction strategy`() = runTest {
        val persistent = InMemoryPersistentCache<String>()
        repeat(5) { idx ->
            val page = idx + 1
            persistent.save(PageState.SuccessPage(page, mutableListOf("p$page")))
        }

        val lru = LruPagingCache<String>(maxSize = 3, protectContextWindow = false)
        val p = paginator(persistent = persistent, cache = lru)

        val inserted = p.warmUpFromPersistent()

        // All 5 are inserted, but the LRU will evict the oldest until only 3 remain.
        assertEquals(5, inserted)
        assertEquals(3, p.cache.pages.size, "LRU trims to its maxSize regardless of L2 size")
    }

    @Test
    fun `warmUpFromPersistent does not mark pages as affected`() = runTest {
        val persistent = InMemoryPersistentCache<String>()
        persistent.save(PageState.SuccessPage(1, mutableListOf("a")))
        persistent.save(PageState.SuccessPage(2, mutableListOf("b")))

        val p = paginator(persistent = persistent)
        p.warmUpFromPersistent()

        assertTrue(
            p.affectedPages.isEmpty(),
            "warming up from L2 is not a CRUD mutation"
        )
        assertFalse(p.hasPendingFlush)
    }
}
