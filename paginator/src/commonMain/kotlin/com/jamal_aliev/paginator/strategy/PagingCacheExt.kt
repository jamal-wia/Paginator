package com.jamal_aliev.paginator.strategy

/**
 * Composes two cache strategies so that [this] wraps [inner].
 *
 * The operator is left-associative, so `a + b + c` builds the chain `a → b → c → DefaultPagingCache`.
 * The leaf [DefaultPagingCache] of [this] is replaced by [inner], recursively.
 *
 * ```kotlin
 * // Equivalent forms:
 * val cache = LruPagingCache(maxSize = 50) + TtlPagingCache(ttl = 5.minutes) + SlidingWindowPagingCache()
 *
 * val cache = LruPagingCache(
 *     delegate = TtlPagingCache(
 *         delegate = SlidingWindowPagingCache(),
 *         ttl = 5.minutes
 *     ),
 *     maxSize = 50
 * )
 * ```
 *
 * When [this] is not a [WrappablePagingCache] (i.e., it is the leaf [DefaultPagingCache]),
 * [inner] is returned directly.
 */
operator fun <T> PagingCache<T>.plus(inner: PagingCache<T>): PagingCache<T> =
    withLeaf(inner)

/**
 * Replaces the leaf [DefaultPagingCache] at the bottom of this cache's delegation chain
 * with [newLeaf]. If [this] is not a [WrappablePagingCache], [newLeaf] is returned directly.
 *
 * Custom strategy implementations should call this on their private inner cache field
 * inside [WrappablePagingCache.replaceLeaf]:
 * ```kotlin
 * override fun replaceLeaf(newLeaf: PagingCache<T>): MyCache<T> =
 *     MyCache(cache = cache.withLeaf(newLeaf), myParam = myParam)
 * ```
 */
fun <T> PagingCache<T>.withLeaf(newLeaf: PagingCache<T>): PagingCache<T> =
    if (this is WrappablePagingCache<T>) this.replaceLeaf(newLeaf) else newLeaf
