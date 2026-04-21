package com.jamal_aliev.paginator.interweave

/**
 * A single row in a list produced by interweaving meta-elements (decorations) into a
 * plain stream of data.
 *
 * Use [WovenEntry] as the output row type of [Flow.interweave] /
 * [PaginatorUiState.interweave]: every position in the resulting list is either a
 * [Data] entry wrapping an original item, or an [Decoration] entry carrying a
 * meta-element produced by an [Interweaver] at the boundary between two items
 * (or at the leading / trailing edge of the list).
 *
 * Rendering contract:
 * - In an adapter (`RecyclerView`, Compose `items`, etc.) dispatch on the
 *   `is`-check: `Data` → bind a regular item view, `Decoration` → bind a header /
 *   divider / banner view.
 * - [wovenKey] is intended to be fed into `DiffUtil` / `LazyColumn`'s `key = { it.wovenKey }`
 *   to keep animations stable across re-emissions.
 */
sealed interface WovenEntry<out T, out I> {

    /** Stable identifier used by UI diffing. Must be unique within a single emitted list. */
    val wovenKey: Any

    /**
     * A data row — wraps an original item of type [T].
     *
     * @property value The item passed through by the interweaver.
     * @property wovenKey Produced by [Interweaver.itemKey].
     */
    data class Data<T>(
        val value: T,
        override val wovenKey: Any,
    ) : WovenEntry<T, Nothing>

    /**
     * An decoration row — carries a meta-element of type [I] inserted between data rows
     * (or at the leading / trailing edge of the list).
     *
     * @property value The meta-element produced by [Interweaver.between].
     * @property wovenKey Produced by [Interweaver.decorationKey].
     */
    data class Decoration<I>(
        val value: I,
        override val wovenKey: Any,
    ) : WovenEntry<Nothing, I>
}
