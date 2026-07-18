package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.cache.InMemoryPagingCache
import com.jamal_aliev.paginator.offset.cache.PagingCache
import com.jamal_aliev.paginator.offset.load.LoadResult
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Counts the cache traffic a single operation produces. */
private class CountingPagingCache<T>(
    private val inner: PagingCache<T> = InMemoryPagingCache(),
) : PagingCache<T> by inner {

    var pagesReads = 0
    var getStateOfCalls = 0

    override val pages: List<Int>
        get() {
            pagesReads++
            return inner.pages
        }

    override fun getStateOf(page: Int): OffsetPageState<T>? {
        getStateOfCalls++
        return inner.getStateOf(page)
    }

    fun reset() {
        pagesReads = 0
        getStateOfCalls = 0
    }
}

/**
 * `removeState` must not walk the cache more than it has to.
 *
 * `PagingCache.pages` materialises a fresh `List<Int>` on every access, so repeated reads are
 * repeated allocations on a CRUD path. `getStateOf` is worse than an allocation: on the
 * access-ordered eviction decorators it is a *write* — `MostRecentPagingCache` re-orders its
 * LRU and `TimeLimitedPagingCache` refreshes its TTL — so probing the cache through it silently
 * corrupts eviction order.
 */
class RemoveStateCacheTrafficTest {

    private suspend fun paginatorOf(pageCount: Int): Pair<MutablePaginator<String>, CountingPagingCache<String>> {
        val counting = CountingPagingCache<String>()
        val paginator = MutablePaginator(core = PagingCore(cache = counting)) { page: Int ->
            LoadResult(MutableList(core.capacity) { "p${page}_$it" })
        }
        paginator.core.resize(capacity = 3, resize = false, silently = true)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        repeat(pageCount - 1) {
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        }
        counting.reset()
        return paginator to counting
    }

    @Test
    fun `removeState reads the page list once and never probes through getStateOf`() = runTest {
        val (paginator, counting) = paginatorOf(pageCount = 40)

        paginator.removeState(pageToRemove = 5, silently = true)

        assertEquals(1, counting.pagesReads, "cache.pages should be read exactly once")
        assertEquals(0, counting.getStateOfCalls, "getStateOf mutates LRU/TTL state; use removeFromCache")
    }

    @Test
    fun `removing an uncached page touches nothing`() = runTest {
        val (paginator, counting) = paginatorOf(pageCount = 5)

        paginator.removeState(pageToRemove = 99, silently = true)

        assertEquals(1, counting.pagesReads)
        assertEquals(0, counting.getStateOfCalls)
        assertEquals(listOf(1, 2, 3, 4, 5), paginator.cache.pages)
    }

    @Test
    fun `removing the topmost page does no shifting work`() = runTest {
        val (paginator, counting) = paginatorOf(pageCount = 5)

        paginator.removeState(pageToRemove = 5, silently = true)

        assertEquals(1, counting.pagesReads)
        assertEquals(0, counting.getStateOfCalls)
        assertEquals(listOf(1, 2, 3, 4), paginator.cache.pages)
    }
}
