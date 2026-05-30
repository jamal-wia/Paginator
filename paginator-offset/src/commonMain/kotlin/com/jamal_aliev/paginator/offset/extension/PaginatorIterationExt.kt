package com.jamal_aliev.paginator.offset.extension

import com.jamal_aliev.paginator.offset.Paginator
import com.jamal_aliev.paginator.offset.page.OffsetPageState

/**
 * Iterates through each [OffsetPageState] in the paginator and performs the given [action] on it.
 *
 * @param action The action to be performed on each [OffsetPageState].
 */
inline fun <T> Paginator<T>.forEach(
    action: (OffsetPageState<T>) -> Unit,
) {
    for (state in this) {
        action(state)
    }
}

/**
 * Iterates safely over all [OffsetPageState] items contained in this [Paginator],
 * allowing full control over how iteration starts, progresses, and stops.
 *
 * This function provides customizable strategies for:
 * - selecting the initial index,
 * - determining how the index changes on each step,
 * - defining the conditions under which iteration continues.
 *
 * @param initialIndex A function that determines the starting index for iteration.
 *   Defaults to `0` (beginning of the list).
 * @param step A function that defines how to compute the next index value.
 *   Defaults to incrementing by 1.
 * @param actionAndContinue A callback invoked for each visited [OffsetPageState].
 *   Receives the full list of states, the current index, and the current state.
 *   Returns `true` to continue iterating, or `false` to stop.
 *
 * @return The original list of page states after iteration completes.
 */
inline fun <T> Paginator<T>.smartForEach(
    initialIndex: (list: List<OffsetPageState<T>>) -> Int = { 0 },
    step: (index: Int) -> Int = { it + 1 },
    actionAndContinue: (
        states: List<OffsetPageState<T>>,
        index: Int,
        currentState: OffsetPageState<T>,
    ) -> Boolean,
): List<OffsetPageState<T>> {
    val states: List<OffsetPageState<T>> = this.core.states
    var index = initialIndex.invoke(states)
    while (0 <= index && index < states.size) {
        val currentState: OffsetPageState<T> = states[index]
        if (!actionAndContinue.invoke(states, index, currentState)) {
            break
        }
        index = step.invoke(index)
    }
    return states
}

/**
 * Walks forward from the given [pivotState] through consecutive pages
 * that satisfy the [predicate], and returns the last page in that chain.
 *
 * @param pivotState The initial page from which forward traversal begins.
 * @param predicate A condition that each traversed page must satisfy.
 * @return The last [OffsetPageState] encountered while moving forward that still satisfies [predicate],
 *   or `null` if the starting page is null or fails the predicate.
 */
inline fun <T> Paginator<T>.walkForwardWhile(
    pivotState: OffsetPageState<T>?,
    predicate: (OffsetPageState<T>) -> Boolean = { true },
): OffsetPageState<T>? {
    return core.walkWhile(
        pivotState = pivotState,
        next = { currentPage: Int -> currentPage + 1 },
        predicate = { state: OffsetPageState<T> -> predicate.invoke(state) },
    )
}

/**
 * Walks backward from the given [pivotState], following consecutive previous pages,
 * and returns the first page in that backward chain that satisfies the [predicate].
 *
 * @param pivotState The initial page from which backward traversal begins.
 * @param predicate A condition that each traversed page must satisfy.
 * @return The last [OffsetPageState] encountered while moving backward that still satisfies [predicate],
 *   or `null` if the starting page is null or fails the predicate.
 */
inline fun <T> Paginator<T>.walkBackwardWhile(
    pivotState: OffsetPageState<T>?,
    predicate: (OffsetPageState<T>) -> Boolean = { true },
): OffsetPageState<T>? {
    return core.walkWhile(
        pivotState = pivotState,
        next = { currentPage: Int -> currentPage - 1 },
        predicate = { state: OffsetPageState<T> -> predicate.invoke(state) },
    )
}
