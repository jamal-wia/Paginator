package com.jamal_aliev.paginator.prefetch

import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.page.PageState

/**
 * Default [PaginatorPrefetchController.prefetchDistance] used by the optional UI-binding
 * artifacts (`paginator-compose`, `paginator-view`) when no explicit value is provided.
 */
public const val DefaultPrefetchDistance: Int = 6

/**
 * Bag of runtime-mutable settings for a [PaginatorPrefetchController] / UI-binding helper.
 *
 * Shared by `paginator-compose` and `paginator-view` so configuration written once can be
 * applied to either UI surface. All fields map directly onto the corresponding controller
 * properties, plus two binding-side knobs ([cancelOnDispose], [scrollSampleMillis]) that the
 * controller itself doesn't care about.
 *
 * Data class with all `val` primitive fields — the Compose compiler infers it as **stable**, so
 * passing the same instance across recompositions does not invalidate downstream skipping.
 *
 * @property prefetchDistance Distance from the edge (in items) at which prefetch fires.
 * @property enableBackwardPrefetch If `true`, scrolling toward the start triggers prefetch.
 * @property silentlyLoading Suppress `ProgressPage` snapshot emission during prefetch loading.
 * @property silentlyResult Suppress snapshot emission when the prefetched page arrives.
 * @property enabled Master switch — `false` makes [PaginatorPrefetchController.onScroll] a no-op.
 * @property cancelOnDispose If `true`, [PaginatorPrefetchController.cancel] runs on teardown
 *   (Compose `onDispose` / View `Lifecycle.Event.ON_DESTROY`). Set to `false` to let an in-flight
 *   prefetch survive a brief teardown gap (overlay, bottom-sheet, screen recreation) so the page
 *   lands when the screen returns.
 * @property scrollSampleMillis When > 0, throttle the scroll signal to one emission per window.
 *   Defaults to `0` (no throttling). Useful when `loadGuard` or downstream work is non-trivial
 *   and the user can scroll fast.
 */
public data class PrefetchOptions(
    val prefetchDistance: Int = DefaultPrefetchDistance,
    val enableBackwardPrefetch: Boolean = false,
    val silentlyLoading: Boolean = true,
    val silentlyResult: Boolean = false,
    val enabled: Boolean = true,
    val cancelOnDispose: Boolean = true,
    val scrollSampleMillis: Long = 0L,
)

/**
 * Stable functional interface for [PaginatorPrefetchController]'s page-load guard.
 *
 * Prefer this over a raw lambda when storing the guard in a hoisted state holder — `fun
 * interface` instances are treated as stable by the Compose compiler, while plain lambdas are
 * not, which can cause spurious recompositions of consumers that capture the guard.
 */
public fun interface PageLoadGuard<T> {
    public operator fun invoke(page: Int, state: PageState<T>?): Boolean

    public companion object {
        private val ALLOW_ALL: PageLoadGuard<Any?> = PageLoadGuard { _, _ -> true }

        /** Guard that admits every page load. Cached singleton — safe to reuse. */
        @Suppress("UNCHECKED_CAST")
        public fun <T> allowAll(): PageLoadGuard<T> = ALLOW_ALL as PageLoadGuard<T>
    }
}

/** Cursor-paginator counterpart of [PageLoadGuard]. */
public fun interface CursorLoadGuard<T> {
    public operator fun invoke(cursor: CursorBookmark, state: PageState<T>?): Boolean

    public companion object {
        private val ALLOW_ALL: CursorLoadGuard<Any?> = CursorLoadGuard { _, _ -> true }

        /** Guard that admits every cursor load. Cached singleton — safe to reuse. */
        @Suppress("UNCHECKED_CAST")
        public fun <T> allowAll(): CursorLoadGuard<T> = ALLOW_ALL as CursorLoadGuard<T>
    }
}
