package com.jamal_aliev.paginator.logger

/**
 * Logical components of the paginator that produce log events.
 *
 * Use these to filter logs to only the subsystem you are debugging.
 */
enum class LogComponent {

    /** Page navigation: jump, goNextPage, goPreviousPage, loadOrGetPageState. */
    NAVIGATION,

    /** Cache eviction events from cache strategies (LRU, FIFO, TTL, etc.). */
    CACHE,

    /** Element-level CRUD: setElement, removeElement, addAllElements, removeState. */
    MUTATION,

    /** Lifecycle events: release, restart, refresh, refreshDirtyPages. */
    LIFECYCLE
}
