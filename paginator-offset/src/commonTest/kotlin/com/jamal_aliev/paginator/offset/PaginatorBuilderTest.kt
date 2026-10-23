// Exercises the deprecated L2 persistent cache, which stays tested until it is removed.
@file:Suppress("DEPRECATION")

package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.core.load.Metadata
import com.jamal_aliev.paginator.core.logger.PaginatorLogger
import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.cache.eviction.MostRecentPagingCache
import com.jamal_aliev.paginator.offset.cache.persistent.PersistentPagingCache
import com.jamal_aliev.paginator.offset.dsl.mutablePaginator
import com.jamal_aliev.paginator.offset.dsl.paginator
import com.jamal_aliev.paginator.offset.extension.plus
import com.jamal_aliev.paginator.offset.load.LoadResult
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class PaginatorBuilderTest {

    // =========================================================================
    // Required configuration
    // =========================================================================

    @Test
    fun `paginator builder throws when load is not provided`() {
        assertFailsWith<IllegalStateException> {
            paginator<String> {
                // intentionally no load { ... }
            }
        }
    }

    @Test
    fun `mutablePaginator builder throws when load is not provided`() {
        assertFailsWith<IllegalStateException> {
            mutablePaginator<String> {
                // intentionally no load { ... }
            }
        }
    }

    // =========================================================================
    // Return-type contract
    // =========================================================================

    @Test
    fun `paginator return type is Paginator not MutablePaginator`() {
        // Compile-time guarantee: this only compiles because paginator { } returns
        // Paginator<T>. If it returned MutablePaginator we could downcast freely
        // and lose the read-only contract at the call site.
        val p: Paginator<String> = paginator<String> {
            load { LoadResult(emptyList()) }
        }
        // Confirm the runtime class too: it must NOT be a MutablePaginator
        // subclass leaking through.
        assertEquals("Paginator", p::class.simpleName)
    }

    @Test
    fun `mutablePaginator returns a MutablePaginator that supports mutations`() = runTest {
        val p: MutablePaginator<String> = mutablePaginator(capacity = 2) {
            load { page -> LoadResult(MutableList(2) { "p${page}_$it" }) }
        }
        // Smoke: a MutablePaginator-only API is reachable on the return type.
        p.goNextPage(silentlyLoading = true, silentlyResult = true)
        p.setElement(element = "REPLACED", page = 1, index = 0, silently = true)
        assertEquals("REPLACED", p.cache.getElement(1, 0))
    }

    // =========================================================================
    // Defaults
    // =========================================================================

    @Test
    fun `defaults match a plain MutablePaginator`() = runTest {
        val p = paginator<String>(capacity = 4) {
            load { page -> LoadResult(MutableList(4) { "p${page}_$it" }) }
        }

        assertEquals(4, p.core.capacity)
        assertEquals(Int.MAX_VALUE, p.finalPage)
        assertEquals(false, p.recyclingBookmark)
        // Default bookmarks list contains BookmarkInt(1) — same as MutablePaginator default.
        assertEquals(1, p.bookmarks.size)
        assertEquals(1, p.bookmarks.first().page)

        // Smoke: the paginator actually loads.
        p.goNextPage(silentlyLoading = true, silentlyResult = true)
        assertEquals(4, p.cache.getStateOf(1)?.data?.size)
    }

    // =========================================================================
    // Wiring of optional knobs
    // =========================================================================

    @Test
    fun `finalPage and recyclingBookmark are forwarded to the paginator`() {
        val p = paginator<String> {
            load { LoadResult(emptyList()) }
            finalPage = 42
            recyclingBookmark = true
        }
        assertEquals(42, p.finalPage)
        assertEquals(true, p.recyclingBookmark)
    }

    @Test
    fun `bookmarks vararg replaces the default bookmark list`() {
        val p = paginator<String> {
            load { LoadResult(emptyList()) }
            bookmarks(2, 5, 9)
        }
        assertEquals(listOf(2, 5, 9), p.bookmarks.map { it.page })
    }

    @Test
    fun `bookmarks list replaces the default bookmark list`() {
        val p = paginator<String> {
            load { LoadResult(emptyList()) }
            bookmarks(listOf(BookmarkInt(3), BookmarkInt(7)))
        }
        assertEquals(listOf(3, 7), p.bookmarks.map { it.page })
    }

    @Test
    fun `cache property is honored and reachable through core`() {
        val customCache = MostRecentPagingCache<String>(maxSize = 5)
        val p = paginator<String> {
            load { LoadResult(emptyList()) }
            cache = customCache
        }
        // The exact instance is wired into the core.
        assertSame(customCache, p.core.cache)
    }

    @Test
    fun `cache composition with plus operator survives the build`() {
        val composed = MostRecentPagingCache<String>(maxSize = 5) +
                MostRecentPagingCache<String>(maxSize = 10)
        val p = paginator<String> {
            load { LoadResult(emptyList()) }
            cache = composed
        }
        assertSame(composed, p.core.cache)
    }

    @Test
    fun `persistentCache is forwarded to the core`() {
        val pc = StubPersistentCache()
        val p = paginator<String> {
            load { LoadResult(emptyList()) }
            persistentCache = pc
        }
        assertSame(pc, p.core.persistentCache)
    }

    @Test
    fun `logger is forwarded both to the paginator and its core`() {
        val log = NoopLogger()
        val p = paginator<String> {
            load { LoadResult(emptyList()) }
            logger = log
        }
        assertSame(log, p.logger)
        assertSame(log, p.core.logger)
    }

    // =========================================================================
    // Initializers DSL
    // =========================================================================

    @Test
    fun `initializers DSL success factory is used for empty results`() = runTest {
        var successCalls = 0
        val p = paginator<String>(capacity = 2) {
            load { LoadResult(emptyList()) } // always empty
            initializers {
                success { page, data, meta ->
                    successCalls++
                    OffsetPageState.Success(page = page, data = data, metadata = meta)
                }
            }
        }
        runCatching {
            p.goNextPage(silentlyLoading = true, silentlyResult = true)
        }
        assertTrue(
            successCalls > 0,
            "custom success initializer should run, got $successCalls call(s)"
        )
    }

    @Test
    fun `initializers DSL overrides the error factory`() = runTest {
        var errorCalls = 0
        val p = paginator<String>(capacity = 2) {
            load { error("boom") } // always fails
            initializers {
                error { e, page, data, meta ->
                    errorCalls++
                    OffsetPageState.Error(exception = e, page = page, data = data, metadata = meta)
                }
            }
        }
        runCatching {
            p.goNextPage(silentlyLoading = true, silentlyResult = true)
        }
        assertTrue(errorCalls > 0, "custom error initializer should run, got $errorCalls call(s)")
    }

    @Test
    fun `initializers block can be invoked multiple times and merges`() {
        val p = paginator<String> {
            load { LoadResult(emptyList()) }
            initializers {
                success { page, data, meta -> OffsetPageState.Success(page, data, meta) }
            }
            initializers {
                error { e, page, data, meta -> OffsetPageState.Error(e, page, data, meta) }
            }
        }
        // No assertion needed beyond "did not throw"; but make sure the wiring exists.
        assertNotNull(p.core.initializerSuccessPage)
        assertNotNull(p.core.initializerErrorPage)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Minimal no-op persistent cache for wiring tests. */
    private class StubPersistentCache : PersistentPagingCache<String> {
        override suspend fun save(state: OffsetPageState<String>) = Unit
        override suspend fun load(page: Int): OffsetPageState<String>? = null
        override suspend fun loadAll(): List<OffsetPageState<String>> = emptyList()
        override suspend fun remove(page: Int) = Unit
        override suspend fun clear() = Unit
    }

    /** Minimal logger used only to verify wiring (no formatting concerns). */
    private class NoopLogger : PaginatorLogger {
        override fun log(
            level: com.jamal_aliev.paginator.core.logger.LogLevel,
            component: com.jamal_aliev.paginator.core.logger.LogComponent,
            message: String,
        ) = Unit
    }

    // =========================================================================
    // livePages { } — the DSL form of observePages
    // =========================================================================

    private class LiveTable {
        val rows = MutableStateFlow(List(20) { "r$it" })
        fun observe(limit: Int, offset: Int): Flow<List<String>> = rows.map { it.drop(offset).take(limit) }
    }

    @Test
    fun `livePages without load loads the pages from the live source and keeps them live`() = runTest {
        val table = LiveTable()
        val paginator = mutablePaginator<String>(capacity = 3) {
            livePages(backgroundScope) { page -> table.observe(core.capacity, (page - 1) * core.capacity) }
        }

        paginator.jump(BookmarkInt(1))
        paginator.goNextPage()
        assertEquals(listOf("r3", "r4", "r5"), paginator.cache.getStateOf(2)!!.data)

        table.rows.value = table.rows.value.toMutableList().also { it[4] = "edited" }
        runCurrent()
        assertEquals(listOf("r3", "edited", "r5"), paginator.cache.getStateOf(2)!!.data)
    }

    @Test
    fun `livePageResults carries metadata into the pages`() = runTest {
        class Total(val count: Int) : Metadata()

        val table = LiveTable()
        val paginator = mutablePaginator<String>(capacity = 3) {
            livePageResults(backgroundScope) { page ->
                table.observe(core.capacity, (page - 1) * core.capacity).map { LoadResult(it, Total(20)) }
            }
        }

        paginator.jump(BookmarkInt(1))

        assertEquals(20, (paginator.cache.getStateOf(1)!!.metadata as Total).count)
    }

    @Test
    fun `livePages forwards onError`() = runTest {
        val errors = mutableListOf<Int>()
        val paginator = mutablePaginator<String>(capacity = 3) {
            livePages(backgroundScope, onError = { page, _ -> errors += page }) { _ ->
                flow {
                    emit(listOf("a", "b", "c"))
                    throw IllegalStateException("db closed")
                }
            }
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        runCurrent()

        assertTrue(errors.isNotEmpty() && errors.all { it == 1 })
    }

    @Test
    fun `a configured load is always used and the live source keeps its pages current`() = runTest {
        val table = LiveTable()
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        var loads = 0
        val paginator = mutablePaginator<String>(capacity = 3) {
            load { page -> loads++; LoadResult(MutableList(3) { "load_p${page}_$it" }) }
            livePages(scope) { page -> table.observe(core.capacity, (page - 1) * core.capacity) }
        }
        paginator.jump(BookmarkInt(1))
        assertEquals(1, loads)
        assertEquals("load_p1_0", paginator.cache.getStateOf(1)!!.data.first())
        runCurrent()
        assertEquals("r0", paginator.cache.getStateOf(1)!!.data.first(), "the live query corrects the page")

        scope.cancel()
        runCurrent()
        paginator.goNextPage()

        assertEquals(2, loads)
        assertEquals("load_p2_0", paginator.cache.getStateOf(2)!!.data.first())
    }

    @Test
    fun `without load the derived load keeps working after the livePages scope ended`() = runTest {
        val table = LiveTable()
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val paginator = mutablePaginator<String>(capacity = 3) {
            livePages(scope) { page -> table.observe(core.capacity, (page - 1) * core.capacity) }
        }
        scope.cancel()
        runCurrent()

        paginator.jump(BookmarkInt(1))

        assertEquals(listOf("r0", "r1", "r2"), paginator.cache.getStateOf(1)!!.data)
    }

    @Test
    fun `livePages forwards settle`() = runTest {
        val sources = mutableMapOf<Int, MutableStateFlow<List<String>>>()
        fun src(page: Int) = sources.getOrPut(page) { MutableStateFlow(List(3) { "p${page}_$it" }) }
        val paginator = mutablePaginator<String>(capacity = 3) {
            livePages(backgroundScope, settle = 100.milliseconds) { page -> src(page) }
        }
        paginator.jump(BookmarkInt(1))
        paginator.goNextPage()
        runCurrent()

        src(1).value = listOf("x", "y", "z")
        advanceTimeBy(50)
        runCurrent()
        assertEquals("p1_0", paginator.cache.getStateOf(1)!!.data.first(), "held for the round")
        advanceTimeBy(60)
        runCurrent()
        assertEquals("x", paginator.cache.getStateOf(1)!!.data.first())
    }
}
