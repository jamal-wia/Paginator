package com.jamal_aliev.paginator.cursor.extension

import com.jamal_aliev.paginator.cursor.cache.CursorPagingCache
import com.jamal_aliev.paginator.cursor.cache.eviction.CursorChainablePagingCache

/**
 * Composes two cursor-cache strategies so that [this] wraps [inner].
 *
 * The operator is left-associative, so `a + b + c` builds the chain
 * `a → b → c → CursorInMemoryPagingCache`. The leaf at the bottom of [this]'s
 * chain is replaced by [inner], recursively.
 *
 * When [this] is not a [CursorChainablePagingCache] (i.e. it is the leaf
 * [com.jamal_aliev.paginator.cursor.cache.CursorInMemoryPagingCache]), [inner] is
 * returned directly.
 */
operator fun <K : Any, T> CursorPagingCache<K, T>.plus(inner: CursorPagingCache<K, T>): CursorPagingCache<K, T> =
    withLeaf(inner)

/**
 * Replaces the leaf [com.jamal_aliev.paginator.cursor.cache.CursorInMemoryPagingCache] at
 * the bottom of this cursor-cache's delegation chain with [newLeaf]. If [this]
 * is not a [CursorChainablePagingCache], [newLeaf] is returned directly.
 */
fun <K : Any, T> CursorPagingCache<K, T>.withLeaf(newLeaf: CursorPagingCache<K, T>): CursorPagingCache<K, T> =
    if (this is CursorChainablePagingCache<K, T>) this.replaceLeaf(newLeaf) else newLeaf
