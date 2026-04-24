package com.jamal_aliev.paginator.extension

import com.jamal_aliev.paginator.cache.CursorPagingCache
import com.jamal_aliev.paginator.cache.CursorWrappablePagingCache

/**
 * Composes two cursor-cache strategies so that [this] wraps [inner].
 *
 * The operator is left-associative, so `a + b + c` builds the chain
 * `a → b → c → DefaultCursorPagingCache`. The leaf at the bottom of [this]'s
 * chain is replaced by [inner], recursively.
 *
 * When [this] is not a [CursorWrappablePagingCache] (i.e. it is the leaf
 * [com.jamal_aliev.paginator.cache.DefaultCursorPagingCache]), [inner] is
 * returned directly.
 */
operator fun <T> CursorPagingCache<T>.plus(inner: CursorPagingCache<T>): CursorPagingCache<T> =
    withLeaf(inner)

/**
 * Replaces the leaf [com.jamal_aliev.paginator.cache.DefaultCursorPagingCache] at
 * the bottom of this cursor-cache's delegation chain with [newLeaf]. If [this]
 * is not a [CursorWrappablePagingCache], [newLeaf] is returned directly.
 */
fun <T> CursorPagingCache<T>.withLeaf(newLeaf: CursorPagingCache<T>): CursorPagingCache<T> =
    if (this is CursorWrappablePagingCache<T>) this.replaceLeaf(newLeaf) else newLeaf
