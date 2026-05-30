package com.jamal_aliev.paginator.cursor.cache.persistent

import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.page.CursorPageState

/**
 * An optional second-level (L2) persistent cache for
 * [com.jamal_aliev.paginator.cursor.CursorPaginator], keyed by [CursorBookmark<K>.self].
 *
 * Unlike the offset-based [PersistentPagingCache], this interface stores the full
 * [CursorBookmark<K>] alongside the [PageState] so that `prev`/`next` links survive
 * process death.
 *
 * **Read path:** L1 → L2 → source. When a navigation method encounters a cache
 * miss in L1, it checks L2 before making a network call.
 *
 * **Write path:** After every successful source load, the resulting
 * [PageState.SuccessPage] (including pages that came back empty) is automatically
 * saved to L2 via [save] together with its [CursorBookmark<K>].
 *
 * **Lifecycle:** L2 is **not** cleared by
 * [com.jamal_aliev.paginator.cursor.CursorPaginator.restart],
 * [com.jamal_aliev.paginator.cursor.CursorPaginator.release] or transaction rollback.
 * Lifecycle is the consumer's responsibility.
 *
 * @param T The type of elements contained in each page.
 */
interface CursorPersistentPagingCache<K : Any, T> {

    /** Persists a single page state together with its bookmark. */
    suspend fun save(cursor: CursorBookmark<K>, state: CursorPageState<K, T>)

    /**
     * Persists multiple `(cursor, state)` entries in a single operation.
     *
     * The default implementation calls [save] sequentially; implementations may
     * override for batch-optimised storage.
     */
    suspend fun saveAll(entries: List<Pair<CursorBookmark<K>, CursorPageState<K, T>>>) {
        entries.forEach { (cursor, state) -> save(cursor, state) }
    }

    /** Loads the page identified by [self], or `null` if not stored. */
    suspend fun load(self: K): Pair<CursorBookmark<K>, CursorPageState<K, T>>?

    /** Loads every page currently held in persistent storage. */
    suspend fun loadAll(): List<Pair<CursorBookmark<K>, CursorPageState<K, T>>>

    /** Removes a single page from persistent storage. */
    suspend fun remove(self: K)

    /**
     * Removes multiple pages from persistent storage.
     *
     * The default implementation calls [remove] for each page sequentially.
     */
    suspend fun removeAll(selves: List<K>) {
        selves.forEach { remove(it) }
    }

    /** Removes every page from persistent storage. */
    suspend fun clear()

    /**
     * Executes [block] inside a storage transaction when the backend supports it.
     *
     * The default implementation simply runs [block] without any transactional
     * guarantees.
     */
    suspend fun <R> transaction(block: suspend CursorPersistentPagingCache<K, T>.() -> R): R {
        return block()
    }
}
