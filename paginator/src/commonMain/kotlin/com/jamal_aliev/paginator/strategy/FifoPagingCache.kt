package com.jamal_aliev.paginator.strategy

import com.jamal_aliev.paginator.page.PageState

/**
 * A [PagingCache] decorator that enforces a **FIFO (First In, First Out)** eviction policy.
 *
 * When the number of cached pages exceeds [maxSize], the oldest page (first inserted)
 * is evicted. Unlike [LruPagingCache], reading a page does **not** change its eviction priority —
 * only the original insertion order matters.
 *
 * Pages within the current context window can optionally be protected from eviction
 * via [protectContextWindow].
 *
 * ## Usage
 * ```kotlin
 * val paginator = MutablePaginator(
 *     pagingCore = PagingCore(
 *         cache = FifoPagingCache(maxSize = 30),
 *         initialCapacity = 20
 *     ),
 *     source = { page -> api.loadPage(page) }
 * )
 * ```
 *
 * @param delegate The inner [PagingCache] to delegate to. Defaults to [DefaultPagingCache].
 * @param maxSize Maximum number of pages to keep in cache. Must be > 0.
 * @param protectContextWindow If `true` (default), pages within the visible context window
 *   are never evicted.
 * @param evictionListener Optional listener notified when a page is evicted.
 */
class FifoPagingCache<T>(
    private val delegate: PagingCache<T> = DefaultPagingCache(),
    val maxSize: Int,
    val protectContextWindow: Boolean = true,
    var evictionListener: CacheEvictionListener<T>? = null,
) : PagingCache<T> by delegate {

    init {
        require(maxSize > 0) { "maxSize must be greater than 0, was $maxSize" }
    }

    /** Tracks page insertion order: head = oldest, tail = newest. */
    private val insertionOrder = mutableListOf<Int>()

    override fun setState(state: PageState<T>, silently: Boolean) {
        val isNew = delegate.getStateOf(state.page) == null
        delegate.setState(state, silently)
        if (isNew) {
            insertionOrder.add(state.page)
        }
        performEviction(justAddedPage = state.page)
    }

    override fun removeFromCache(page: Int): PageState<T>? {
        val result = delegate.removeFromCache(page)
        if (result != null) insertionOrder.remove(page)
        return result
    }

    override fun clear() {
        delegate.clear()
        insertionOrder.clear()
    }

    override fun release(capacity: Int, silently: Boolean) {
        delegate.release(capacity, silently)
        insertionOrder.clear()
    }

    /**
     * Evicts oldest-inserted pages until [size] <= [maxSize].
     * Pages in the context window are skipped when [protectContextWindow] is `true`.
     *
     * @param justAddedPage The page that was just added — never evicted in the same call.
     */
    private fun performEviction(justAddedPage: Int = -1) {
        while (delegate.size > maxSize) {
            val protectedRange = if (protectContextWindow && delegate.isStarted)
                delegate.startContextPage..delegate.endContextPage else null

            val victim = insertionOrder.firstOrNull { page ->
                page != justAddedPage &&
                    delegate.getStateOf(page) != null &&
                    (protectedRange == null || page !in protectedRange)
            } ?: break

            val evicted = delegate.removeFromCache(victim)
            insertionOrder.remove(victim)
            if (evicted != null) {
                delegate.logger?.log("FifoPagingCore", "evict: page=${evicted.page}")
                evictionListener?.onEvicted(evicted)
            }
        }
    }
}
