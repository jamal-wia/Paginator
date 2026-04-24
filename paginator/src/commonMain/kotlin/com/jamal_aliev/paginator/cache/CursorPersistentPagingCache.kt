package com.jamal_aliev.paginator.cache

import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.page.PageState

/**
 * An optional second-level (L2) persistent cache for
 * [com.jamal_aliev.paginator.CursorPaginator], keyed by [CursorBookmark.self].
 *
 * Unlike the offset-based [PersistentPagingCache], this interface stores the full
 * [CursorBookmark] alongside the [PageState] so that `prev`/`next` links survive
 * process death.
 *
 * **Read path:** L1 → L2 → source. When a navigation method encounters a cache
 * miss in L1, it checks L2 before making a network call.
 *
 * **Write path:** After every successful source load, the resulting
 * [PageState.SuccessPage] (or [PageState.EmptyPage]) is automatically saved
 * to L2 via [save] together with its [CursorBookmark].
 *
 * **Lifecycle:** L2 is **not** cleared by
 * [com.jamal_aliev.paginator.CursorPaginator.restart],
 * [com.jamal_aliev.paginator.CursorPaginator.release] or transaction rollback.
 * Lifecycle is the consumer's responsibility.
 *
 * @param T The type of elements contained in each page.
 */
interface CursorPersistentPagingCache<T> {

    /** Persists a single page state together with its bookmark. */
    suspend fun save(cursor: CursorBookmark, state: PageState<T>)

    /**
     * Persists multiple `(cursor, state)` entries in a single operation.
     *
     * The default implementation calls [save] sequentially; implementations may
     * override for batch-optimised storage.
     */
    suspend fun saveAll(entries: List<Pair<CursorBookmark, PageState<T>>>) {
        entries.forEach { (cursor, state) -> save(cursor, state) }
    }

    /** Loads the page identified by [self], or `null` if not stored. */
    suspend fun load(self: Any): Pair<CursorBookmark, PageState<T>>?

    /** Loads every page currently held in persistent storage. */
    suspend fun loadAll(): List<Pair<CursorBookmark, PageState<T>>>

    /** Removes a single page from persistent storage. */
    suspend fun remove(self: Any)

    /**
     * Removes multiple pages from persistent storage.
     *
     * The default implementation calls [remove] for each page sequentially.
     */
    suspend fun removeAll(selves: List<Any>) {
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
    suspend fun <R> transaction(block: suspend CursorPersistentPagingCache<T>.() -> R): R {
        return block()
    }
}
