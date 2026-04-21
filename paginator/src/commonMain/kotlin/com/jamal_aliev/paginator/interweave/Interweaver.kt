package com.jamal_aliev.paginator.interweave

/**
 * Configures how a plain list of data items is *interwoven* with meta-elements
 * ("decorations") — section headers, date dividers, "unread since" markers,
 * banners, etc.
 *
 * An [Interweaver] is a pure, stateless strategy. It is consulted at every
 * boundary between two adjacent items in an emitted list (and optionally at the
 * leading / trailing edges) and decides whether a meta-element should be
 * inserted there.
 *
 * Typical flow: attach the strategy via [com.jamal_aliev.paginator.interweave.interweave]
 * to a `Flow<PaginatorUiState<T>>` so the stream emits
 * `PaginatorUiState<WovenEntry<T, I>>`.
 *
 * Implementations must be pure: [between], [itemKey] and [decorationKey] are
 * called repeatedly on every emission, and any side effect will run on every
 * re-emit of the same snapshot.
 */
interface Interweaver<T, I> {

    /**
     * Returns a stable identifier for [item] suitable for UI diffing.
     *
     * Called once per item per emission. The value must be deterministic — the
     * same item should map to the same key across re-emissions, otherwise
     * `DiffUtil` / `LazyColumn` will see spurious changes.
     */
    fun itemKey(item: T): Any

    /**
     * Returns a stable identifier for an [decoration] produced by [between] at
     * the boundary between [prev] and [next].
     *
     * [prev] is `null` for the leading edge; [next] is `null` for the trailing
     * edge; both are non-null for a between-items decoration. The key must be
     * deterministic on ([prev], [next]) so that the same decoration receives the
     * same key across re-emissions.
     */
    fun decorationKey(decoration: I, prev: T?, next: T?): Any

    /**
     * Decides whether a meta-element should be inserted at the boundary
     * between [prev] and [next].
     *
     * - [prev] is `null`, [next] non-null → leading edge; consulted only when
     *   [emitLeading] is `true`.
     * - [prev] non-null, [next] is `null` → trailing edge; consulted only when
     *   [emitTrailing] is `true`.
     * - Both non-null → regular between-items boundary.
     *
     * Return `null` when no decoration is needed at this boundary.
     */
    fun between(prev: T?, next: T?): I?

    /** When `true`, a decoration may be inserted before the first data item. */
    val emitLeading: Boolean get() = false

    /** When `true`, a decoration may be inserted after the last data item. */
    val emitTrailing: Boolean get() = false
}
