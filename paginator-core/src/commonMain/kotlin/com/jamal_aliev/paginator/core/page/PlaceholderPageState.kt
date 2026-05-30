package com.jamal_aliev.paginator.core.page

/**
 * Strategy-agnostic marker for a page state that also exposes a parallel list of [placeholders]
 * (e.g. skeleton/shimmer items rendered while real data loads).
 *
 * The concrete placeholder classes live in the strategy modules — see
 * `OffsetPlaceholder{Error,Progress,Success}Page` in `paginator-offset` and
 * `CursorPlaceholder{Error,Progress,Success}Page` in `paginator-cursor` — each extending its
 * strategy's concrete [PageState] class and implementing this marker. Match on
 * `is PlaceholderPageState<*>` when you only need access to [placeholders].
 *
 * @param R the placeholder element type (may differ from the page's data element type).
 */
interface PlaceholderPageState<R> {

    /** The placeholder items associated with this page state. */
    val placeholders: List<R>
}
