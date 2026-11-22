package com.jamal_aliev.paginator.cursor.cache.eviction

import com.jamal_aliev.paginator.core.cache.eviction.CacheEvictionListener
import com.jamal_aliev.paginator.core.extension.isEmptyState
import com.jamal_aliev.paginator.core.extension.isProgressState
import com.jamal_aliev.paginator.core.extension.isSuccessState
import com.jamal_aliev.paginator.core.logger.LogComponent
import com.jamal_aliev.paginator.core.logger.debug
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.cache.CursorInMemoryPagingCache
import com.jamal_aliev.paginator.cursor.cache.CursorPagingCache
import com.jamal_aliev.paginator.cursor.extension.withLeaf
import com.jamal_aliev.paginator.cursor.page.CursorPageState

/**
 * A [CursorPagingCache] decorator that keeps a **bounded window of the pages nearest to the page
 * that was just loaded**, and evicts the farthest ones. It is the cursor counterpart of
 * `NearestPagingCache` of the offset module and follows the same rules.
 *
 * Cursor pages have no numbers, so the distance between two pages is the number of steps along the
 * `prev`/`next` links of the cached bookmarks (`walkBackward` / `walkForward`), counted from the
 * page whose load just completed (the *anchor*):
 *
 * ```
 * cached [p0, p1, p2] + p3 loaded  ->  [p1, p2, p3]   // scrolled forward: p0 is farthest
 * cached [p1, p2, p3] + p0 loaded  ->  [p0, p1, p2]   // scrolled backward: p3 is farthest
 * ```
 *
 * The strategy keeps **no state of its own**: the rule only looks at which pages are cached right
 * now. The victim does not depend on when pages were inserted or read, so scrolling in either
 * direction never punches a hole in the middle of the window.
 *
 * ## Which writes evict
 *
 * Only the **completion of a page load** evicts: a Success (with or without data) written over a
 * page whose current state is Progress, and only while the cache holds more than [maxSize] pages.
 * Navigation always writes Progress for a page before it loads it, under the same `self` key as the
 * result. If the source answers with a different `self`, the result is not a write over that
 * Progress, so the load evicts nothing until the next one. Every other write leaves the cache
 * alone. In particular an in-place edit never evicts:
 * when a message is inserted into the first page of a full window and the overflow creates a new
 * page at the end, that new page was not loaded, and evicting the farthest page would throw away
 * the page that was just edited.
 *
 * | Written state                                                         | Evicts down to [maxSize]? |
 * |-----------------------------------------------------------------------|---------------------------|
 * | Success (with or without data) over Progress: a load completes        | yes                       |
 * | Success over anything else: CRUD overflow page, L2 promotion          | no                        |
 * | Progress                                                              | no                        |
 * | Error                                                                 | no                        |
 * | [setStateUntracked] / [peekStateOf]                                   | no                        |
 *
 * A cancelled load that restores the previous Success state over its Progress looks like a
 * completion, but it only evicts if the cache is over [maxSize] at that moment.
 *
 * The cache can therefore temporarily hold more than [maxSize] pages: the window plus one
 * unfinished page on each edge, plus pages created by CRUD overflow. The next completed load trims
 * the excess, including unfinished pages that are far away.
 *
 * ## Victim selection
 *
 * 1. The anchor is never evicted.
 * 2. Candidates are ordered by distance to the anchor, farthest first. Pages that cannot be
 *    reached from the anchor along the cached links (for example islands left behind by a `jump`)
 *    have an infinite distance and go first, among themselves in the order of `cursors`.
 * 3. At equal distance a page **outside** the current context window
 *    (`startContextCursor..endContextCursor`) goes before a page inside it.
 * 4. If the tie remains, the page on the `next` side goes first.
 *
 * Pages are evicted one by one, farthest first, through `removeFromCache` of the inner cache; the
 * [evictionListener] is called exactly once per page that was really removed, in that order.
 *
 * There is deliberately no `protectContextWindow` parameter: the point of this strategy is to trim
 * the window, and protecting all of it would let the cache grow without bound while scrolling. The
 * strategy relies on the paginator core shrinking the context window to the contiguous run of
 * cached pages around the page navigation has just loaded; see `NearestPagingCache` for details.
 *
 * ## Composition
 *
 * The strategy is a [CursorChainablePagingCache] and works with the `+` operator. Inner strategies
 * evict **before** it (an outer decorator calls the inner `setState` first). This strategy
 * tolerates more than [maxSize] pages until a load completes, but `CursorQueuedPagingCache` and
 * `CursorMostRecentPagingCache` evict on **every** write, a Progress page included, wherever they
 * sit in the chain. Another size limit in the chain must therefore leave room for the temporary
 * excess: give it a `maxSize` of at least this strategy's [maxSize] **plus 2** (the window, plus an
 * unfinished page on each edge: a failed or incomplete page on one side and the page being loaded
 * on the other). Navigation alone never needs more. Pages created by CRUD overflow, or a `jump`
 * issued while both edges hold an unfinished page (plus 3), need more. With a smaller limit that
 * strategy picks the victim by its own rule before the load completes, instead of the nearest
 * rule. [CursorTimeLimitedPagingCache] combines freely.
 *
 * ```kotlin
 * val cache = CursorNearestPagingCache<String, Message>(maxSize = 3) +
 *     CursorTimeLimitedPagingCache(ttl = 5.minutes)
 * ```
 *
 * @param cache The inner [CursorPagingCache] to delegate to. Defaults to [CursorInMemoryPagingCache].
 * @param maxSize Maximum number of pages to keep once a page has settled. Must be > 0. The cache
 *   may temporarily hold more while Progress or Error pages are present (see above).
 * @param evictionListener Optional listener notified when a page is evicted.
 */
