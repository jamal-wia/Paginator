package com.jamal_aliev.paginator.cursor.prefetch

import com.jamal_aliev.paginator.core.page.PageState
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark

/**
 * Stable functional interface for the cursor-load guard used by
 * `CursorPaginatorPrefetchController` — cursor-paginator counterpart of `PageLoadGuard`.
 *
 * Prefer this over a raw lambda when storing the guard in a hoisted state holder — `fun
 * interface` instances are treated as stable by the Compose compiler, while plain lambdas are
 * not, which can cause spurious recompositions of consumers that capture the guard.
 */
fun interface CursorLoadGuard<T> {
    operator fun invoke(cursor: CursorBookmark, state: PageState<T>?): Boolean

    companion object {
        private val ALLOW_ALL: CursorLoadGuard<Any?> = CursorLoadGuard { _, _ -> true }

        /** Guard that admits every cursor load. Cached singleton — safe to reuse. */
        @Suppress("UNCHECKED_CAST")
        fun <T> allowAll(): CursorLoadGuard<T> = ALLOW_ALL as CursorLoadGuard<T>
    }
}
