package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.core.cache.eviction.CacheEvictionListener
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.cache.CursorPagingCache
import com.jamal_aliev.paginator.cursor.cache.eviction.CursorContextWindowPagingCache
import com.jamal_aliev.paginator.cursor.cache.eviction.CursorMostRecentPagingCache
import com.jamal_aliev.paginator.cursor.page.CursorPageState
import com.jamal_aliev.paginator.cursor.serialization.CursorPageEntry
import com.jamal_aliev.paginator.cursor.serialization.CursorPagingCoreSnapshot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `restoreState` and the `transaction` rollback rebuild the cache page by page. The context window
 * must already describe the restored pages while they are written, otherwise a strategy that
 * evicts by the window (ContextWindow, protected LRU/FIFO/TTL) judges them by the window the
 * paginator had **before** the restore and throws restored pages away.
 */
class CursorRestoreContextOrderTest {

    private val capacity = 3

    private class Env(
        val paginator: CursorPaginator<String, String>,
        val evicted: MutableList<String>,
    )

    private fun createEnv(buildCache: (CacheEvictionListener<String>) -> CursorPagingCache<String, String>): Env {
        val evicted = mutableListOf<String>()
        val listener = CacheEvictionListener<String> {
            evicted.add((it as CursorPageState<*, *>).bookmark.self as String)
        }
        val backend = FakeCursorBackend(FakeCursorBackend.defaultPages(pageCount = 12, capacity = capacity))
        val core = CursorPagingCore<String, String>(cache = buildCache(listener), initialCapacity = capacity)
        val paginator = CursorPaginator<String, String>(core = core) { cursor -> backend.loadResult(cursor) }
        return Env(paginator, evicted)
    }

    private fun contextWindowEnv(margin: Int): Env = createEnv { listener ->
        CursorContextWindowPagingCache(margin = margin, evictionListener = listener)
    }

    private fun cb(self: String) = CursorBookmark<String>(prev = null, self = self, next = null)

    private val encode: (String) -> JsonElement = { JsonPrimitive(it) }
    private val decode: (JsonElement) -> String = { it.jsonPrimitive.content }

    /** The entry of page `p<i>` in the chain `p0 -> p1 -> ... -> p<last>`. */
    private fun entry(i: Int, last: Int, size: Int = capacity, wasDirty: Boolean = false) = CursorPageEntry(
        selfKey = JsonPrimitive("p$i"),
        prevKey = if (i == 0) null else JsonPrimitive("p${i - 1}"),
        nextKey = if (i == last) null else JsonPrimitive("p${i + 1}"),
        data = List(size) { "p${i}_item$it" },
        wasDirty = wasDirty,
    )

    private fun snapshotOf(
        start: String?,
        end: String?,
        entries: List<CursorPageEntry<String>>,
    ) = CursorPagingCoreSnapshot(
        entries = entries,
        startContextSelf = start?.let { JsonPrimitive(it) },
        endContextSelf = end?.let { JsonPrimitive(it) },
        capacity = capacity,
    )

    private suspend fun CursorPaginator<String, String>.loadChain(last: Int) {
        jump(cb("p0"))
        repeat(last) { goNextPage() }
    }

    private fun CursorPaginator<String, String>.cachedSelves(): Set<String> =
        cache.cursors.map { it.self }.toSet()

    private fun CursorPaginator<String, String>.window(): Pair<String?, String?> =
        core.startContextCursor?.self to core.endContextCursor?.self

    /** Puts pages `p<from>..p<to>` of the chain `p0 -> ... -> p<chainEnd>` straight into the cache. */
    private fun CursorPagingCore<String, String>.putChain(from: Int, to: Int, chainEnd: Int, lastSize: Int = capacity) {
        for (i in from..to) {
            val bookmark = CursorBookmark<String>(
                prev = if (i == 0) null else "p${i - 1}",
                self = "p$i",
                next = if (i == chainEnd) null else "p${i + 1}",
            )
            val size = if (i == to) lastSize else capacity
            cache.setState(
                bookmark,
                CursorPageState.Success(bookmark, MutableList(size) { "p${i}_item$it" }),
                silently = true
            )
        }
    }

    // ── restoreState ─────────────────────────────────────────────────────────

    @Test
    fun `restoreState after jump keeps whole saved chain with strict context window`() = runTest {
        val env = contextWindowEnv(margin = 0)
        val paginator = env.paginator
        paginator.loadChain(2)
        val snapshot = paginator.core.saveState(selfEncoder = encode)

        paginator.jump(cb("p6"))
        assertEquals(setOf("p6"), paginator.cachedSelves())
        env.evicted.clear()

        paginator.core.restoreState(snapshot, selfDecoder = decode)

        assertEquals(setOf("p0", "p1", "p2"), paginator.cachedSelves())
        assertEquals("p0" to "p2", paginator.window())
        // the old window (p6) is cleared, not evicted: no page may be evicted on the way
        assertEquals(emptyList(), env.evicted)
    }

