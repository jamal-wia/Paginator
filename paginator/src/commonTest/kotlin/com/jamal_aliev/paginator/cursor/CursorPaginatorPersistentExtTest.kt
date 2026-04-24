package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.CursorPagingCore
import com.jamal_aliev.paginator.MutableCursorPaginator
import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cache.CursorPersistentPagingCache
import com.jamal_aliev.paginator.cache.DefaultCursorPagingCache
import com.jamal_aliev.paginator.cache.LruCursorPagingCache
import com.jamal_aliev.paginator.extension.warmUpFromPersistent
import com.jamal_aliev.paginator.page.PageState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Cursor counterpart of [com.jamal_aliev.paginator.cache.PaginatorPersistentExtTest].
 *
 * Verifies [MutableCursorPaginator.affectedSelves], [MutableCursorPaginator.hasPendingFlush]
 * and the cursor variant of [warmUpFromPersistent].
 */
class CursorPaginatorPersistentExtTest {

    private class InMemoryCursorPersistentCache<T> : CursorPersistentPagingCache<T> {
        val store = mutableMapOf<Any, Pair<CursorBookmark, PageState<T>>>()

        override suspend fun save(cursor: CursorBookmark, state: PageState<T>) {
            store[cursor.self] = cursor to state.copy(data = state.data.toMutableList())
        }

        override suspend fun load(self: Any): Pair<CursorBookmark, PageState<T>>? = store[self]

        override suspend fun loadAll(): List<Pair<CursorBookmark, PageState<T>>> =
            store.values.toList()

        override suspend fun remove(self: Any) {
            store.remove(self)
        }

        override suspend fun clear() {
            store.clear()
        }
    }

    private fun backendPages(count: Int = 4, capacity: Int = 3): List<FakeCursorBackend.Page> =
        FakeCursorBackend.defaultPages(pageCount = count, capacity = capacity)

    private fun mutablePaginator(
        persistent: CursorPersistentPagingCache<String>? = InMemoryCursorPersistentCache(),
        cache: com.jamal_aliev.paginator.cache.CursorPagingCache<String> =
            DefaultCursorPagingCache(),
        backend: FakeCursorBackend = FakeCursorBackend(backendPages()),
        capacity: Int = 3,
    ): MutableCursorPaginator<String> = MutableCursorPaginator(
        core = CursorPagingCore(
            cache = cache,
            persistentCache = persistent,
            initialCapacity = capacity,
        ),
    ) { cursor -> backend.loadResult(cursor) }

    private fun seed(
        persistent: InMemoryCursorPersistentCache<String>,
        count: Int = 3,
    ) {
        val pages = backendPages(count)
        for (page in pages) {
            val cursor = CursorBookmark(prev = page.prev, self = page.self, next = page.next)
            persistent.store[cursor.self] = cursor to
                    PageState.SuccessPage(page = 0, data = page.items.toMutableList())
        }
    }

    // =========================================================================
    // affectedSelves / hasPendingFlush
    // =========================================================================

    @Test
    fun `affectedSelves starts empty and stays empty without CRUD`() = runTest {
        val p = mutablePaginator()
        assertTrue(p.affectedSelves.isEmpty())
        assertFalse(p.hasPendingFlush)

        p.restart(silentlyLoading = true, silentlyResult = true)
        assertTrue(
            p.affectedSelves.isEmpty(),
            "auto-persist from source load must not enter the CRUD tracker"
        )
        assertFalse(p.hasPendingFlush)
    }

    @Test
    fun `setElement marks affected self and flag flips to true`() = runTest {
        val p = mutablePaginator()
        p.restart(silentlyLoading = true, silentlyResult = true)

        p.setElement("X", self = "p0", index = 0, silently = true)

        assertEquals(setOf<Any>("p0"), p.affectedSelves)
        assertTrue(p.hasPendingFlush)
    }

    @Test
    fun `flush drains affected selves and flag flips back to false`() = runTest {
        val p = mutablePaginator()
        p.restart(silentlyLoading = true, silentlyResult = true)
        p.setElement("X", self = "p0", index = 0, silently = true)

        assertTrue(p.hasPendingFlush)
        p.flush()

        assertTrue(p.affectedSelves.isEmpty())
        assertFalse(p.hasPendingFlush)
    }

    @Test
    fun `hasPendingFlush is always false when persistentCache is null`() = runTest {
        val p = mutablePaginator(persistent = null)
        p.restart(silentlyLoading = true, silentlyResult = true)
        p.setElement("X", self = "p0", index = 0, silently = true)

        assertFalse(p.hasPendingFlush)
    }

    @Test
    fun `multiple CRUD ops across pages are all tracked`() = runTest {
        val p = mutablePaginator()
        p.restart(silentlyLoading = true, silentlyResult = true)
        p.goNextPage(silentlyLoading = true, silentlyResult = true)
        p.goNextPage(silentlyLoading = true, silentlyResult = true)

        p.setElement("A", self = "p0", index = 0, silently = true)
        p.setElement("B", self = "p2", index = 0, silently = true)

        assertEquals(setOf<Any>("p0", "p2"), p.affectedSelves)
        assertTrue(p.hasPendingFlush)
    }

    @Test
    fun `successful transaction clears affected selves after auto-flush`() = runTest {
        val p = mutablePaginator()
        p.restart(silentlyLoading = true, silentlyResult = true)

        p.transaction {
            (this as MutableCursorPaginator).setElement(
                element = "TX", self = "p0", index = 0, silently = true,
            )
        }

        assertFalse(p.hasPendingFlush, "auto-flush inside transaction must drain tracker")
    }

