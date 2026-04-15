package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import com.jamal_aliev.paginator.cache.LruPagingCache
import com.jamal_aliev.paginator.cache.PersistentPagingCache
import com.jamal_aliev.paginator.dsl.mutablePaginator
import com.jamal_aliev.paginator.dsl.paginator
import com.jamal_aliev.paginator.extension.plus
import com.jamal_aliev.paginator.load.LoadResult
import com.jamal_aliev.paginator.logger.PaginatorLogger
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PageState.EmptyPage
import com.jamal_aliev.paginator.page.PageState.ErrorPage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
    fun `paginator return type is Paginator (not MutablePaginator)`() {
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
    fun `bookmarks(vararg) replaces the default bookmark list`() {
        val p = paginator<String> {
            load { LoadResult(emptyList()) }
            bookmarks(2, 5, 9)
        }
        assertEquals(listOf(2, 5, 9), p.bookmarks.map { it.page })
    }

    @Test
    fun `bookmarks(list) replaces the default bookmark list`() {
        val p = paginator<String> {
            load { LoadResult(emptyList()) }
            bookmarks(listOf(BookmarkInt(3), BookmarkInt(7)))
        }
        assertEquals(listOf(3, 7), p.bookmarks.map { it.page })
    }

    @Test
    fun `cache property is honored and reachable through core`() {
        val customCache = LruPagingCache<String>(maxSize = 5)
        val p = paginator<String> {
            load { LoadResult(emptyList()) }
            cache = customCache
        }
        // The exact instance is wired into the core.
        assertSame(customCache, p.core.cache)
    }

    @Test
    fun `cache composition with plus operator survives the build`() {
        val composed = LruPagingCache<String>(maxSize = 5) +
                LruPagingCache<String>(maxSize = 10)
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
    fun `initializers DSL overrides the empty factory`() = runTest {
        var emptyCalls = 0
        val p = paginator<String>(capacity = 2) {
            load { LoadResult(emptyList()) } // always empty
            initializers {
                empty { page, data, meta ->
                    emptyCalls++
                    EmptyPage(page = page, data = data, metadata = meta)
                }
            }
        }
        runCatching {
            p.goNextPage(silentlyLoading = true, silentlyResult = true)
        }
        assertTrue(emptyCalls > 0, "custom empty initializer should run, got $emptyCalls call(s)")
    }

    @Test
    fun `initializers DSL overrides the error factory`() = runTest {
        var errorCalls = 0
        val p = paginator<String>(capacity = 2) {
            load { error("boom") } // always fails
            initializers {
                error { e, page, data, meta ->
                    errorCalls++
                    ErrorPage(exception = e, page = page, data = data, metadata = meta)
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
                empty { page, data, meta -> EmptyPage(page, data, meta) }
            }
            initializers {
                error { e, page, data, meta -> ErrorPage(e, page, data, meta) }
            }
        }
        // No assertion needed beyond "did not throw"; but make sure the wiring exists.
        assertNotNull(p.core.initializerEmptyPage)
        assertNotNull(p.core.initializerErrorPage)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Minimal no-op persistent cache for wiring tests. */
    private class StubPersistentCache : PersistentPagingCache<String> {
        override suspend fun save(state: PageState<String>) = Unit
        override suspend fun load(page: Int): PageState<String>? = null
        override suspend fun loadAll(): List<PageState<String>> = emptyList()
        override suspend fun remove(page: Int) = Unit
        override suspend fun clear() = Unit
    }

    /** Minimal logger used only to verify wiring (no formatting concerns). */
    private class NoopLogger : PaginatorLogger {
        override fun log(
            level: com.jamal_aliev.paginator.logger.LogLevel,
            component: com.jamal_aliev.paginator.logger.LogComponent,
            message: String,
        ) = Unit
    }
}
