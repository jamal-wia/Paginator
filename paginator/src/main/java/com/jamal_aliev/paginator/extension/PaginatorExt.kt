package com.jamal_aliev.paginator.extension

import com.jamal_aliev.paginator.MutablePaginator
import com.jamal_aliev.paginator.page.PageState

/**
 * Iterates through each PageState in the paginator and performs the given action on it.
 *
 * @param action The action to be performed on each PageState.
 */
inline fun <T> MutablePaginator<T>.foreEach(
    action: (PageState<T>) -> Unit
) {
    for (state in this) {
        action(state.value)
    }
}

/**
 * Iterates safely over all `PageState` items contained in this `MutablePaginator`,
 * allowing full control over how iteration starts, progresses, and stops.
 *
 * This function provides customizable strategies for:
 * - selecting the initial index,
 * - determining how the index changes on each step,
 * - defining the conditions under which iteration continues.
 *
 * This enables flexible traversal patterns such as forward or backward iteration,
 * skipping elements, conditional early termination, or implementing custom stepping logic.
 *
 * Iteration proceeds as long as:
 * - the index stays within the bounds of the state list, and
 * - `actionAndContinue` returns `true`.
 *
 * @param initialIndex A function that determines the starting index for iteration.
 * Defaults to `0` (beginning of the list).
 *
 * @param nextIndex A function that defines how to compute the next index value.
 * Defaults to incrementing by 1.
 *
 * @param actionAndContinue A callback invoked for each visited `PageState`.
 * Receives the full list of states, the current index, and the current state.
 * Returns `true` to continue iterating, or `false` to stop.
 *
 * @return The original list of page states (`pageStates`) after iteration completes.
 */
inline fun <T> MutablePaginator<T>.smartForEach(
    initialIndex: (list: List<PageState<T>>) -> Int = { 0 },
    nextIndex: (index: Int) -> Int = { it + 1 },
    actionAndContinue: (
        states: List<PageState<T>>,
        index: Int,
        currentState: PageState<T>
    ) -> Boolean
): List<PageState<T>> {
    val states: List<PageState<T>> = this.pageStates
    var index = initialIndex.invoke(states)
    while (0 <= index && index < states.size) {
        val currentState: PageState<T> = states[index]
        if (!actionAndContinue.invoke(states, index, currentState)) {
            break
        }
        index = nextIndex.invoke(index)
    }
    return states
}

/**
 * Finds the index of the first element matching the given predicate in the paginator.
 *
 * @param predicate The predicate to match elements.
 * @return A pair containing the page number and index of the first matching element, or null if none found.
 */
inline fun <T> MutablePaginator<T>.indexOfFirst(
    predicate: (T) -> Boolean
): Pair<UInt, Int>? {
    for (page in pageStates) {
        val result = page.data.indexOfFirst(predicate)
        if (result != -1) return page.page to result
    }
    return null
}

/**
 * Finds the index of the first element matching the given predicate in the specified page.
 *
 * @param page The page number to search in.
 * @param predicate The predicate to match elements.
 * @return A pair containing the page number and index of the first matching element, or null if none found.
 * @throws IllegalArgumentException if the page is not found.
 */
inline fun <T> MutablePaginator<T>.indexOfFirst(
    page: UInt,
    predicate: (T) -> Boolean
): Pair<UInt, Int>? {
    val pageState = checkNotNull(getPageState(page)) { "Page $page is not found" }
    for ((i, e) in pageState.data.withIndex()) {
        if (predicate(e)) {
            return page to i
        }
    }
    return null
}

/**
 * Finds the index of the last element matching the given predicate in the paginator.
 *
 * @param predicate The predicate to match elements.
 * @return A pair containing the page number and index of the last matching element, or null if none found.
 */
inline fun <T> MutablePaginator<T>.indexOfLast(
    predicate: (T) -> Boolean
): Pair<UInt, Int>? {
    for (page in pageStates.reversed()) {
        val result = page.data.indexOfLast(predicate)
        if (result != -1) return page.page to result
    }
    return null
}

/**
 * Finds the index of the last element matching the given predicate in the specified page.
 *
 * @param page The page number to search in.
 * @param predicate The predicate to match elements.
 * @return A pair containing the page number and index of the last matching element, or null if none found.
 * @throws IllegalArgumentException if the page is not found.
 */
inline fun <T> MutablePaginator<T>.indexOfLast(
    page: UInt,
    predicate: (T) -> Boolean
): Pair<UInt, Int>? {
    val pageState = checkNotNull(getPageState(page)) { "Page $page is not found" }
    for ((index, element) in pageState.data.reversed().withIndex()) {
        if (predicate(element)) {
            return page to index
        }
    }
    return null
}
