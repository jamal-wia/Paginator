package com.jamal_aliev.paginator.strategy

import com.jamal_aliev.paginator.page.PageState
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
 *         cache = TtlPagingCache(ttl = 5.minutes),
 *         initialCapacity = 20
 *     ),
 *     source = { page -> api.loadPage(page) }
 * )
 * ```
 *
 * @param cache The inner [PagingCache] to delegate to. Defaults to [DefaultPagingCache].
 * @param ttl Maximum time a page can remain in cache before being eligible for eviction.
 * @param refreshOnAccess If `true`, reading a page via [getStateOf] or [getElement]
 *   resets its TTL timer. Default is `false`.
 * @param protectContextWindow If `true` (default), pages within the visible context window
 *   are never evicted, even if expired.
 * @param timeSource Time source for timestamps. Override with [TimeSource.Monotonic]
 *   or a test implementation for deterministic testing.
 * @param evictionListener Optional listener notified when a page is evicted.
 */
class TtlPagingCache<T>(
    private val cache: PagingCache<T> = DefaultPagingCache(),
    val ttl: Duration,
    val refreshOnAccess: Boolean = false,
    val protectContextWindow: Boolean = true,
    val timeSource: TimeSource = TimeSource.Monotonic,
    var evictionListener: CacheEvictionListener<T>? = null,
) : PagingCache<T> by cache, WrappablePagingCache<T> {

    override val wrapped: PagingCache<T> get() = cache

    override fun wrap(inner: PagingCache<T>): TtlPagingCache<T> =
        TtlPagingCache(cache = inner, ttl = ttl, refreshOnAccess = refreshOnAccess,
            protectContextWindow = protectContextWindow, timeSource = timeSource,
            evictionListener = evictionListener)

    init {
        require(ttl.isPositive()) { "ttl must be positive, was $ttl" }
    }

    /** Tracks when each page was last added (or accessed, if [refreshOnAccess]). */
    private val timestamps = hashMapOf<Int, TimeMark>()

    override fun setState(state: PageState<T>, silently: Boolean) {
        cache.setState(state, silently)
        timestamps[state.page] = timeSource.markNow()
        performEviction()
    }

    override fun getStateOf(page: Int): PageState<T>? {
        val result = cache.getStateOf(page)
        if (result != null && refreshOnAccess) {
            timestamps[page] = timeSource.markNow()
        }
        return result
    }

    override fun getElement(page: Int, index: Int): T? {
        val result = cache.getElement(page, index)
        if (result != null && refreshOnAccess) {
            timestamps[page] = timeSource.markNow()
        }
        return result
    }

    override fun removeFromCache(page: Int): PageState<T>? {
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

        val expired = timestamps.entries
            .filter { (_, mark) -> mark.elapsedNow() > ttl }
            .map { (page, _) -> page }
            .filter { page ->
                cache.getStateOf(page) != null &&
                    (protectedRange == null || page !in protectedRange)
            }

        for (page in expired) {
            val evicted = cache.removeFromCache(page)
            timestamps.remove(page)
            if (evicted != null) {
                cache.logger?.log("TtlPagingCore", "evict: page=${evicted.page} (ttl expired)")
                evictionListener?.onEvicted(evicted)
            }
        }
    }
}
