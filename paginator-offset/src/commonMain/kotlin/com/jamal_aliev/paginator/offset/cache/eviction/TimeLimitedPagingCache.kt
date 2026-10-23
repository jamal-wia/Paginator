package com.jamal_aliev.paginator.offset.cache.eviction

import com.jamal_aliev.paginator.core.cache.eviction.CacheEvictionListener
import com.jamal_aliev.paginator.core.logger.LogComponent
import com.jamal_aliev.paginator.core.logger.debug
import com.jamal_aliev.paginator.offset.cache.InMemoryPagingCache
import com.jamal_aliev.paginator.offset.cache.PagingCache
import com.jamal_aliev.paginator.offset.extension.withLeaf
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * A [PagingCache] decorator that enforces a **TTL (Time To Live)** eviction policy.
 *
 * Each page has a timestamp recorded when it is first added to the cache.
 * When a new page is added (via [setState]), any pages whose age exceeds [ttl]
 * are evicted.
 *
 * Pages within the current context window can optionally be protected from eviction
 * via [protectContextWindow].
 *
 * ## Usage
 * ```kotlin
 * val paginator = MutablePaginator(
 *     pagingCore = PagingCore(
 *         cache = TimeLimitedPagingCache(ttl = 5.minutes),
 *         initialCapacity = 20
 *     ),
 *     load = { page -> api.loadPage(page) }
 * )
 * ```
 *
 * @param cache The inner [PagingCache] to delegate to. Defaults to [InMemoryPagingCache].
 * @param ttl Maximum time a page can remain in cache before being eligible for eviction.
 * @param refreshOnAccess If `true`, reading a page via [getStateOf] or [getElement]
 *   resets its TTL timer. Default is `false`.
 * @param protectContextWindow If `true` (default), pages within the visible context window
 *   are never evicted, even if expired.
 * @param timeSource Time source for timestamps. Override with [TimeSource.Monotonic]
 *   or a test implementation for deterministic testing.
 * @param evictionListener Optional listener notified when a page is evicted.
 */
class TimeLimitedPagingCache<T>(
    private val cache: PagingCache<T> = InMemoryPagingCache<T>(),
    val ttl: Duration,
    val refreshOnAccess: Boolean = false,
    val protectContextWindow: Boolean = true,
    val timeSource: TimeSource = TimeSource.Monotonic,
    var evictionListener: CacheEvictionListener<T>? = null,
) : PagingCache<T> by cache, ChainablePagingCache<T> {

    override fun replaceLeaf(newLeaf: PagingCache<T>): TimeLimitedPagingCache<T> =
        TimeLimitedPagingCache(
            cache = cache.withLeaf(newLeaf), ttl = ttl, refreshOnAccess = refreshOnAccess,
            protectContextWindow = protectContextWindow, timeSource = timeSource,
            evictionListener = evictionListener
        )

    init {
        require(ttl.isPositive()) { "ttl must be positive, was $ttl" }
    }

    /** Tracks when each page was last added (or accessed, if [refreshOnAccess]). */
    // Kept in the order the marks were taken — oldest first — so the expiry pass can stop at the
    // first page that is still fresh instead of checking every page on every write.
    private val timestamps = LinkedHashMap<Int, TimeMark>()

    /** Restarts [page]'s timer, moving it to the fresh end of [timestamps]. */
    private fun markNow(page: Int) {
        timestamps.remove(page)
        timestamps[page] = timeSource.markNow()
    }

    override fun setState(state: OffsetPageState<T>, silently: Boolean) {
        cache.setState(state, silently)
        markNow(state.page)
        performEviction()
    }

    override fun getStateOf(page: Int): OffsetPageState<T>? {
        val result = cache.getStateOf(page)
        if (result != null && refreshOnAccess) {
            markNow(page)
        }
        return result
    }

    /** Replaces an already-cached page without restarting its TTL timer. */
    override fun setStateUntracked(state: OffsetPageState<T>, silently: Boolean) {
        cache.setStateUntracked(state, silently)
    }

    /** Reads a page without restarting its TTL timer, even with [refreshOnAccess]. */
    override fun peekStateOf(page: Int): OffsetPageState<T>? = cache.peekStateOf(page)

    override fun getElement(page: Int, index: Int): T? {
        val result = cache.getElement(page, index)
        if (result != null && refreshOnAccess) {
            markNow(page)
        }
        return result
    }

    override fun removeFromCache(page: Int): OffsetPageState<T>? {
        val result = cache.removeFromCache(page)
        if (result != null) timestamps.remove(page)
        return result
    }

    override fun clear() {
        cache.clear()
        timestamps.clear()
    }

    override fun release(capacity: Int, silently: Boolean) {
        cache.release(capacity, silently)
        timestamps.clear()
    }

    /**
     * Evicts all pages whose age exceeds [ttl].
     * Pages in the context window are skipped when [protectContextWindow] is `true`.
     */
    private fun performEviction() {
        val protectedRange = if (protectContextWindow && cache.isStarted)
            cache.startContextPage..cache.endContextPage else null

        // Marks are monotonic and kept oldest first: everything after the first fresh page is
        // fresh too. Expired pages that are protected (or no longer cached) are skipped, not stops.
        val expired = mutableListOf<Int>()
        for ((page, mark) in timestamps) {
            if (mark.elapsedNow() <= ttl) break
            if (cache.peekStateOf(page) != null && (protectedRange == null || page !in protectedRange)) {
                expired += page
            }
        }

        for (page in expired) {
            val evicted = cache.removeFromCache(page)
            timestamps.remove(page)
            if (evicted != null) {
                cache.logger.debug(LogComponent.CACHE) { "TimeLimitedPagingCache evict: page=${evicted.page} (ttl expired)" }
                evictionListener?.onEvicted(evicted)
            }
        }
    }
}
