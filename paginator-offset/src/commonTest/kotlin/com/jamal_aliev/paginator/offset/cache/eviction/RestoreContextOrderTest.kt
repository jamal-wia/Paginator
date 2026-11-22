package com.jamal_aliev.paginator.offset.cache.eviction

import com.jamal_aliev.paginator.core.cache.eviction.CacheEvictionListener
import com.jamal_aliev.paginator.offset.MutablePaginator
import com.jamal_aliev.paginator.offset.PagingCore
import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.cache.PagingCache
import com.jamal_aliev.paginator.offset.load.LoadResult
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import com.jamal_aliev.paginator.offset.serialization.PageEntry
import com.jamal_aliev.paginator.offset.serialization.PagingCoreSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `restoreState` and the `transaction` rollback rebuild the cache page by page. The context window
 * must already describe the restored pages while they are written, otherwise a strategy that
 * evicts by the window (ContextWindow, protected LRU/FIFO/TTL) judges them by the window the
 * paginator had **before** the restore and throws restored pages away.
 */
class RestoreContextOrderTest {

    private val capacity = 3

    private class Env(
        val paginator: MutablePaginator<String>,
        val evicted: MutableList<Int>,
    )

    private fun createEnv(buildCache: (CacheEvictionListener<String>) -> PagingCache<String>): Env {
        val evicted = mutableListOf<Int>()
        val listener = CacheEvictionListener<String> { evicted.add((it as OffsetPageState).page) }
        val paginator = MutablePaginator(
            core = PagingCore(cache = buildCache(listener), initialCapacity = capacity)
        ) { page: Int ->
            if (page in 1..20) LoadResult(List(this.core.capacity) { "p${page}_item$it" })
            else LoadResult(emptyList())
        }
        return Env(paginator, evicted)
    }

    private fun contextWindowEnv(margin: Int): Env = createEnv { listener ->
        ContextWindowPagingCache(margin = margin, evictionListener = listener)
    }

    private fun entry(page: Int, size: Int = capacity, wasDirty: Boolean = false) = PageEntry(
        page = page,
        data = List(size) { "p${page}_item$it" },
        wasDirty = wasDirty,
    )

    private fun snapshotOf(
        start: Int,
        end: Int,
        entries: List<PageEntry<String>>,
    ) = PagingCoreSnapshot(
        entries = entries,
        startContextPage = start,
        endContextPage = end,
        capacity = capacity,
    )

    private suspend fun MutablePaginator<String>.loadPages(first: Int, last: Int) {
        jump(BookmarkInt(first))
        repeat(last - first) { goNextPage() }
    }

    private fun PagingCore<String>.putPage(page: Int, size: Int = capacity) {
        cache.setState(
            initializerSuccessPage(page, MutableList(size) { "p${page}_item$it" }, null),
            silently = true
        )
    }

    // ── restoreState ─────────────────────────────────────────────────────────

    @Test
    fun `restoreState after jump keeps all saved pages with strict context window`() = runTest {
        val env = contextWindowEnv(margin = 0)
        val paginator = env.paginator
        paginator.loadPages(1, 3)
        val snapshot = paginator.core.saveState()

        paginator.jump(BookmarkInt(10))
        assertEquals(listOf(10), paginator.cache.pages)
        env.evicted.clear()

        paginator.core.restoreState(snapshot)

        assertEquals(listOf(1, 2, 3), paginator.cache.pages)
        assertEquals(1, paginator.core.startContextPage)
        assertEquals(3, paginator.core.endContextPage)
        // the old window (page 10) is cleared, not evicted: no page may be evicted on the way
        assertEquals(emptyList(), env.evicted)
    }

    @Test
    fun `restoreState through the paginator after jump keeps all saved pages`() = runTest {
        val env = contextWindowEnv(margin = 0)
        val paginator = env.paginator
        paginator.loadPages(1, 3)
        val snapshot = paginator.saveState()

        paginator.jump(BookmarkInt(10))
        env.evicted.clear()

        paginator.restoreState(snapshot)

        assertEquals(listOf(1, 2, 3), paginator.cache.pages)
        assertEquals(1, paginator.core.startContextPage)
        assertEquals(3, paginator.core.endContextPage)
        assertEquals(emptyList(), env.evicted)
    }

