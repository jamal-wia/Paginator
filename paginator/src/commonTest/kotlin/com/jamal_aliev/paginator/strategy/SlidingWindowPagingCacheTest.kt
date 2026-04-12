package com.jamal_aliev.paginator.strategy

import com.jamal_aliev.paginator.MutablePaginator
import com.jamal_aliev.paginator.PagingCore
import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.source.SourceResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlidingWindowPagingCacheTest {

    private fun successPage(page: Int, data: List<String> = listOf("item_$page")): PageState.SuccessPage<String> {
        return PageState.SuccessPage(page = page, data = data)
    }

    // -- Unit tests --

    @Test
    fun `evicts pages outside context window`() {
        val core = SlidingWindowPagingCache<String>()

        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)
        core.setState(successPage(3), silently = true)
        core.setState(successPage(5), silently = true)

        // Set context window to 2..3
        core.startContextPage = 2
        core.endContextPage = 3

        // Trigger eviction by adding a page inside the window
        core.setState(successPage(2, listOf("updated")), silently = true)

        // Pages 1 and 5 should be evicted (outside context 2..3)
        assertNull(core.getStateOf(1))
        assertNotNull(core.getStateOf(2))
        assertNotNull(core.getStateOf(3))
        assertNull(core.getStateOf(5))
    }

    @Test
    fun `margin extends the keep range`() {
        val core = SlidingWindowPagingCache<String>(margin = 1)

        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)
        core.setState(successPage(3), silently = true)
        core.setState(successPage(4), silently = true)
        core.setState(successPage(5), silently = true)

        // Context window = 2..4, margin = 1 -> keep range = 1..5
        core.startContextPage = 2
        core.endContextPage = 4

        core.setState(successPage(3, listOf("trigger")), silently = true)

        // All pages 1-5 should be kept (within margin)
        assertEquals(5, core.size)

        // But page 6 is outside margin
        core.setState(successPage(6), silently = true)
    }

    @Test
    fun `no eviction when paginator not started`() {
        val core = SlidingWindowPagingCache<String>()

        // Not started (startContextPage = 0, endContextPage = 0)
        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)
        core.setState(successPage(3), silently = true)

        // No eviction -- paginator hasn't started yet
        assertEquals(3, core.size)
    }

    @Test
    fun `evictionListener called for evicted pages`() {
        val core = SlidingWindowPagingCache<String>()
        val evicted = mutableListOf<Int>()
        core.evictionListener = CacheEvictionListener { evicted.add(it.page) }

        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)
        core.setState(successPage(5), silently = true)

        core.startContextPage = 2
        core.endContextPage = 3

        // Trigger eviction
        core.setState(successPage(3), silently = true)

        assertTrue(evicted.contains(1))
        assertTrue(evicted.contains(5))
    }

    @Test
    fun `clear works correctly`() {
        val core = SlidingWindowPagingCache<String>()
        core.setState(successPage(1), silently = true)
        core.clear()
        assertEquals(0, core.size)
    }

    @Test
    fun `release works correctly`() {
        val core = SlidingWindowPagingCache<String>()
        core.setState(successPage(1), silently = true)
        core.release(silently = true)
        assertEquals(0, core.size)
    }

    @Test
    fun `margin 0 keeps only context window pages`() {
        val core = SlidingWindowPagingCache<String>(margin = 0)

        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)
        core.setState(successPage(3), silently = true)

        core.startContextPage = 2
        core.endContextPage = 2

        // Trigger
        core.setState(successPage(2, listOf("update")), silently = true)

        assertEquals(1, core.size)
        assertNotNull(core.getStateOf(2))
    }

    @Test
    fun `negative margin throws`() {
        assertFailsWith<IllegalArgumentException> {
            SlidingWindowPagingCache<String>(margin = -1)
        }
    }

    // -- Integration tests with Paginator --

    @Test
    fun `jump evicts pages from previous location`() = runTest {
        val core = SlidingWindowPagingCache<String>()
        val paginator = MutablePaginator(
            core = PagingCore(cache = core, initialCapacity = 3)
        ) { page: Int ->
            SourceResult(List(3) { "p${page}_item$it" })
        }

        // Load pages 1-3
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        assertEquals(3, paginator.cache.size)

        // Jump to page 10 -- context resets to page 10
        paginator.jump(BookmarkInt(10), silentlyLoading = true, silentlyResult = true)

        // Pages 1-3 should be evicted (outside new context window)
        assertNull(paginator.cache.getStateOf(1))
        assertNull(paginator.cache.getStateOf(2))
        assertNull(paginator.cache.getStateOf(3))
        assertNotNull(paginator.cache.getStateOf(10))
    }

    @Test
    fun `goNextPage keeps context window pages`() = runTest {
        val core = SlidingWindowPagingCache<String>()
        val paginator = MutablePaginator(
            core = PagingCore(cache = core, initialCapacity = 3)
        ) { page: Int ->
            SourceResult(List(3) { "p${page}_item$it" })
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        // Context window = 1..3, all pages should be present
        assertNotNull(paginator.cache.getStateOf(1))
        assertNotNull(paginator.cache.getStateOf(2))
        assertNotNull(paginator.cache.getStateOf(3))
    }

    @Test
    fun `margin keeps extra pages after jump`() = runTest {
        val core = SlidingWindowPagingCache<String>(margin = 1)
        val paginator = MutablePaginator(
            core = PagingCore(cache = core, initialCapacity = 3)
        ) { page: Int ->
            SourceResult(List(3) { "p${page}_item$it" })
        }

        paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        // Context = 5..6, margin=1 -> keep = 4..7
        // Pages 5 and 6 are loaded and in context
        assertNotNull(paginator.cache.getStateOf(5))
        assertNotNull(paginator.cache.getStateOf(6))

        // Load a page way outside
        paginator.jump(BookmarkInt(20), silentlyLoading = true, silentlyResult = true)

        // Pages 5 and 6 should be evicted (far outside context 20..20 + margin)
        assertNull(paginator.cache.getStateOf(5))
        assertNull(paginator.cache.getStateOf(6))
        assertNotNull(paginator.cache.getStateOf(20))
    }

    @Test
    fun `release works after sliding window navigation`() = runTest {
        val core = SlidingWindowPagingCache<String>()
        val paginator = MutablePaginator(
            core = PagingCore(cache = core, initialCapacity = 3)
        ) { page: Int ->
            SourceResult(List(3) { "p${page}_item$it" })
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.release()
        assertEquals(0, paginator.cache.size)

        // Can use after release
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        assertNotNull(paginator.cache.getStateOf(1))
    }

    @Test
    fun `serialization roundtrip works`() = runTest {
        val core = SlidingWindowPagingCache<String>()
        val paginator = MutablePaginator(
            core = PagingCore(cache = core, initialCapacity = 3)
        ) { page: Int ->
            SourceResult(List(3) { "p${page}_item$it" })
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        val savedPages = paginator.cache.pages.toList()
        val snapshot = paginator.core.saveState()
        paginator.core.restoreState(snapshot, silently = true)

        assertEquals(savedPages, paginator.cache.pages.toList())
    }

    @Test
    fun `evictionListener called on jump eviction`() = runTest {
        val core = SlidingWindowPagingCache<String>()
        val evicted = mutableListOf<Int>()
        core.evictionListener = CacheEvictionListener { evicted.add(it.page) }

        val paginator = MutablePaginator(
            core = PagingCore(cache = core, initialCapacity = 3)
        ) { page: Int ->
            SourceResult(List(3) { "p${page}_item$it" })
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        assertTrue(evicted.isEmpty())

        // Jump far away
        paginator.jump(BookmarkInt(10), silentlyLoading = true, silentlyResult = true)

        // Pages 1 and 2 should have been evicted
        assertTrue(evicted.contains(1))
        assertTrue(evicted.contains(2))
    }
}