    @Test
    fun `failed transaction preserves pre-transaction affected selves`() = runTest {
        val p = mutablePaginator()
        p.restart(silentlyLoading = true, silentlyResult = true)
        p.goNextPage(silentlyLoading = true, silentlyResult = true)

        p.setElement("OUTSIDE", self = "p0", index = 0, silently = true)
        assertEquals(setOf<Any>("p0"), p.affectedSelves)

        runCatching {
            p.transaction {
                (this as MutableCursorPaginator).setElement(
                    element = "INSIDE", self = "p1", index = 0, silently = true,
                )
                error("rollback!")
            }
        }

        assertEquals(setOf<Any>("p0"), p.affectedSelves)
        assertTrue(p.hasPendingFlush)
    }

    // =========================================================================
    // warmUpFromPersistent
    // =========================================================================

    @Test
    fun `warmUpFromPersistent returns zero when persistentCache is null`() = runTest {
        val p = mutablePaginator(persistent = null)
        assertEquals(0, p.warmUpFromPersistent())
    }

    @Test
    fun `warmUpFromPersistent returns zero when L2 is empty`() = runTest {
        val p = mutablePaginator()
        assertEquals(0, p.warmUpFromPersistent())
        assertTrue(p.cache.cursors.isEmpty())
    }

    @Test
    fun `warmUpFromPersistent populates L1 with every persisted page and rebuilds the chain`() =
        runTest {
            val persistent = InMemoryCursorPersistentCache<String>()
            seed(persistent, count = 3)

            val p = mutablePaginator(persistent = persistent)
            val inserted = p.warmUpFromPersistent()

            assertEquals(3, inserted)
            // All selves present.
            assertNotNull(p.cache.getStateOf("p0"))
            assertNotNull(p.cache.getStateOf("p1"))
            assertNotNull(p.cache.getStateOf("p2"))

            // Chain links survived the round-trip.
            val p0 = p.cache.getCursorOf("p0")!!
            val p2 = p.cache.getCursorOf("p2")!!
            assertEquals("p1", p0.next)
            assertEquals("p1", p2.prev)

            val walkedNext = p.cache.walkForward(p0)
            assertEquals("p1", walkedNext?.self)
        }

    @Test
    fun `warmUpFromPersistent does not overwrite pages already in L1`() = runTest {
        val persistent = InMemoryCursorPersistentCache<String>()
        seed(persistent, count = 2)

        val p = mutablePaginator(persistent = persistent)
        // Seed L1 with a different value for "p0".
        val cursor = CursorBookmark(prev = null, self = "p0", next = "p1")
        p.cache.setState(
            cursor = cursor,
            state = PageState.SuccessPage(page = 0, data = mutableListOf("in-memory")),
            silently = true,
        )

        val inserted = p.warmUpFromPersistent()

        assertEquals(1, inserted, "only p1 should be inserted")
        assertEquals(
            listOf("in-memory"),
            p.cache.getStateOf("p0")?.data,
            "L1 copy must win over L2",
        )
    }

    @Test
    fun `warmUpFromPersistent followed by jump does not call source`() = runTest {
        val persistent = InMemoryCursorPersistentCache<String>()
        seed(persistent, count = 3)

        val backend = FakeCursorBackend(backendPages(count = 3))
        val p = mutablePaginator(persistent = persistent, backend = backend)

        p.warmUpFromPersistent()
        val callsBefore = backend.callCount

        p.jump(
            bookmark = CursorBookmark(prev = null, self = "p1", next = null),
            silentlyLoading = true,
            silentlyResult = true,
        )

        assertEquals(
            callsBefore, backend.callCount,
            "pre-warmed pages must be served from L1 without a network call"
        )
    }

    @Test
    fun `warmUpFromPersistent with cursorRange emits a snapshot`() = runTest {
        val persistent = InMemoryCursorPersistentCache<String>()
        seed(persistent, count = 3)

        val p = mutablePaginator(persistent = persistent)
        p.warmUpFromPersistent()

        // Re-fetch the cursors as stored in L1 so the snapshot range is consistent.
        val head = p.cache.getCursorOf("p0")!!
        val tail = p.cache.getCursorOf("p2")!!
        p.core.snapshot(head to tail)

        val emitted = p.core.snapshot.first()
        assertEquals(3, emitted.size)
    }

    @Test
    fun `warmUpFromPersistent without cursorRange emits no snapshot`() = runTest {
        val persistent = InMemoryCursorPersistentCache<String>()
        seed(persistent, count = 2)

        val p = mutablePaginator(persistent = persistent)
        p.warmUpFromPersistent()

        assertTrue(p.core.snapshot.first().isEmpty())
    }

    @Test
    fun `warmUpFromPersistent respects LRU eviction strategy`() = runTest {
        val persistent = InMemoryCursorPersistentCache<String>()
        seed(persistent, count = 5)

        val lru = LruCursorPagingCache<String>(maxSize = 3, protectContextWindow = false)
        val p = mutablePaginator(persistent = persistent, cache = lru)

        val inserted = p.warmUpFromPersistent()

        assertEquals(5, inserted)
        assertEquals(3, p.cache.cursors.size, "LRU trims to its maxSize regardless of L2 size")
    }

    @Test
    fun `warmUpFromPersistent does not mark selves as affected`() = runTest {
        val persistent = InMemoryCursorPersistentCache<String>()
        seed(persistent, count = 2)

        val p = mutablePaginator(persistent = persistent)
        p.warmUpFromPersistent()

        assertTrue(
            p.affectedSelves.isEmpty(),
            "warming up from L2 is not a CRUD mutation"
        )
        assertFalse(p.hasPendingFlush)
    }
}
