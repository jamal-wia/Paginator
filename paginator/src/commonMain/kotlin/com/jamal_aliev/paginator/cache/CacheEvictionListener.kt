package com.jamal_aliev.paginator.cache

import com.jamal_aliev.paginator.page.PageState

/**
 * Listener that is notified when a page is evicted from the cache
 * by a cache eviction strategy (e.g., [LruPagingCache], [FifoPagingCache], [TtlPagingCache]).
 *
 * Use this to react to eviction events — for example, to persist evicted data
 * before it is removed from the cache.
 *
 * @param T The type of elements contained in each page.
 */
fun interface CacheEvictionListener<T> {
    /**
     * Called when a page is about to be evicted from the cache.
     *
     * @param pageState The [PageState] being evicted, including its data.
     */
    fun onEvicted(pageState: PageState<T>)
}