    @Test
    fun `restoreState through the paginator after jump keeps whole saved chain`() = runTest {
        val env = contextWindowEnv(margin = 0)
        val paginator = env.paginator
        paginator.loadChain(2)
        val snapshot = paginator.saveState(encode)

        paginator.jump(cb("p6"))
        env.evicted.clear()

        paginator.restoreState(snapshot, decode)

        assertEquals(setOf("p0", "p1", "p2"), paginator.cachedSelves())
        assertEquals("p0" to "p2", paginator.window())
        assertEquals(emptyList(), env.evicted)
    }

    @Test
    fun `restoreState after jump keeps margin links with context window margin`() = runTest {
        val env = contextWindowEnv(margin = 1)
        val paginator = env.paginator
        val snapshot = snapshotOf(
            start = "p1", end = "p3",
            entries = (0..4).map { entry(it, last = 4) },
        )

        paginator.jump(cb("p6"))
        env.evicted.clear()

        paginator.core.restoreState(snapshot, selfDecoder = decode)

        assertEquals(setOf("p0", "p1", "p2", "p3", "p4"), paginator.cachedSelves())
        assertEquals("p1" to "p3", paginator.window())
        assertEquals(emptyList(), env.evicted)
    }

    @Test
    fun `restoreState keeps incomplete tail page of restored chain`() = runTest {
        val env = contextWindowEnv(margin = 0)
        val paginator = env.paginator
        val snapshot = snapshotOf(
            start = "p0", end = "p2",
            entries = listOf(entry(0, last = 2), entry(1, last = 2), entry(2, last = 2, size = 2)),
        )

        paginator.jump(cb("p6"))
        env.evicted.clear()

        paginator.core.restoreState(snapshot, selfDecoder = decode)

        assertEquals(setOf("p0", "p1", "p2"), paginator.cachedSelves())
        assertEquals(2, paginator.cache.getStateOf("p2")!!.data.size)
        assertNull(paginator.cache.getCursorOf("p2")!!.next)
        assertEquals("p2", paginator.core.endContextCursor!!.self)
        assertEquals("p0" to "p2", paginator.window())
        assertEquals(emptyList(), env.evicted)
    }

    @Test
    fun `restoreState with context self missing from entries leaves bounds unset`() = runTest {
        val env = contextWindowEnv(margin = 0)
        val paginator = env.paginator
        val snapshot = snapshotOf(
            start = "ghost", end = "p2",
            entries = (0..2).map { entry(it, last = 2) },
        )

        paginator.jump(cb("p6"))
        env.evicted.clear()

        paginator.core.restoreState(snapshot, selfDecoder = decode)

        assertNull(paginator.core.startContextCursor)
        assertNotNull(paginator.core.endContextCursor)
        assertFalse(paginator.core.isStarted)
        assertEquals(setOf("p0", "p1", "p2"), paginator.cachedSelves())
        assertEquals(emptyList(), env.evicted)
    }

    @Test
    fun `restoreState of unstarted snapshot into started paginator evicts nothing`() = runTest {
        val env = contextWindowEnv(margin = 0)
        val paginator = env.paginator
        val snapshot = snapshotOf(
            start = null, end = null,
            entries = (0..2).map { entry(it, last = 2) },
        )

        paginator.jump(cb("p6"))
        assertNotNull(paginator.core.startContextCursor)
        env.evicted.clear()

        paginator.core.restoreState(snapshot, selfDecoder = decode)

        assertEquals(setOf("p0", "p1", "p2"), paginator.cachedSelves())
        assertFalse(paginator.core.isStarted)
        assertEquals(emptyList(), env.evicted)
    }

    @Test
    fun `restoreState protects restored window in lru cache over max size`() = runTest {
        val env = createEnv { listener ->
            CursorMostRecentPagingCache(maxSize = 2, protectContextWindow = true, evictionListener = listener)
        }
        val paginator = env.paginator
        // Window p1..p2, three entries: one of them has to go. Judged by the old window (p6) nothing
        // is protected and the least recent page, p1, would go.
        val snapshot = snapshotOf(
            start = "p1", end = "p2",
            entries = listOf(entry(1, last = 2), entry(0, last = 2), entry(2, last = 2)),
        )

        paginator.jump(cb("p6"))
        env.evicted.clear()

        paginator.core.restoreState(snapshot, selfDecoder = decode)

        assertEquals(setOf("p1", "p2"), paginator.cachedSelves())
        assertEquals(listOf("p0"), env.evicted)
        assertEquals("p1" to "p2", paginator.window())
    }