class CursorNearestPagingCache<K : Any, T>(
    private val cache: CursorPagingCache<K, T> = CursorInMemoryPagingCache<K, T>(),
    val maxSize: Int,
    var evictionListener: CacheEvictionListener<T>? = null,
) : CursorPagingCache<K, T> by cache, CursorChainablePagingCache<K, T> {

    override fun replaceLeaf(newLeaf: CursorPagingCache<K, T>): CursorNearestPagingCache<K, T> =
        CursorNearestPagingCache(
            cache = cache.withLeaf(newLeaf),
            maxSize = maxSize,
            evictionListener = evictionListener,
        )

    init {
        require(maxSize > 0) { "maxSize must be greater than 0, was $maxSize" }
    }

    override fun setState(
        cursor: CursorBookmark<K>,
        state: CursorPageState<K, T>,
        silently: Boolean
    ) {
        // peekStateOf: reading the previous state must not count as a use for inner strategies.
        val completesLoad = cache.peekStateOf(cursor.self).isProgressState()
        cache.setState(cursor, state, silently)
        if (!completesLoad || !(state.isSuccessState() || state.isEmptyState())) return
        if (cache.size <= maxSize) return

        val anchor = cursor.self

        // Steps from the anchor along the links in one direction. A page is reachable only through
        // a chain of cached pages; a cycle back to the anchor or to a visited page ends the walk.
        fun stepsFromAnchor(step: (CursorBookmark<K>) -> CursorBookmark<K>?): Map<K, Int> {
            val steps = HashMap<K, Int>()
            var current = step(cursor)
            var distance = 1
            while (current != null && current.self != anchor && current.self !in steps) {
                steps[current.self] = distance++
                current = step(current)
            }
            return steps
        }

        // Selves of the pages from startContextCursor to endContextCursor, walking forward.
        fun contextWindowSelves(): Set<K> {
            val result = HashSet<K>()
            if (!cache.isStarted) return result
            val end = cache.endContextCursor
            var current = cache.startContextCursor
            while (current != null && result.add(current.self)) {
                if (end != null && current.self == end.self) break
                current = cache.walkForward(current)
            }
            return result
        }

        val unreachable = Int.MAX_VALUE
        val forward = stepsFromAnchor(cache::walkForward)
        val backward = stepsFromAnchor(cache::walkBackward)
        val window = contextWindowSelves()

        fun distanceOf(self: K): Int = minOf(forward[self] ?: unreachable, backward[self] ?: unreachable)

        // Farthest first; `sortedWith` is stable, so unreachable pages keep the order of `cursors`:
        // the window and side keys apply to reachable pages only.
        val victims = cache.cursors.map { it.self }
            .filter { it != anchor }
            .sortedWith(
                compareByDescending<K> { distanceOf(it) }
                    .thenBy { if (distanceOf(it) != unreachable && it in window) 1 else 0 }
                    .thenBy { if ((forward[it] ?: unreachable) > (backward[it] ?: unreachable)) 1 else 0 }
            )

        for (self in victims) {
            if (cache.size <= maxSize) break
            val evicted = cache.removeFromCache(self)
            if (evicted != null) {
                cache.logger.debug(LogComponent.CACHE) { "CursorNearestPagingCache evict: self=$self" }
                evictionListener?.onEvicted(evicted)
            }
        }
    }
}
