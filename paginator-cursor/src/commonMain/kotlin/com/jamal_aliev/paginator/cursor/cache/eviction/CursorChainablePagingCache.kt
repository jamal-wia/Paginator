package com.jamal_aliev.paginator.cursor.cache.eviction

import com.jamal_aliev.paginator.cursor.cache.CursorPagingCache

/**
 * A [CursorPagingCache] that can participate in strategy composition via the
 * [com.jamal_aliev.paginator.extension.plus] operator.
 *
 * Built-in eviction strategies for cursor-based pagination
 * ([CursorMostRecentPagingCache], [CursorQueuedPagingCache], [CursorTimeLimitedPagingCache],
 * [CursorContextWindowPagingCache]) implement this interface, enabling
 * left-to-right strategy composition:
 *
 * ```kotlin
 * val cache = CursorMostRecentPagingCache<Message>(maxSize = 50) +
 *     CursorTimeLimitedPagingCache(ttl = 5.minutes) +
 *     CursorContextWindowPagingCache()
 * ```
 *
 * @see com.jamal_aliev.paginator.extension.plus
 * @see com.jamal_aliev.paginator.extension.withLeaf
 */
interface CursorChainablePagingCache<K : Any, T> : CursorPagingCache<K, T> {

    /**
     * Returns a copy of this decorator with [newLeaf] inserted at the bottom of
     * the delegation chain, replacing the current leaf [CursorInMemoryPagingCache].
     */
    fun replaceLeaf(newLeaf: CursorPagingCache<K, T>): CursorChainablePagingCache<K, T>
}
