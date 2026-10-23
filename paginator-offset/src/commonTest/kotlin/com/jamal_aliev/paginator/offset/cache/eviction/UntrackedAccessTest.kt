package com.jamal_aliev.paginator.offset.cache.eviction

import com.jamal_aliev.paginator.offset.cache.InMemoryPagingCache
import com.jamal_aliev.paginator.offset.cache.PagingCache
import com.jamal_aliev.paginator.offset.extension.plus
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * `setStateUntracked` / `peekStateOf` write and read a page without counting it as a use, so
 * background updates (live database queries) do not reshape LRU order or TTL timers.
 */
class UntrackedAccessTest {

    private fun page(page: Int, item: String = "item_$page") =
        OffsetPageState.Success(page = page, data = mutableListOf(item))

    @Test
    fun `MostRecent - an untracked write keeps the page's recency`() {
        val cache = MostRecentPagingCache<String>(maxSize = 3, protectContextWindow = false)
        cache.setState(page(1), silently = true)
        cache.setState(page(2), silently = true)
        cache.setState(page(3), silently = true)

        cache.setStateUntracked(page(1, "updated"), silently = true)
        cache.setState(page(4), silently = true)

        // Page 1 is still the least recently used one, so it is the one evicted.
        assertEquals(listOf(2, 3, 4), cache.pages)
    }

    @Test
    fun `MostRecent - a peek keeps the page's recency while a read refreshes it`() {
        val cache = MostRecentPagingCache<String>(maxSize = 3, protectContextWindow = false)
        cache.setState(page(1), silently = true)
        cache.setState(page(2), silently = true)
        cache.setState(page(3), silently = true)

        assertNotNull(cache.peekStateOf(1))
        cache.setState(page(4), silently = true)
        assertEquals(listOf(2, 3, 4), cache.pages)

        assertNotNull(cache.getStateOf(2))
        cache.setState(page(5), silently = true)
        assertEquals(listOf(2, 4, 5), cache.pages, "the tracked read made page 2 recent")
    }

    @Test
    fun `TimeLimited - an untracked write keeps the TTL timer`() {
        val time = TestTimeSource()
        val cache = TimeLimitedPagingCache<String>(ttl = 10.seconds, protectContextWindow = false, timeSource = time)
        cache.setState(page(1), silently = true)
        time += 11.seconds

        cache.setStateUntracked(page(1, "updated"), silently = true)
        assertEquals("updated", cache.peekStateOf(1)!!.data.single())
        cache.setState(page(2), silently = true) // runs the expiry pass

        assertNull(cache.peekStateOf(1), "page 1 expired: the background write did not refresh it")
    }

    @Test
    fun `TimeLimited - a peek does not refresh the timer even with refreshOnAccess`() {
        val time = TestTimeSource()
        val cache = TimeLimitedPagingCache<String>(
            ttl = 10.seconds, refreshOnAccess = true, protectContextWindow = false, timeSource = time,
        )
        cache.setState(page(1), silently = true)
        time += 6.seconds
        cache.peekStateOf(1)
        time += 5.seconds

        cache.setState(page(2), silently = true)
        assertNull(cache.peekStateOf(1))
    }

    @Test
    fun `strategies delegating with by forward untracked access down the chain`() {
        val lru = MostRecentPagingCache<String>(maxSize = 3, protectContextWindow = false)
        val chain = QueuedPagingCache<String>(maxSize = 10, protectContextWindow = false) + lru
        chain.setState(page(1), silently = true)
        chain.setState(page(2), silently = true)
        chain.setState(page(3), silently = true)

        chain.setStateUntracked(page(1, "updated"), silently = true)
        chain.peekStateOf(1)
        chain.setState(page(4), silently = true)

        assertEquals(listOf(2, 3, 4), chain.pages, "the LRU below the queue never saw a use of page 1")
    }

    @Test
    fun `the default untracked access of a custom cache is a plain write and read`() {
        // A cache implemented from scratch (no `by` delegation) inherits the interface defaults.
        val writes = mutableListOf<Int>()
        val plain = object : PagingCache<String> {
            private val inner = InMemoryPagingCache<String>()
            override var logger get() = inner.logger; set(value) { inner.logger = value }
            override val size get() = inner.size
            override val pages get() = inner.pages
            override val isStarted get() = inner.isStarted
            override var startContextPage get() = inner.startContextPage; set(value) { inner.startContextPage = value }
            override var endContextPage get() = inner.endContextPage; set(value) { inner.endContextPage = value }
            override fun setState(state: OffsetPageState<String>, silently: Boolean) {
                writes += state.page
                inner.setState(state, silently)
            }
            override fun getStateOf(page: Int) = inner.getStateOf(page)
            override fun getElement(page: Int, index: Int) = inner.getElement(page, index)
            override fun removeFromCache(page: Int) = inner.removeFromCache(page)
            override fun clear() = inner.clear()
            override fun release(capacity: Int, silently: Boolean) = inner.release(capacity, silently)
        }

        plain.setStateUntracked(page(7), silently = true)

        assertEquals(listOf(7), writes)
        assertEquals("item_7", plain.peekStateOf(7)!!.data.single())
    }
}