    @Test
    fun `restoreState after jump keeps margin pages with context window margin`() = runTest {
        val env = contextWindowEnv(margin = 1)
        val paginator = env.paginator
        val snapshot = snapshotOf(
            start = 2, end = 4,
            entries = (1..5).map { entry(it) },
        )

        paginator.jump(BookmarkInt(10))
        env.evicted.clear()

        paginator.core.restoreState(snapshot)

        assertEquals(listOf(1, 2, 3, 4, 5), paginator.cache.pages)
        assertEquals(2, paginator.core.startContextPage)
        assertEquals(4, paginator.core.endContextPage)
        assertEquals(emptyList(), env.evicted)
    }

    @Test
    fun `restoreState keeps incomplete last page of restored window`() = runTest {
        val env = contextWindowEnv(margin = 0)
        val paginator = env.paginator
        val snapshot = snapshotOf(
            start = 1, end = 3,
            entries = listOf(entry(1), entry(2), entry(3, size = 2)),
        )

        paginator.jump(BookmarkInt(10))
        env.evicted.clear()

        paginator.core.restoreState(snapshot)

        assertEquals(listOf(1, 2, 3), paginator.cache.pages)
        assertEquals(2, paginator.cache.getStateOf(3)!!.data.size)
        assertEquals(1, paginator.core.startContextPage)
        assertEquals(3, paginator.core.endContextPage)
        assertEquals(emptyList(), env.evicted)
    }

    @Test
    fun `restoreState of unstarted snapshot into started paginator evicts nothing`() = runTest {
        val env = contextWindowEnv(margin = 0)
        val paginator = env.paginator
        val snapshot = snapshotOf(
            start = 0, end = 0,
            entries = (1..3).map { entry(it) },
        )

        paginator.jump(BookmarkInt(10))
        assertTrue(paginator.core.isStarted)
        env.evicted.clear()

        paginator.core.restoreState(snapshot)

        assertEquals(listOf(1, 2, 3), paginator.cache.pages)
        assertFalse(paginator.core.isStarted)
        assertEquals(emptyList(), env.evicted)
    }

    @Test
    fun `restoreState protects restored window in lru cache over max size`() = runTest {
        val env = createEnv { listener ->
            MostRecentPagingCache(maxSize = 2, protectContextWindow = true, evictionListener = listener)
        }
        val paginator = env.paginator
        // Written in the order 2, 1, 3: the first written page is part of the restored window. Judged
        // by the old window (page 10) nothing is protected and the oldest page, 2, would go.
        val snapshot = snapshotOf(
            start = 2, end = 3,
            entries = listOf(entry(2), entry(1), entry(3)),
        )

        paginator.jump(BookmarkInt(10))
        env.evicted.clear()

        paginator.core.restoreState(snapshot)

        assertEquals(listOf(2, 3), paginator.cache.pages)
        assertEquals(listOf(1), env.evicted)
        assertEquals(2, paginator.core.startContextPage)
        assertEquals(3, paginator.core.endContextPage)
    }

    @Test
    fun `restoreState keeps dirty marks of restored pages`() = runTest {
        val env = contextWindowEnv(margin = 0)
        val paginator = env.paginator
        val snapshot = snapshotOf(
            start = 1, end = 3,
            entries = listOf(entry(1), entry(2, wasDirty = true), entry(3)),
        )

        paginator.jump(BookmarkInt(10))
        paginator.core.restoreState(snapshot, silently = true)

        assertEquals(listOf(1, 2, 3), paginator.cache.pages)
        assertEquals(setOf(2), paginator.core.dirtyPages)
    }

    @Test
    fun `restoreState of a snapshot bigger than the cache narrows the window and publishes it`() = runTest {
        val env = createEnv { listener ->
            MostRecentPagingCache(maxSize = 2, protectContextWindow = false, evictionListener = listener)
        }
        val paginator = env.paginator
        // three pages into a cache of two without window protection: page 1 is evicted while the
        // pages are written, although it is the start of the restored window
        val snapshot = snapshotOf(
            start = 1, end = 3,
            entries = (1..3).map { entry(it) },
        )

        paginator.core.restoreState(snapshot)

        assertEquals(listOf(1), env.evicted)
        assertEquals(listOf(2, 3), paginator.cache.pages)
        assertEquals(2, paginator.core.startContextPage)
        assertEquals(3, paginator.core.endContextPage)
        assertEquals(listOf(2, 3), paginator.core.snapshot.first().map { it.page })
    }

