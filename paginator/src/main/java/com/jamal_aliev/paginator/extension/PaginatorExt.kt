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
 * Iterates through each PageState in the paginator safely and performs the given action on it.
 *
 * @param action The action to be performed on each PageState.
 */
inline fun <T> MutablePaginator<T>.smartForEach(
    startIndex: (list: List<PageState<T>>) -> Int = { 0 },
    step: (index: Int) -> Int = { it + 1 },
    action: (list: List<PageState<T>>, index: Int, pageState: PageState<T>) -> Boolean
): List<PageState<T>> {
    val pageStateList = this.pageStates
    var index = startIndex(pageStateList)
    while (index in pageStateList.indices) {
        if (!action(pageStateList, index, pageStateList[index])) break
        index = step(index)
    }
    return pageStateList
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