    @Test
    fun `restoreState keeps dirty marks of restored pages`() = runTest {
        val env = contextWindowEnv(margin = 0)
        val paginator = env.paginator
        val snapshot = snapshotOf(
            start = "p0", end = "p2",
            entries = listOf(entry(0, last = 2), entry(1, last = 2, wasDirty = true), entry(2, last = 2)),
        )

        paginator.jump(cb("p6"))
        paginator.core.restoreState(snapshot, silently = true, selfDecoder = decode)

        assertEquals(setOf("p0", "p1", "p2"), paginator.cachedSelves())
        assertEquals(setOf("p1"), paginator.core.dirtyCursors)
    }

    // ── transaction rollback ─────────────────────────────────────────────────

    @Test
    fun `rollback after successful jump keeps whole saved chain with strict context window`() = runTest {
        val env = contextWindowEnv(margin = 0)
        val paginator = env.paginator
        paginator.loadChain(2)

        assertFailsWith<IllegalStateException> {
            paginator.transaction {
                jump(cb("p6"))
                assertEquals(setOf("p6"), cache.cursors.map { it.self }.toSet())
                env.evicted.clear()
                error("boom")
            }
        }

        assertEquals(setOf("p0", "p1", "p2"), paginator.cachedSelves())
        assertEquals("p0" to "p2", paginator.window())
        assertEquals(emptyList(), env.evicted)
    }

    @Test
    fun `rollback after successful jump keeps margin links with context window margin`() = runTest {
        val env = contextWindowEnv(margin = 1)
        val paginator = env.paginator
        // window p1..p3, p0 and p4 are margin pages that sit in the cache outside the window
        paginator.core.putChain(0, 4, chainEnd = 4)
        paginator.core.startContextCursor = paginator.cache.getCursorOf("p1")
        paginator.core.endContextCursor = paginator.cache.getCursorOf("p3")
        assertEquals(setOf("p0", "p1", "p2", "p3", "p4"), paginator.cachedSelves())

        assertFailsWith<IllegalStateException> {
            paginator.transaction {
                jump(cb("p6"))
                env.evicted.clear()
                error("boom")
            }
        }

        assertEquals(setOf("p0", "p1", "p2", "p3", "p4"), paginator.cachedSelves())
        assertEquals("p1" to "p3", paginator.window())
        assertEquals(emptyList(), env.evicted)
    }

    @Test
    fun `rollback keeps incomplete tail page and dirty marks`() = runTest {
        val env = contextWindowEnv(margin = 0)
        val paginator = env.paginator
        paginator.core.putChain(0, 2, chainEnd = 2, lastSize = 2)
        paginator.core.startContextCursor = paginator.cache.getCursorOf("p0")
        paginator.core.endContextCursor = paginator.cache.getCursorOf("p2")
        paginator.core.markDirty("p1")
        val dataBefore = paginator.cachedSelves().associateWith { paginator.cache.getStateOf(it)!!.data.toList() }

        assertFailsWith<IllegalStateException> {
            paginator.transaction {
                jump(cb("p6"))
                env.evicted.clear()
                error("boom")
            }
        }

        assertEquals(setOf("p0", "p1", "p2"), paginator.cachedSelves())
        assertEquals(dataBefore, paginator.cachedSelves().associateWith { paginator.cache.getStateOf(it)!!.data.toList() })
        assertEquals(2, paginator.cache.getStateOf("p2")!!.data.size)
        assertEquals(setOf("p1"), paginator.core.dirtyCursors)
        assertEquals("p0" to "p2", paginator.window())
        assertEquals(emptyList(), env.evicted)
    }

    @Test
    fun `rollback protects saved window in lru cache over max size`() = runTest {
        val env = createEnv { listener ->
            CursorMostRecentPagingCache(maxSize = 2, protectContextWindow = true, evictionListener = listener)
        }
        val paginator = env.paginator
        // the window p0..p2 is bigger than maxSize; the window is set first, otherwise the pages
        // written before it are not protected
        paginator.core.startContextCursor = CursorBookmark(prev = null, self = "p0", next = "p1")
        paginator.core.endContextCursor = CursorBookmark(prev = "p1", self = "p2", next = null)
        paginator.core.putChain(0, 2, chainEnd = 2)
        assertEquals(setOf("p0", "p1", "p2"), paginator.cachedSelves())

        assertFailsWith<IllegalStateException> {
            paginator.transaction {
                jump(cb("p6"))
                env.evicted.clear()
                error("boom")
            }
        }

        // all three pages belong to the restored window, so none of them is evicted
        assertEquals(setOf("p0", "p1", "p2"), paginator.cachedSelves())
        assertEquals(emptyList(), env.evicted)
    }
}
