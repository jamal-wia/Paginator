package com.jamal_aliev.paginator.extension

import com.jamal_aliev.paginator.cache.PagingCache
import com.jamal_aliev.paginator.cache.eviction.ChainablePagingCache

/**
 * Composes two cache strategies so that [this] wraps [inner].
 *
 * The operator is left-associative, so `a + b + c` builds the chain `a → b → c → InMemoryPagingCache`.
 * The leaf [com.jamal_aliev.paginator.cache.InMemoryPagingCache] of [this] is replaced by [inner], recursively.
 *
 * ```kotlin
 * // Equivalent forms:
 * val cache = MostRecentPagingCache(maxSize = 50) + TimeLimitedPagingCache(ttl = 5.minutes) + ContextWindowPagingCache()
 *
 * val cache = MostRecentPagingCache(
 *     delegate = TimeLimitedPagingCache(
 *         delegate = ContextWindowPagingCache(),
 *         ttl = 5.minutes
 *     ),
 *     maxSize = 50
 * )
 * ```
 *
 * When [this] is not a [com.jamal_aliev.paginator.cache.ChainablePagingCache] (i.e., it is the leaf [com.jamal_aliev.paginator.cache.InMemoryPagingCache]),
 * [inner] is returned directly.
 */
operator fun <T> PagingCache<T>.plus(inner: PagingCache<T>): PagingCache<T> =
    withLeaf(inner)

/**
 * Replaces the leaf [com.jamal_aliev.paginator.cache.InMemoryPagingCache] at the bottom of this cache's delegation chain
 * with [newLeaf]. If [this] is not a [com.jamal_aliev.paginator.cache.ChainablePagingCache], [newLeaf] is returned directly.
 *
 * Custom strategy implementations should call this on their private inner cache field
 * inside [com.jamal_aliev.paginator.cache.ChainablePagingCache.replaceLeaf]:
 * ```kotlin
 * override fun replaceLeaf(newLeaf: PagingCache<T>): MyCache<T> =
 *     MyCache(cache = cache.withLeaf(newLeaf), myParam = myParam)
 * ```
 */
fun <T> PagingCache<T>.withLeaf(newLeaf: PagingCache<T>): PagingCache<T> =
    if (this is ChainablePagingCache<T>) this.replaceLeaf(newLeaf) else newLeaf
