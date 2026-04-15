package com.jamal_aliev.paginator.cache

/**
 * A [PagingCache] that can participate in strategy composition via the [com.jamal_aliev.paginator.extension.plus] operator.
 *
 * All four built-in eviction strategies ([LruPagingCache], [FifoPagingCache],
 * [TtlPagingCache], [SlidingWindowPagingCache]) implement this interface, enabling
 * left-to-right strategy composition:
 *
 * ```kotlin
 * val cache = LruPagingCache(maxSize = 50) + TtlPagingCache(ttl = 5.minutes) + SlidingWindowPagingCache()
 * // equivalent to:
 * // LruPagingCache(delegate = TtlPagingCache(delegate = SlidingWindowPagingCache(), ttl = 5.minutes), maxSize = 50)
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
interface WrappablePagingCache<T> : PagingCache<T> {

    /**
     * Returns a copy of this decorator with [newLeaf] inserted at the bottom of
     * the delegation chain, replacing the current leaf [DefaultPagingCache].
     *
     * Use [com.jamal_aliev.paginator.extension.withLeaf] on your private inner cache to recurse correctly:
     * ```kotlin
     * override fun replaceLeaf(newLeaf: PagingCache<T>): MyCache<T> =
     *     MyCache(cache = cache.withLeaf(newLeaf), myParam = myParam)
     * ```
     */
    fun replaceLeaf(newLeaf: PagingCache<T>): WrappablePagingCache<T>
}
