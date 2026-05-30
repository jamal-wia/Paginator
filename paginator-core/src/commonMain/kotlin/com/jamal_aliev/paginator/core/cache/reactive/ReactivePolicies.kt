package com.jamal_aliev.paginator.core.cache.reactive
/**
 * How the paginator handles the gap between the most recent page load and
 * the first event observed via [PaginatorReactiveCache.changes].
 *
 * If the source mutates between those two moments, events fired in the gap
 * are otherwise lost. The default trades one extra round of source reads for
 * correctness; opt out only when the adapter explicitly buffers or replays
 * events from the moment of subscribe.
 */
enum class InitialSyncPolicy {

    /**
     * Issue `refreshAll()` immediately after subscribe so every cached page
     * is re-fetched from `load`. Costs one extra load per cached page on
     * startup; eliminates the chance of missed updates.
     *
     * **No-op when the cache is empty.** If `observe()` is called before any
     * page has been loaded, there is nothing to reconcile — the upcoming
     * first `load()` will already see the latest source state. The
     * reconciliation runs only when `cache.pages` is non-empty at subscribe
     * time.
     *
     * Default — safe for the common case (Room/SQLDelight DAOs).
     */
    RefreshAll,

    /**
     * Skip the reconciliation refresh. Use only when the adapter's
     * [PaginatorReactiveCache.changes] is guaranteed to deliver every event
     * since the last source mutation — typically a `MutableSharedFlow` with
     * `replay = Int.MAX_VALUE` or an explicit "snapshot then subscribe"
     * primitive.
     */
    None,
}

/**
 * How the paginator reacts to a [ReactiveEvent.Updated], [ReactiveEvent.Removed],
 * or [ReactiveEvent.Moved] (or an `*Identity` insert anchor) whose identity
 * is not present in any cached page.
 *
 * The common cause is that the affected item lives outside the paginator's
 * currently loaded window — either above the cache's `startContextPage`
 * or below its `endContextPage`, or
 * on a page that has been evicted.
 */
enum class UnknownItemPolicy {

    /**
     * Silently ignore the event. Correct when the affected item is outside
     * the visible window — the UI does not need to react.
     *
     * Default.
     */
    Drop,

    /**
     * Escalate to `refreshAll()`. Use when the source can hand the paginator
     * an event for an item that *should* be visible but is not — typically a
     * sign that the cache has drifted from the source and needs a full
     * reconciliation.
     */
    RefreshAll,
}
