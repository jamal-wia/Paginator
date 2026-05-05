package com.jamal_aliev.paginator.cache.eviction

import com.jamal_aliev.paginator.page.PageState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class TimeLimitedPagingCacheTest {

    private data class CorePair<T>(
        val core: TimeLimitedPagingCache<T>,
        val timeSource: TestTimeSource,
    )

    private fun createCore(
        ttlMs: Long = 1000,
        refreshOnAccess: Boolean = false,
        protectContextWindow: Boolean = true,
        timeSource: TestTimeSource = TestTimeSource(),
    ): CorePair<String> {
        val core = TimeLimitedPagingCache<String>(
            ttl = ttlMs.milliseconds,
            refreshOnAccess = refreshOnAccess,
            protectContextWindow = protectContextWindow,
            timeSource = timeSource,
        )
        return CorePair(core, timeSource)
    }

    private fun successPage(
        page: Int,
        data: List<String> = listOf("item_$page")
    ): PageState.SuccessPage<String> {
        return PageState.SuccessPage(page = page, data = data)
    }

    // -- 1. Pages evicted after TTL --

    @Test
    fun `pages are evicted after TTL expires`() {
        val ts = TestTimeSource()
        val (core, _) = createCore(ttlMs = 1000, timeSource = ts)

        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)
        assertEquals(2, core.size)

        // Advance time past TTL
        ts += 1001.milliseconds

        // Trigger eviction by adding another page
        core.setState(successPage(3), silently = true)

        // Pages 1 and 2 should be evicted (expired)
        assertNull(core.getStateOf(1))
        assertNull(core.getStateOf(2))
        assertNotNull(core.getStateOf(3))
    }

    // -- 2. Pages within TTL not evicted --

    @Test
    fun `pages within TTL are not evicted`() {
        val ts = TestTimeSource()
        val (core, _) = createCore(ttlMs = 5000, timeSource = ts)

        core.setState(successPage(1), silently = true)
        ts += 100.milliseconds
        core.setState(successPage(2), silently = true)
        ts += 100.milliseconds

        // Add another page -- nothing expired yet
        core.setState(successPage(3), silently = true)
        assertEquals(3, core.size)
    }

    // -- 3. refreshOnAccess resets timer via getStateOf --

    @Test
    fun `refreshOnAccess true resets TTL on getStateOf`() {
        val ts = TestTimeSource()
        val (core, _) = createCore(ttlMs = 1000, refreshOnAccess = true, timeSource = ts)

        core.setState(successPage(1), silently = true)
        core.setState(successPage(2), silently = true)

        // Advance 800ms -- not yet expired
        ts += 800.milliseconds

        // Access page 1 -- resets its TTL
        core.getStateOf(1)

        // Advance another 300ms -- total 1100ms for page 2, but only 300ms for page 1
        ts += 300.milliseconds

        // Trigger eviction
        core.setState(successPage(3), silently = true)

        // Page 2 should be evicted (1100ms > 1000ms TTL)
        // Page 1 should survive (300ms < 1000ms TTL, refreshed by access)
        assertNotNull(core.getStateOf(1))
        assertNull(core.getStateOf(2))
    }

    // -- 4. refreshOnAccess false does NOT reset timer --

    @Test
    fun `refreshOnAccess false does not reset TTL on getStateOf`() {
        val ts = TestTimeSource()
        val (core, _) = createCore(ttlMs = 1000, refreshOnAccess = false, timeSource = ts)

        core.setState(successPage(1), silently = true)

        ts += 800.milliseconds
        core.getStateOf(1) // should NOT refresh TTL

        ts += 300.milliseconds // total 1100ms > TTL

        // Trigger eviction
        core.setState(successPage(2), silently = true)

        // Page 1 should be evicted (1100ms > TTL, not refreshed)
        assertNull(core.getStateOf(1))
    }

    // -- 5. Context window protection --

    @Test
    fun `protectContextWindow true prevents eviction of context pages`() {
        val ts = TestTimeSource()
        val (core, _) = createCore(ttlMs = 1000, protectContextWindow = true, timeSource = ts)

        core.setState(successPage(1, List(5) { "i$it" }), silently = true)
        core.setState(successPage(2, List(5) { "i$it" }), silently = true)
        core.startContextPage = 1
        core.endContextPage = 2

        ts += 2000.milliseconds

        core.setState(successPage(3), silently = true)

        // Pages 1 and 2 are expired but protected
        assertNotNull(core.getStateOf(1))
        assertNotNull(core.getStateOf(2))
    }

    @Test
    fun `protectContextWindow false allows eviction of expired context pages`() {
        val ts = TestTimeSource()
        val (core, _) = createCore(ttlMs = 1000, protectContextWindow = false, timeSource = ts)

        core.setState(successPage(1, List(5) { "i$it" }), silently = true)
        core.startContextPage = 1
        core.endContextPage = 1

        ts += 2000.milliseconds

        core.setState(successPage(2), silently = true)

        assertNull(core.getStateOf(1))
    }

    // -- 6. evictionListener works --

    @Test
    fun `evictionListener is called with expired page`() {
        val ts = TestTimeSource()
        val (core, _) = createCore(ttlMs = 500, timeSource = ts)
        val evicted = mutableListOf<Int>()
        core.evictionListener = CacheEvictionListener { evicted.add(it.page) }

        core.setState(successPage(1), silently = true)
        ts += 600.milliseconds
        core.setState(successPage(2), silently = true)

        assertEquals(listOf(1), evicted)
    }

    // -- 7. release() clears timestamps --

    @Test
    fun `release clears all TTL tracking`() {
        val ts = TestTimeSource()
        val (core, _) = createCore(ttlMs = 1000, timeSource = ts)

        core.setState(successPage(1), silently = true)
        ts += 500.milliseconds
        core.release(silently = true)

        // After release, new pages get fresh timestamps
        core.setState(successPage(2), silently = true)
        ts += 600.milliseconds // total 1100ms from page 1, but only 600ms from page 2

        core.setState(successPage(3), silently = true)
        // Page 2 should NOT be evicted (600ms < 1000ms TTL)
        assertNotNull(core.getStateOf(2))
    }

    // -- 8. clear() clears timestamps --

    @Test
    fun `clear clears all TTL tracking`() {
        val ts = TestTimeSource()
        val (core, _) = createCore(ttlMs = 1000, timeSource = ts)

        core.setState(successPage(1), silently = true)
        core.clear()
        assertEquals(0, core.size)

        core.setState(successPage(2), silently = true)
        ts += 500.milliseconds
        core.setState(successPage(3), silently = true)

        // Both should be present (500ms < 1000ms)
        assertEquals(2, core.size)
    }

    // -- 9. TestTimeSource for deterministic testing --

    @Test
    fun `custom TimeSource allows deterministic testing`() {
        val ts = TestTimeSource()
        val (core, _) = createCore(ttlMs = 2000, timeSource = ts)

        core.setState(successPage(1), silently = true)

        // Advance exactly to TTL boundary
        ts += 2000.milliseconds
        core.setState(successPage(2), silently = true)

        // Page 1 should NOT be evicted (elapsed == ttl, not > ttl)
        assertNotNull(core.getStateOf(1))

        // Advance 1ms past TTL
        ts += 1.milliseconds
        core.setState(successPage(3), silently = true)

        // Now page 1 should be evicted
        assertNull(core.getStateOf(1))
    }

    // -- 10. All expired but all in protected range --

    @Test
    fun `all expired pages in protected range are not evicted`() {
        val ts = TestTimeSource()
        val (core, _) = createCore(ttlMs = 500, protectContextWindow = true, timeSource = ts)

        core.setState(successPage(1, List(5) { "i$it" }), silently = true)
        core.setState(successPage(2, List(5) { "i$it" }), silently = true)
        core.startContextPage = 1
        core.endContextPage = 2

        ts += 1000.milliseconds

        core.setState(successPage(3, List(5) { "i$it" }), silently = true)

        // Pages 1 and 2 expired but protected, page 3 just added
        assertEquals(3, core.size)
    }

    // -- 11. TTL validation --

    @Test
    fun `negative TTL throws`() {
        assertFailsWith<IllegalArgumentException> {
            TimeLimitedPagingCache<String>(ttl = (-1).seconds)
        }
    }

    // -- 12. removeFromCache clears timestamp --

    @Test
    fun `removeFromCache removes timestamp`() {
        val ts = TestTimeSource()
        val (core, _) = createCore(ttlMs = 1000, timeSource = ts)

        core.setState(successPage(1), silently = true)
        core.removeFromCache(1)

        // Re-add page 1 -- should get fresh timestamp
        ts += 500.milliseconds
        core.setState(successPage(1), silently = true)

        ts += 600.milliseconds // 600ms from re-add, would be 1100ms from original

        core.setState(successPage(2), silently = true)
        // Page 1 should NOT be evicted (600ms < 1000ms)
        assertNotNull(core.getStateOf(1))
    }

    // -- 13. setState refreshes TTL for existing page --

    @Test
    fun `setState on existing page refreshes its TTL`() {
        val ts = TestTimeSource()
        val (core, _) = createCore(ttlMs = 1000, timeSource = ts)

        core.setState(successPage(1), silently = true)
        ts += 800.milliseconds

        // Update page 1 -- should reset TTL
        core.setState(successPage(1, listOf("updated")), silently = true)

        ts += 300.milliseconds // 300ms from update, would be 1100ms from original

        core.setState(successPage(2), silently = true)
        // Page 1 should NOT be evicted (refreshed by setState)
        assertNotNull(core.getStateOf(1))
    }

    // -- 14. refreshOnAccess with getElement --

    @Test
    fun `refreshOnAccess true resets TTL on getElement`() {
        val ts = TestTimeSource()
        val (core, _) = createCore(ttlMs = 1000, refreshOnAccess = true, timeSource = ts)

        core.setState(successPage(1, listOf("a", "b")), silently = true)
        ts += 800.milliseconds

        core.getElement(1, 0) // should refresh TTL

        ts += 300.milliseconds

        core.setState(successPage(2), silently = true)
        // Page 1 should survive (300ms < 1000ms after refresh)
        assertNotNull(core.getStateOf(1))
    }

    // -- 15. Multiple pages expire at different times --

    @Test
    fun `pages added at different times expire independently`() {
        val ts = TestTimeSource()
        val (core, _) = createCore(ttlMs = 1000, timeSource = ts)

        core.setState(successPage(1), silently = true)
        ts += 500.milliseconds
        core.setState(successPage(2), silently = true)
        ts += 600.milliseconds // 1100ms for page 1, 600ms for page 2

        core.setState(successPage(3), silently = true)

        // Page 1 expired, page 2 still valid
        assertNull(core.getStateOf(1))
        assertNotNull(core.getStateOf(2))
        assertNotNull(core.getStateOf(3))
    }
}
