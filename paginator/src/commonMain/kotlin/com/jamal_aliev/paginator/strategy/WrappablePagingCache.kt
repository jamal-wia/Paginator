package com.jamal_aliev.paginator.strategy

/**
 * A [PagingCache] that can participate in strategy composition via the [plus] operator.
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
 * Implement [replaceLeaf] using [withLeaf] on your inner cache field:
 * ```kotlin
 * override fun replaceLeaf(newLeaf: PagingCache<T>): MyCache<T> =
 *     MyCache(cache = cache.withLeaf(newLeaf), myParam = myParam)
 * ```
 *
 * @see plus
 * @see withLeaf
 */
interface WrappablePagingCache<T> : PagingCache<T> {

    /**
     * Returns a copy of this decorator with [newLeaf] inserted at the bottom of
     * the delegation chain, replacing the current leaf [DefaultPagingCache].
     *
     * Use [withLeaf] on your private inner cache to recurse correctly:
     * ```kotlin
     * override fun replaceLeaf(newLeaf: PagingCache<T>): MyCache<T> =
     *     MyCache(cache = cache.withLeaf(newLeaf), myParam = myParam)
     * ```
     */
    fun replaceLeaf(newLeaf: PagingCache<T>): WrappablePagingCache<T>
}
