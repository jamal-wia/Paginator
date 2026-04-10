package com.jamal_aliev.paginator.strategy

import com.jamal_aliev.paginator.PagingCore
import com.jamal_aliev.paginator.page.PageState

/**
 * A [PagingCore] decorator that enforces a **FIFO (First In, First Out)** eviction policy.
 *
 * When the number of cached pages exceeds [maxSize], the oldest page (first inserted)
 * is evicted. Unlike [LruPagingCore], reading a page does **not** change its eviction priority —
 * only the original insertion order matters.
 *
 * Pages within the current context window can optionally be protected from eviction
 * via [protectContextWindow].
 *
 * ## Usage
 * ```kotlin
 * val paginator = MutablePaginator<Item>(
 *     core = FifoPagingCore(maxSize = 30),
 *     source = { page -> api.loadPage(page) }
 * )
 * ```
 *
 * @param initialCapacity Expected items per page (passed to [PagingCore]).
 * @param maxSize Maximum number of pages to keep in cache. Must be > 0.
 * @param protectContextWindow If `true` (default), pages within the visible context window
 *   are never evicted.
 * @param evictionListener Optional listener notified when a page is evicted.
 */
class FifoPagingCore<T>(
    initialCapacity: Int = DEFAULT_CAPACITY,
    val maxSize: Int,
    val protectContextWindow: Boolean = true,
    var evictionListener: CacheEvictionListener<T>? = null,
) : PagingCore<T>(initialCapacity) {

    init {
        require(maxSize > 0) { "maxSize must be greater than 0, was $maxSize" }
    }

    /** Tracks page insertion order: head = oldest, tail = newest. */
    private val insertionOrder = mutableListOf<Int>()

    override fun setState(state: PageState<T>, silently: Boolean) {
        val isNew = getStateOfDirect(state.page) == null
        super.setState(state, silently)
        if (isNew) {
            insertionOrder.add(state.page)
        }
        performEviction(justAddedPage = state.page)
    }

    override fun removeFromCache(page: Int): PageState<T>? {
        val result = super.removeFromCache(page)
        if (result != null) insertionOrder.remove(page)
        return result
    }

    override fun clear() {
        super.clear()
        insertionOrder.clear()
    }

    override fun release(capacity: Int, silently: Boolean) {
        super.release(capacity, silently)
        insertionOrder.clear()
    }

    /**
     * Direct cache lookup without side effects.
     * Used internally to check existence before setState without triggering
     * any tracking logic in potential subclasses.
     */
    private fun getStateOfDirect(page: Int): PageState<T>? {
        return super.getStateOf(page)
    }

    /**
     * Evicts oldest-inserted pages until [size] <= [maxSize].
     * Pages in the context window are skipped when [protectContextWindow] is `true`.
     *
     * @param justAddedPage The page that was just added — never evicted in the same call.
     */
    private fun performEviction(justAddedPage: Int = -1) {
        while (size > maxSize) {
            val protectedRange = if (protectContextWindow && isStarted)
                startContextPage..endContextPage else null

            val victim = insertionOrder.firstOrNull { page ->
                page != justAddedPage &&
                    (protectedRange == null || page !in protectedRange)
            } ?: break

            val evicted = super.removeFromCache(victim)
            insertionOrder.remove(victim)
            if (evicted != null) {
                logger?.log("FifoPagingCore", "evict: page=${evicted.page}")
                evictionListener?.onEvicted(evicted)
            }
        }
    }
}
