package com.jamal_aliev.paginator.core.navigation

/**
 * A domain event describing a navigation that just occurred on a paginator.
 *
 * Emitted via `Paginator.navigationEvents` / `CursorPaginator.navigationEvents`. It reports a
 * **fact** — "a jump landed on X" — not a UI command: the headless paginator engine (also used by
 * RecyclerView bindings, pure-Kotlin code, and tests) stays UI-agnostic, and each consumer decides
 * how to react. The Compose turnkeys, for example, scroll the list to the target's page; a
 * RecyclerView could `scrollToPosition`; analytics could just log it.
 *
 * The type is a `sealed interface` so new event kinds (e.g. restart, next/previous, refresh) can be
 * added later and handled exhaustively with `when`.
 *
 * @param B the strategy's positional target type — `BookmarkInt` for the offset strategy,
 *   `CursorBookmark<K>` for the cursor strategy.
 */
sealed interface PaginatorNavigationEvent<out B> {

    /**
     * A jump-type navigation (`jump` / `jumpForward` / `jumpBack`, including a cache hit) landed on
     * [target]. UI layers typically scroll the list to the target's page in response.
     */
    data class Jumped<out B>(val target: B) : PaginatorNavigationEvent<B>
}
