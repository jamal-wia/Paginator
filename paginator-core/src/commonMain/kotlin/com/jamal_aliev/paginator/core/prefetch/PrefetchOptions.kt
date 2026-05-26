package com.jamal_aliev.paginator.core.prefetch

import com.jamal_aliev.paginator.core.page.PageState

/**
 * Default `PaginatorPrefetchController.prefetchDistance` used by the optional UI-binding
 * artifacts (`paginator-compose-*`, `paginator-view-*`) when no explicit value is provided.
 */
const val DefaultPrefetchDistance: Int = 6

/**
 * Bag of runtime-mutable settings for a `PaginatorPrefetchController` / UI-binding helper.
 *
 * Shared by `paginator-compose-*` and `paginator-view-*` so configuration written once can be
 * applied to either UI surface. All fields map directly onto the corresponding controller
 * properties, plus two binding-side knobs ([cancelOnDispose], [scrollSampleMillis]) that the
 * controller itself doesn't care about.
 *
 * Data class with all `val` primitive fields — the Compose compiler infers it as **stable**, so
 * passing the same instance across recompositions does not invalidate downstream skipping.
 */
data class PrefetchOptions(
    val prefetchDistance: Int = DefaultPrefetchDistance,
    val enableBackwardPrefetch: Boolean = false,
    /**
     * If `true`, the `ProgressPage` snapshot emitted while a prefetched page is
     * loading is suppressed. The default is `false` so that an append-indicator
     * bound to `PaginatorUiState.Content.appendState` renders the loading state
     * automatically. Set to `true` to silence the indicator (e.g. background
     * prefetch where the user is far from the loading edge).
     */
    val silentlyLoading: Boolean = false,
    val silentlyResult: Boolean = false,
    val enabled: Boolean = true,
    val cancelOnDispose: Boolean = true,
    val scrollSampleMillis: Long = 0L,
)

/**
 * Stable functional interface for the page-load guard used by `PaginatorPrefetchController`
 * (the offset / page-number variant).
 *
 * Prefer this over a raw lambda when storing the guard in a hoisted state holder — `fun
 * interface` instances are treated as stable by the Compose compiler, while plain lambdas are
 * not, which can cause spurious recompositions of consumers that capture the guard.
 */
fun interface PageLoadGuard<T> {
    operator fun invoke(page: Int, state: PageState<T>?): Boolean

    companion object {
        private val ALLOW_ALL: PageLoadGuard<Any?> = PageLoadGuard { _, _ -> true }

        /** Guard that admits every page load. Cached singleton — safe to reuse. */
        @Suppress("UNCHECKED_CAST")
        fun <T> allowAll(): PageLoadGuard<T> = ALLOW_ALL as PageLoadGuard<T>
    }
}
