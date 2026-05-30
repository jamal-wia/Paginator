package com.jamal_aliev.paginator.cursor.prefetch

import com.jamal_aliev.paginator.cursor.page.CursorPageState
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark

/**
 * Stable functional interface for the cursor-load guard used by
 * `CursorPaginatorPrefetchController` — cursor-paginator counterpart of `PageLoadGuard`.
 *
 * Prefer this over a raw lambda when storing the guard in a hoisted state holder — `fun
 * interface` instances are treated as stable by the Compose compiler, while plain lambdas are
 * not, which can cause spurious recompositions of consumers that capture the guard.
 */
fun interface CursorLoadGuard<K : Any, T> {
    operator fun invoke(cursor: CursorBookmark<K>, state: CursorPageState<K, T>?): Boolean

    companion object {
        private val ALLOW_ALL: CursorLoadGuard<Any, Any?> = CursorLoadGuard { _, _ -> true }

        /** Guard that admits every cursor load. Cached singleton — safe to reuse. */
        @Suppress("UNCHECKED_CAST")
        fun <K : Any, T> allowAll(): CursorLoadGuard<K, T> = ALLOW_ALL as CursorLoadGuard<K, T>
    }
}
