package com.jamal_aliev.paginator.strategy

import com.jamal_aliev.paginator.page.PageState

/**
 * An optional second-level (L2) persistent cache for [com.jamal_aliev.paginator.Paginator].
 *
 * While [PagingCache] keeps pages in memory (L1), a [PersistentPagingCache] allows
 * pages to survive process death by storing them in a durable backend (Room, SQLite,
 * files, etc.). The library provides only this interface — the implementation is
 * entirely up to the consumer.
 *
 * **Read path:** L1 → L2 → source. When a navigation method encounters a cache miss
 * in L1, it checks L2 before making a network call. If L2 has the page, it is promoted
 * back into L1 and returned immediately — no loading indicator, no source invocation.
 *
 * **Write path:** After every successful source load, the resulting [PageState.SuccessPage]
 * (or [PageState.EmptyPage]) is automatically saved to L2 via [save].
 *
 * **Lifecycle:** L2 is **not** cleared by [com.jamal_aliev.paginator.Paginator.restart],
 * [com.jamal_aliev.paginator.Paginator.release], or transaction rollback. It is the
 * consumer's responsibility to manage L2 lifecycle (e.g., clearing stale data on logout).
 *
 * ## Usage
 * ```kotlin
 * val paginator = MutablePaginator(
 *     pagingCore = PagingCore(
 *         cache = LruPagingCache(maxSize = 50),
 *         persistentCache = MyRoomPagingCache(dao)
 *     ),
 *     source = { page -> api.loadPage(page) }
 * )
 * ```
 *
 * @param T The type of elements contained in each page.
 * @see PagingCache
 * @see com.jamal_aliev.paginator.PagingCore
 */
interface PersistentPagingCache<T> {

    /**
     * Persists a single page state.
     *
     * Called automatically after a successful source load. If a page with the same
     * number already exists, it should be replaced.
     */
    suspend fun save(state: PageState<T>)

    /**
     * Persists multiple page states in a single operation.
     *
     * The default implementation calls [save] for each state sequentially.
     * Implementations may override this for batch-optimized storage (e.g., Room's
     * `@Insert(onConflict = REPLACE)` with a list parameter).
     */
    suspend fun saveAll(states: List<PageState<T>>) {
        states.forEach { save(it) }
    }

    /**
     * Loads a single page from persistent storage.
     *
     * @param page The page number to load.
     * @return The persisted [PageState], or `null` if the page is not stored.
     */
    suspend fun load(page: Int): PageState<T>?

    /**
     * Loads all pages currently held in persistent storage.
     *
     * Useful for eager warm-up on cold start (restoring the full cache at once).
     *
     * @return A list of all persisted page states, in any order.
     */
    suspend fun loadAll(): List<PageState<T>>

    /**
     * Removes a single page from persistent storage.
     *
     * @param page The page number to remove.
     */
    suspend fun remove(page: Int)

    /**
     * Removes all pages from persistent storage.
     */
    suspend fun clear()
}
