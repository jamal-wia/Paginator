package com.jamal_aliev.paginator.extension

import com.jamal_aliev.paginator.Paginator
import com.jamal_aliev.paginator.cache.PersistentPagingCache
import com.jamal_aliev.paginator.page.PageState

/**
 * Eagerly restores L1 from the [persistent cache][com.jamal_aliev.paginator.PagingCore.persistentCache]
 * (L2) — reverse of [com.jamal_aliev.paginator.MutablePaginator.flush].
 *
 * On cold start the in-memory cache is empty and pages are lazily hydrated from L2 only
 * when navigation asks for them. For UIs that want the full feed available before the user
 * touches it (e.g. to render a scrollable list immediately), call this once after
 * constructing the paginator and before any navigation happens.
 *
 * ### Behaviour
 *
 * - Reads every entry via [PersistentPagingCache.loadAll] and writes each state into L1
 *   silently (no intermediate snapshot emissions).
 * - Pages that are **already present** in L1 are left untouched — the in-memory copy wins.
 * - A single [snapshot][com.jamal_aliev.paginator.PagingCore.snapshot] is emitted at the end
 *   **only** if a [pageRange] is provided. Without a range the context window is still
 *   `null`/`0`, so there is nothing observable to emit until the caller [jumps]
 *   [com.jamal_aliev.paginator.Paginator.jump] into a page.
 * - No-op when [com.jamal_aliev.paginator.PagingCore.persistentCache] is `null`.
 *
 * ### Interaction with eviction strategies
 *
 * Warming up bypasses the normal "load → cache → strategy" flow by pushing states straight
 * into the cache via its public [setState][com.jamal_aliev.paginator.cache.PagingCache.setState]
 * entry point. If L2 contains more pages than the strategy is willing to hold (e.g.
 * `LruPagingCache(maxSize = 20)` + 100 persisted pages) the strategy will evict on each
 * insert and the final L1 size will match the strategy's capacity, not L2's size.
 * [SlidingWindowPagingCache][com.jamal_aliev.paginator.cache.SlidingWindowPagingCache] with
 * no established context window will effectively discard every inserted page — prefer
 * `LruPagingCache` or pair the warm-up with a subsequent `jump(...)` to establish context.
 *
 * ### Concurrency
 *
 * This function does **not** acquire the paginator's navigation mutex. It is intended to run
 * before the first navigation call. Running it concurrently with navigation may result in
 * warmed-up pages being overwritten by a fresh source load — usually harmless, but not
 * strictly ordered.
 *
 * @param pageRange Optional range to pass through to
 *   [snapshot][com.jamal_aliev.paginator.PagingCore.snapshot] so a newly-warmed range becomes
 *   visible without a jump. When `null` no snapshot is emitted.
 * @return The number of pages actually inserted into L1 (skipped duplicates are not counted).
 */
suspend fun <T> Paginator<T>.warmUpFromPersistent(pageRange: IntRange? = null): Int {
    val pc: PersistentPagingCache<T> = core.persistentCache ?: return 0

    val persisted: List<PageState<T>> = pc.loadAll()
    if (persisted.isEmpty()) return 0

    var inserted = 0
    for (state in persisted) {
        if (cache.getStateOf(state.page) != null) continue
        cache.setState(state, silently = true)
        inserted++
    }

    if (pageRange != null) core.snapshot(pageRange)
    return inserted
}
