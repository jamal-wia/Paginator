package com.jamal_aliev.paginator.extension

import com.jamal_aliev.paginator.CursorPaginator
import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cache.CursorPersistentPagingCache
import com.jamal_aliev.paginator.page.PageState

/**
 * Eagerly restores L1 from the
 * [persistent cache][com.jamal_aliev.paginator.CursorPagingCore.persistentCache] (L2) —
 * reverse of [com.jamal_aliev.paginator.MutableCursorPaginator.flush].
 *
 * Cursor counterpart of
 * [warmUpFromPersistent][com.jamal_aliev.paginator.extension.warmUpFromPersistent] on the
 * offset-based `Paginator`. Every persisted `(cursor, state)` pair is inserted into L1 so
 * the doubly-linked chain is reconstructed before the first navigation call.
 *
 * ### Behaviour
 *
 * - Reads every entry via [CursorPersistentPagingCache.loadAll] and writes each pair into L1
 *   silently (no intermediate snapshot emissions).
 * - Pages whose `self` key is **already present** in L1 are skipped — the in-memory copy wins.
 * - A single [snapshot][com.jamal_aliev.paginator.CursorPagingCore.snapshot] is emitted at the
 *   end **only** if a [cursorRange] is provided. Without a range the context window is still
 *   `null`, so there is nothing observable to emit until the caller [jumps]
 *   [com.jamal_aliev.paginator.CursorPaginator.jump] into a cursor.
 * - No-op when [com.jamal_aliev.paginator.CursorPagingCore.persistentCache] is `null`.
 *
 * ### Link consistency caveat
 *
 * The cursor cache is keyed by [CursorBookmark.self] but ordering is reconstructed by walking
 * `prev`/`next` links. This function assumes L2 stored both endpoints — if L2 dropped a
 * chain link (e.g. after `removeAll` of the tail), the warm-up will still load the surviving
 * islands but `walkForward`/`walkBackward` from a head cursor will stop at the gap. Callers
 * that rely on a fully-connected chain after warm-up must take care with selective deletion.
 *
 * ### Concurrency
 *
 * This function does **not** acquire the paginator's navigation mutex. It is intended to run
 * before the first navigation call.
 *
 * @param cursorRange Optional `(start, end)` pair to pass through to
 *   [snapshot][com.jamal_aliev.paginator.CursorPagingCore.snapshot] so a newly-warmed range
 *   becomes visible without a jump. When `null` no snapshot is emitted.
 * @return The number of pages actually inserted into L1 (skipped duplicates are not counted).
 */
suspend fun <T> CursorPaginator<T>.warmUpFromPersistent(
    cursorRange: Pair<CursorBookmark, CursorBookmark>? = null,
): Int {
    val pc: CursorPersistentPagingCache<T> = core.persistentCache ?: return 0

    val persisted: List<Pair<CursorBookmark, PageState<T>>> = pc.loadAll()
    if (persisted.isEmpty()) return 0

    var inserted = 0
    for ((cursor, state) in persisted) {
        if (cache.getStateOf(cursor.self) != null) continue
        cache.setState(cursor, state, silently = true)
        inserted++
    }

    if (cursorRange != null) core.snapshot(cursorRange)
    return inserted
}
