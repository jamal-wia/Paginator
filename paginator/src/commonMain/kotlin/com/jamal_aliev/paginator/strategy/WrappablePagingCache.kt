package com.jamal_aliev.paginator.strategy

/**
 * A [PagingCache] that wraps another [PagingCache] and can be composed via the [plus] operator.
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
 * @see plus
 */
interface WrappablePagingCache<T> : PagingCache<T> {

    /** The immediate inner cache this decorator wraps. */
    val wrapped: PagingCache<T>

    /** Returns a copy of this decorator with [inner] as the new inner cache. */
    fun wrap(inner: PagingCache<T>): WrappablePagingCache<T>
}