    // ── transaction rollback ─────────────────────────────────────────────────

    @Test
    fun `rollback after successful jump keeps all saved pages with strict context window`() = runTest {
        val env = contextWindowEnv(margin = 0)
        val paginator = env.paginator
        paginator.loadPages(1, 3)

        assertFailsWith<IllegalStateException> {
            paginator.transaction {
                jump(BookmarkInt(10))
                assertEquals(listOf(10), cache.pages)
                env.evicted.clear()
                error("boom")
            }
        }

        assertEquals(listOf(1, 2, 3), paginator.cache.pages)
        assertEquals(1, paginator.core.startContextPage)
        assertEquals(3, paginator.core.endContextPage)
        assertFalse(1 in env.evicted)
        assertFalse(2 in env.evicted)
        assertEquals(emptyList(), env.evicted)
    }

    @Test
    fun `rollback after successful jump keeps margin pages with context window margin`() = runTest {
        val env = contextWindowEnv(margin = 1)
        val paginator = env.paginator
        // window 1..3, page 4 is a margin page that sits in the cache outside the window
        for (page in 1..4) paginator.core.putPage(page)
        paginator.core.startContextPage = 1
        paginator.core.endContextPage = 3
        assertEquals(listOf(1, 2, 3, 4), paginator.cache.pages)

        assertFailsWith<IllegalStateException> {
            paginator.transaction {
                jump(BookmarkInt(10))
                env.evicted.clear()
                error("boom")
            }
        }

        assertEquals(listOf(1, 2, 3, 4), paginator.cache.pages)
        assertEquals(1, paginator.core.startContextPage)
        assertEquals(3, paginator.core.endContextPage)
        assertEquals(emptyList(), env.evicted)
    }

    @Test
    fun `rollback keeps incomplete page and dirty marks`() = runTest {
        val env = contextWindowEnv(margin = 0)
        val paginator = env.paginator
        paginator.core.putPage(1)
        paginator.core.putPage(2)
        paginator.core.putPage(3, size = 2)
        paginator.core.startContextPage = 1
        paginator.core.endContextPage = 3
        paginator.core.markDirty(2)
        val dataBefore = paginator.cache.pages.associateWith { paginator.cache.getStateOf(it)!!.data.toList() }

        assertFailsWith<IllegalStateException> {
            paginator.transaction {
                jump(BookmarkInt(10))
                env.evicted.clear()
                error("boom")
            }
        }

        assertEquals(listOf(1, 2, 3), paginator.cache.pages)
        assertEquals(dataBefore, paginator.cache.pages.associateWith { paginator.cache.getStateOf(it)!!.data.toList() })
        assertEquals(2, paginator.cache.getStateOf(3)!!.data.size)
        assertEquals(setOf(2), paginator.core.dirtyPages)
        assertEquals(1, paginator.core.startContextPage)
        assertEquals(3, paginator.core.endContextPage)
        assertEquals(emptyList(), env.evicted)
    }

    @Test
    fun `rollback protects saved window in lru cache over max size`() = runTest {
        val env = createEnv { listener ->
            MostRecentPagingCache(maxSize = 2, protectContextWindow = true, evictionListener = listener)
        }
        val paginator = env.paginator
        // the cache holds more than maxSize protected pages (window 1..3, a protected window may
        // overflow); the window is set first, otherwise the pages written before it are not protected
        paginator.core.startContextPage = 1
        paginator.core.endContextPage = 3
        for (page in 1..3) paginator.core.putPage(page)
        assertEquals(listOf(1, 2, 3), paginator.cache.pages)

        assertFailsWith<IllegalStateException> {
            paginator.transaction {
                jump(BookmarkInt(10))
                env.evicted.clear()
                error("boom")
            }
        }

        // all three pages belong to the restored window, so none of them is evicted
        assertEquals(listOf(1, 2, 3), paginator.cache.pages)
        assertEquals(emptyList(), env.evicted)
    }
}
