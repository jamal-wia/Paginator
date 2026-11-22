package com.jamal_aliev.paginator.offset.cache.eviction

import com.jamal_aliev.paginator.core.cache.eviction.CacheEvictionListener
import com.jamal_aliev.paginator.core.extension.isEmptyState
import com.jamal_aliev.paginator.core.extension.isSuccessState
import com.jamal_aliev.paginator.core.logger.LogComponent
import com.jamal_aliev.paginator.core.logger.debug
import com.jamal_aliev.paginator.offset.cache.InMemoryPagingCache
import com.jamal_aliev.paginator.offset.cache.PagingCache
import com.jamal_aliev.paginator.offset.extension.withLeaf
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlin.math.abs

/**
 * A [PagingCache] decorator that keeps a **bounded window of the pages nearest to the page that was
 * just loaded**, and evicts the farthest ones.
 *
 * When a page settles and the number of cached pages exceeds [maxSize], the page that was just
 * written (the *anchor*) is kept and the pages farthest from it (largest `abs(page - anchor)`) are
 * evicted first, until the cache fits [maxSize] again. This suits bidirectional scrolling over a
 * fixed-size window, for example a chat history that keeps three pages around the user:
 *
 * ```
 * cached [1, 2, 3] + page 4 settles  ->  [2, 3, 4]   // scrolled forward: page 1 is farthest
 * cached [2, 3, 4] + page 1 settles  ->  [1, 2, 3]   // scrolled backward: page 4 is farthest
 * ```
 *
 * Unlike [QueuedPagingCache] (oldest insertion) and [MostRecentPagingCache] (least recently used),
 * the victim does not depend on when pages were inserted or read, so scrolling in either direction
 * never punches a hole in the middle of the window. The strategy keeps **no state of its own**:
 * the eviction rule only looks at which pages are cached right now.
 *
 * ## Which writes evict
 *
 * Only a settled page evicts. Writes that are not a settled page of data leave the cache alone, so
 * a page that is about to be loaded is never evicted in advance and a failed load does not cost the
 * window its neighbours:
 *
 * | Written state                                              | Evicts down to [maxSize]? |
 * |------------------------------------------------------------|---------------------------|
 * | Success with data (including an incomplete last page)      | yes                       |
 * | Success without data (empty page, end of the feed)         | yes                       |
 * | Progress                                                   | no                        |
 * | Error                                                      | no                        |
 * | Replacing a page that is already cached (`size` unchanged) | nothing to evict          |
 * | [setStateUntracked] / [peekStateOf]                        | no (see below)            |
 *
 * Because Progress and Error pages do not evict, the cache can temporarily hold more than
 * [maxSize] pages: the window plus one unfinished page on each edge. The next settled page trims
 * the excess, including unfinished pages that are far away.
 *
 * ## Victim selection
 *
 * 1. The anchor is never evicted.
 * 2. Candidates are ordered by distance to the anchor, farthest first.
 * 3. At equal distance a page **outside** the current context window
 *    (`startContextPage..endContextPage`) goes before a page inside it.
 * 4. If the tie remains, the page on the "forward" side (the higher page number) goes first.
 *
 * Pages are evicted one by one, farthest first, through `removeFromCache` of the inner cache; the
 * [evictionListener] is called exactly once per page that was really removed, in that order.
 *
 * ## No `protectContextWindow`
 *
 * Unlike [QueuedPagingCache] and [MostRecentPagingCache] there is deliberately no
 * `protectContextWindow` parameter. The point of this strategy is to trim the window: protecting
 * the whole window would leave nothing to evict while scrolling forward and the cache would grow
 * without bound. The pages closest to the anchor are kept by the victim rule itself.
 *
 * The strategy may evict pages that the context window still covers. It relies on the paginator
 * core shrinking the window to the contiguous run of cached pages around the page navigation has
 * just loaded, so that the published snapshot has no hole. Without that, the window can be left
 * pointing at an evicted page and the snapshot comes out empty.
 *
 * ## Untracked access
 *
 * [setStateUntracked] and [peekStateOf] are delegated to the inner cache and never evict, like in
 * the other strategies. A background update of a cached page (for example a live query re-emitting)
 * therefore cannot push the window around.
 *
 * ## Usage
 *
 * ```kotlin
 * // Chat history with live pages: a window of three pages, scrolling both ways.
 * // A page that leaves the window also leaves the cache, and its live subscription ends.
 * val paginator = mutablePaginator<ChatMessage>(capacity = 20) {
 *     cache = NearestPagingCache(maxSize = 3)
 *     livePages(scope) { page -> observePage(page) }
 * }
 * ```
 *
 * ## Composition with other strategies
 *
 * The strategy is a [ChainablePagingCache] and works with the `+` operator. An outer decorator
 * calls the `setState` of the inner one first, so the eviction of **inner strategies happens
 * before** this one. Other size-limiting strategies in the same chain (`QueuedPagingCache`,
 * `MostRecentPagingCache`) must therefore have a `maxSize` **greater than or equal to** this
 * strategy's [maxSize]; otherwise they pick the victim instead of the nearest rule.
 * [TimeLimitedPagingCache] combines freely.
 *
 * ```kotlin
 * val cache = NearestPagingCache<Item>(maxSize = 3) + TimeLimitedPagingCache(ttl = 5.minutes)
 * ```
 *
 * @param cache The inner [PagingCache] to delegate to. Defaults to [InMemoryPagingCache].
 * @param maxSize Maximum number of pages to keep once a page has settled. Must be > 0. The cache
 *   may temporarily hold more while Progress or Error pages are present (see above).
 * @param evictionListener Optional listener notified when a page is evicted.
 */
class NearestPagingCache<T>(
    private val cache: PagingCache<T> = InMemoryPagingCache<T>(),
    val maxSize: Int,
    var evictionListener: CacheEvictionListener<T>? = null,
) : PagingCache<T> by cache, ChainablePagingCache<T> {

    override fun replaceLeaf(newLeaf: PagingCache<T>): NearestPagingCache<T> =
        NearestPagingCache(
            cache = cache.withLeaf(newLeaf), maxSize = maxSize,
            evictionListener = evictionListener
        )

    init {
        require(maxSize > 0) { "maxSize must be greater than 0, was $maxSize" }
    }

    override fun setState(state: OffsetPageState<T>, silently: Boolean) {
        cache.setState(state, silently)
        if (!(state.isSuccessState() || state.isEmptyState())) return
        if (cache.size <= maxSize) return

        val anchor = state.page

        // Pages other than the anchor, in eviction order: farthest first; at equal distance outside
        // the context window before inside it, then the higher page first.
        fun victimsFarthestFirst(): List<Int> {
            val window = if (cache.isStarted) cache.startContextPage..cache.endContextPage else null
            return cache.pages
                .filter { it != anchor }
                .sortedWith(
                    compareByDescending<Int> { abs(it - anchor) }
                        .thenBy { if (window != null && it in window) 1 else 0 }
                        .thenByDescending { it }
                )
        }

        for (page in victimsFarthestFirst()) {
            if (cache.size <= maxSize) break
            val evicted = cache.removeFromCache(page)
            if (evicted != null) {
                cache.logger.debug(LogComponent.CACHE) { "NearestPagingCache evict: page=${evicted.page}" }
                evictionListener?.onEvicted(evicted)
            }
        }
    }
}
