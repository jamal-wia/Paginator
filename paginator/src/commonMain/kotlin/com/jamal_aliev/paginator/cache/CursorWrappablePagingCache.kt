package com.jamal_aliev.paginator.cache

/**
 * A [CursorPagingCache] that can participate in strategy composition via the
 * [com.jamal_aliev.paginator.extension.plus] operator.
 *
 * Built-in eviction strategies for cursor-based pagination
 * ([LruCursorPagingCache], [FifoCursorPagingCache], [TtlCursorPagingCache],
 * [SlidingWindowCursorPagingCache]) implement this interface, enabling
 * left-to-right strategy composition:
 *
 * ```kotlin
 * val cache = LruCursorPagingCache<Message>(maxSize = 50) +
 *     TtlCursorPagingCache(ttl = 5.minutes) +
 *     SlidingWindowCursorPagingCache()
 * ```
 *
 * @see com.jamal_aliev.paginator.extension.plus
 * @see com.jamal_aliev.paginator.extension.withLeaf
 */
interface CursorWrappablePagingCache<T> : CursorPagingCache<T> {

    /**
     * Returns a copy of this decorator with [newLeaf] inserted at the bottom of
     * the delegation chain, replacing the current leaf [DefaultCursorPagingCache].
     */
    fun replaceLeaf(newLeaf: CursorPagingCache<T>): CursorWrappablePagingCache<T>
}
