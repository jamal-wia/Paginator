package com.jamal_aliev.paginator.extension

import com.jamal_aliev.paginator.cache.CursorPagingCache
import com.jamal_aliev.paginator.cache.eviction.CursorChainablePagingCache

/**
 * Composes two cursor-cache strategies so that [this] wraps [inner].
 *
 * The operator is left-associative, so `a + b + c` builds the chain
 * `a → b → c → CursorInMemoryPagingCache`. The leaf at the bottom of [this]'s
 * chain is replaced by [inner], recursively.
 *
 * When [this] is not a [CursorChainablePagingCache] (i.e. it is the leaf
 * [com.jamal_aliev.paginator.cache.CursorInMemoryPagingCache]), [inner] is
 * returned directly.
 */
operator fun <T> CursorPagingCache<T>.plus(inner: CursorPagingCache<T>): CursorPagingCache<T> =
    withLeaf(inner)

/**
 * Replaces the leaf [com.jamal_aliev.paginator.cache.CursorInMemoryPagingCache] at
 * the bottom of this cursor-cache's delegation chain with [newLeaf]. If [this]
 * is not a [CursorChainablePagingCache], [newLeaf] is returned directly.
 */
fun <T> CursorPagingCache<T>.withLeaf(newLeaf: CursorPagingCache<T>): CursorPagingCache<T> =
    if (this is CursorChainablePagingCache<T>) this.replaceLeaf(newLeaf) else newLeaf
