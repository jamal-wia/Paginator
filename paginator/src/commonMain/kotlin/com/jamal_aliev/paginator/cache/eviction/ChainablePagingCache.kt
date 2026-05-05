package com.jamal_aliev.paginator.cache.eviction

import com.jamal_aliev.paginator.cache.PagingCache

/**
 * A [PagingCache] that can participate in strategy composition via the [com.jamal_aliev.paginator.extension.plus] operator.
 *
 * All four built-in eviction strategies ([MostRecentPagingCache], [QueuedPagingCache],
 * [TimeLimitedPagingCache], [ContextWindowPagingCache]) implement this interface, enabling
 * left-to-right strategy composition:
 *
 * ```kotlin
 * val cache = MostRecentPagingCache(maxSize = 50) + TimeLimitedPagingCache(ttl = 5.minutes) + ContextWindowPagingCache()
 * // equivalent to:
 * // MostRecentPagingCache(delegate = TimeLimitedPagingCache(delegate = ContextWindowPagingCache(), ttl = 5.minutes), maxSize = 50)
 * ```
 *
 * Implement [replaceLeaf] using [com.jamal_aliev.paginator.extension.withLeaf] on your inner cache field:
 * ```kotlin
 * override fun replaceLeaf(newLeaf: PagingCache<T>): MyCache<T> =
 *     MyCache(cache = cache.withLeaf(newLeaf), myParam = myParam)
 * ```
 *
 * @see com.jamal_aliev.paginator.extension.plus
 * @see com.jamal_aliev.paginator.extension.withLeaf
 */
interface ChainablePagingCache<T> : PagingCache<T> {

    /**
     * Returns a copy of this decorator with [newLeaf] inserted at the bottom of
     * the delegation chain, replacing the current leaf [InMemoryPagingCache].
     *
     * Use [com.jamal_aliev.paginator.extension.withLeaf] on your private inner cache to recurse correctly:
     * ```kotlin
     * override fun replaceLeaf(newLeaf: PagingCache<T>): MyCache<T> =
     *     MyCache(cache = cache.withLeaf(newLeaf), myParam = myParam)
     * ```
     */
    fun replaceLeaf(newLeaf: PagingCache<T>): ChainablePagingCache<T>
}
