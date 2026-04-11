package com.jamal_aliev.paginator.strategy

import com.jamal_aliev.paginator.page.PageState

/**
 * A [PagingCache] decorator that keeps **only** pages within the current context window.
 *
 * Every time a page is added via [setState], any pages outside the range
 * `startContextPage..endContextPage` are immediately evicted. This is ideal
 * for memory-constrained environments where you only need the currently
 * visible pages — after a [jump][com.jamal_aliev.paginator.Paginator.jump],
 * all pages from the previous location are discarded.
 *
 * Optionally, a [margin] can be set to retain pages slightly outside the
 * context window (e.g., `margin = 1` keeps one page before and after).
 *
 * ## Usage
 * ```kotlin
 * val paginator = MutablePaginator(
 *     pagingCore = PagingCore(
 *         cache = SlidingWindowPagingCache(margin = 1),
 *         initialCapacity = 20
 *     ),
 *     source = { page -> api.loadPage(page) }
 * )
 * ```
 *
 * @param cache The inner [PagingCache] to delegate to. Defaults to [DefaultPagingCache].
 * @param margin Number of pages to keep beyond each edge of the context window.
 *   Default is `0` (strict window). For example, `margin = 2` keeps pages in
 *   `(startContextPage - 2)..(endContextPage + 2)`.
 * @param evictionListener Optional listener notified when a page is evicted.
 */
class SlidingWindowPagingCache<T>(
    private val cache: PagingCache<T> = DefaultPagingCache(),
    val margin: Int = 0,
    var evictionListener: CacheEvictionListener<T>? = null,
) : PagingCache<T> by cache, WrappablePagingCache<T> {

    override fun replaceLeaf(newLeaf: PagingCache<T>): SlidingWindowPagingCache<T> =
        SlidingWindowPagingCache(cache = cache.withLeaf(newLeaf), margin = margin, evictionListener = evictionListener)

    init {
        require(margin >= 0) { "margin must be >= 0, was $margin" }
    }

    override fun setState(state: PageState<T>, silently: Boolean) {
        cache.setState(state, silently)
        performEviction(justAddedPage = state.page)
    }

    /**
     * Evicts all pages outside the context window (with [margin]).
     * Only runs when the paginator has started (context window is set).
     *
     * @param justAddedPage The page that was just added — never evicted in the same call
     *   to avoid evicting a page before the context window has expanded to include it.
     */
    private fun performEviction(justAddedPage: Int = -1) {
        if (!cache.isStarted) return

        val keepRange = (cache.startContextPage - margin)..(cache.endContextPage + margin)

        // Collect pages to evict (iterate over a copy to avoid concurrent modification)
        val pagesToEvict = cache.pages.filter { page ->
            page != justAddedPage && page !in keepRange
        }

        for (page in pagesToEvict) {
            val evicted = cache.removeFromCache(page)
            if (evicted != null) {
                cache.logger?.log("SlidingWindowPagingCore", "evict: page=${evicted.page}")
                evictionListener?.onEvicted(evicted)
            }
        }
    }
}
