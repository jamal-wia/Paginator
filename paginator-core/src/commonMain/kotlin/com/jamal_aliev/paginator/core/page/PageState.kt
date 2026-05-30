package com.jamal_aliev.paginator.core.page

import com.jamal_aliev.paginator.core.load.Metadata
import kotlinx.atomicfu.atomic

/**
 * Strategy-agnostic root of the page-state model.
 *
 * A `PageState` describes the load status and payload of a single logical page. It deliberately
 * carries **no positional key**: the offset strategy keys pages by a numeric `page` (see
 * `OffsetPageState` in `paginator-offset`) while the cursor strategy keys them by a typed
 * bookmark (see `CursorPageState` in `paginator-cursor`). Code that does not care which strategy
 * produced a page — UI mapping ([PaginatorUiState]), predicates ([com.jamal_aliev.paginator.core.extension.isSuccessState]
 * and friends), caches, prefetch guards — works against this interface and the three status
 * markers nested below.
 *
 * Status is expressed through the marker sub-interfaces [ErrorState], [ProgressState] and
 * [SuccessState] so that the concrete classes of either strategy can opt into a status without
 * sharing a class hierarchy. Match on these markers (e.g. `is PageState.SuccessState`) rather
 * than on a strategy's concrete class when you want to stay strategy-agnostic.
 *
 * @param E the element type of the page payload.
 */
interface PageState<out E> {

    /** The page payload. A [SuccessState] with an empty list represents an "empty" page. */
    val data: List<E>

    /** Optional API-level metadata propagated from the load result (cursors, totals, ETags…). */
    val metadata: Metadata?

    /** Per-instance identity used for equality/diffing. Preserved across `copy`. */
    val id: Long

    /**
     * Returns a page state of the **same status and strategy** as this one, but carrying [data]
     * in place of the current payload. `metadata` and [id] are preserved.
     *
     * Used by strategy-agnostic transforms (e.g. interweaving) that need to rebuild a page with
     * a different element type without knowing which concrete strategy produced it.
     */
    fun <R> withData(data: List<R>): PageState<R>

    /** Marker for a failed load. Carries the [exception] that caused the failure. */
    interface ErrorState<out E> : PageState<E> {
        val exception: Exception
    }

    /** Marker for an in-flight load. */
    interface ProgressState<out E> : PageState<E>

    /** Marker for a successful load. An empty [data] denotes an "empty" page. */
    interface SuccessState<out E> : PageState<E>

    companion object {
        private val ids = atomic(0L)

        /** Allocates a fresh, process-unique [id] for a new page-state instance. */
        fun nextId(): Long = ids.incrementAndGet()
    }
}
