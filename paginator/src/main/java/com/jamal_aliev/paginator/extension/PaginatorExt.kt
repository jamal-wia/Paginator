package com.jamal_aliev.paginator.extension

import com.jamal_aliev.paginator.MutablePaginator
import com.jamal_aliev.paginator.exception.LoadGuardedException
import com.jamal_aliev.paginator.exception.LockedException.RefreshWasLockedException
import com.jamal_aliev.paginator.initializer.InitializerEmptyPage
import com.jamal_aliev.paginator.initializer.InitializerErrorPage
import com.jamal_aliev.paginator.initializer.InitializerProgressPage
import com.jamal_aliev.paginator.initializer.InitializerSuccessPage
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
 * @param step A function that defines how to compute the next index value.
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
    step: (index: Int) -> Int = { it + 1 },
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
        index = step.invoke(index)
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
    val pageState = checkNotNull(getStateOf(page)) { "Page $page is not found" }
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
    val pageState = checkNotNull(getStateOf(page)) { "Page $page is not found" }
    for ((index, element) in pageState.data.reversed().withIndex()) {
        if (predicate(element)) {
            return page to index
        }
    }
    return null
}

/**
 * Walks forward from the given [pivotState] through consecutive pages
 * that satisfy the [predicate], and returns the last page in that chain.
 *
 * This function:
 * - Starts at [pivotState].
 * - Moves to the next page (`page + 1`) as long as the page exists in the cache
 *   and satisfies the [predicate].
 * - Stops at the last consecutive page that satisfies the predicate.
 *
 * @param pivotState The initial page from which forward traversal begins.
 * If null or does not satisfy [predicate], the function returns null.
 *
 * @param predicate A condition that each traversed page must satisfy.
 * Defaults to always true, allowing traversal through all consecutive next pages.
 *
 * @return The last PageState encountered while moving forward that still satisfies [predicate],
 * or null if the starting page is null or fails the predicate.
 */
inline fun <T> MutablePaginator<T>.walkForwardWhile(
    pivotState: PageState<T>?,
    predicate: (PageState<T>) -> Boolean = { true }
): PageState<T>? {
    return walkWhile(
        pivotState = pivotState,
        next = { currentPage: UInt ->
            return@walkWhile currentPage + 1u
        },
        predicate = { state: PageState<T> ->
            return@walkWhile predicate.invoke(state)
        }
    )
}

/**
 * Walks backward from the given [pivotState], following consecutive previous pages,
 * and returns the first page in that backward chain that does *not* satisfy the [predicate].
 *
 * In other words, this function:
 * - Starts at [pivotState].
 * - Moves to the previous page (`page - 1`) as long as:
 *   - The page exists in the cache, and
 *   - The page satisfies the [predicate].
 * - Stops at the last page that satisfied the predicate.
 *
 * This effectively finds the earliest consecutive page before [pivotState]
 * that still matches [predicate].
 *
 * @param pivotState The initial page from which backward traversal begins.
 * If null or does not satisfy [predicate], the function returns null.
 *
 * @param predicate A condition that each traversed page must satisfy.
 * Defaults to always true, allowing traversal through all consecutive previous pages.
 *
 * @return The last PageState encountered while moving backward that still satisfies [predicate],
 * or null if the starting page is null or fails the predicate.
 */
inline fun <T> MutablePaginator<T>.walkBackwardWhile(
    pivotState: PageState<T>?,
    predicate: (PageState<T>) -> Boolean = { true }
): PageState<T>? {
    return walkWhile(
        pivotState = pivotState,
        next = { currentPage: UInt ->
            return@walkWhile currentPage - 1u
        },
        predicate = { state: PageState<T> ->
            return@walkWhile predicate.invoke(state)
        }
    )
}

fun <T> MutablePaginator<T>.removeElement(predicate: (T) -> Boolean): T? {
    for (page in pages) {
        val removed: T? = removeElement(page, predicate)
        if (removed != null) {
            return removed
        }
    }
    return null
}

fun <T> MutablePaginator<T>.removeElement(page: UInt, predicate: (T) -> Boolean): T? {
    val state: PageState<T>? = getStateOf(page)
    state ?: return null
    for ((index, element) in state.data.withIndex()) {
        if (predicate(element)) {
            return removeElement(
                page = page,
                index = index
            )
        }
    }
    return null
}

fun <T> MutablePaginator<T>.addElement(
    element: T,
    silently: Boolean = false,
    initSuccessPageState: ((page: UInt, data: List<T>) -> PageState<T>)? = null
): Boolean {
    val lastPage: UInt = pages.lastOrNull() ?: return false
    val lastIndex: Int = getStateOf(lastPage)?.data?.lastIndex ?: return false
    addElement(element, lastPage, lastIndex, silently, initSuccessPageState)
    return true
}

fun <T> MutablePaginator<T>.addElement(
    element: T,
    page: UInt,
    index: Int,
    silently: Boolean = false,
    initPageState: ((page: UInt, data: List<T>) -> PageState<T>)? = null
) {
    return addAllElements(
        elements = listOf(element),
        targetPage = page,
        index = index,
        silently = silently,
        initPageState = initPageState
    )
}

inline fun <T> MutablePaginator<T>.getElement(
    predicate: (T) -> Boolean
): T? {
    this.smartForEach { _, _, pageState: PageState<T> ->
        for (element in pageState.data) {
            if (predicate(element)) {
                return element
            }
        }
        return@smartForEach true
    }
    return null
}

inline fun <T> MutablePaginator<T>.setElement(
    element: T,
    silently: Boolean = false,
    predicate: (T) -> Boolean
) {
    this.smartForEach { _, _, pageState ->
        var index = 0
        while (index < pageState.data.size) {
            if (predicate(pageState.data[index++])) {
                setElement(
                    element = element,
                    page = pageState.page,
                    index = index,
                    silently = silently
                )
                return
            }
        }
        return@smartForEach true
    }
}

/**
 * Refreshes **all** currently cached pages by reloading them from the source in parallel.
 *
 * This is a convenience wrapper around [MutablePaginator.refresh] that passes all
 * cached page numbers automatically.
 *
 * @param loadingSilently If `true`, the snapshot will **not** be emitted after setting
 *   pages to progress state.
 * @param finalSilently If `true`, the snapshot will **not** be emitted after all pages
 *   finish loading.
 * @param loadGuard A guard callback invoked with `(page, currentState)` for **each** page
 *   **before** any loading begins. Return `true` to proceed, or `false` to abort the
 *   entire refresh. When `false` is returned, [LoadGuardedException] is thrown.
 *   Defaults to always allowing the load.
 * @param enableCacheFlow If `true`, the full cache flow is also updated.
 * @param initProgressState Factory for creating progress page instances during loading.
 * @param initEmptyState Factory for creating empty page instances.
 * @param initSuccessState Factory for creating success page instances.
 * @param initErrorState Factory for creating error page instances.
 * @throws RefreshWasLockedException If refresh is locked.
 * @throws LoadGuardedException If [loadGuard] returns `false` for any page.
 */
suspend fun <T> MutablePaginator<T>.refreshAll(
    loadingSilently: Boolean = false,
    finalSilently: Boolean = false,
    loadGuard: (page: UInt, state: PageState<T>?) -> Boolean = { _, _ -> true },
    enableCacheFlow: Boolean = this.enableCacheFlow,
    initProgressState: InitializerProgressPage<T> = this.initializerProgressPage,
    initEmptyState: InitializerEmptyPage<T> = this.initializerEmptyPage,
    initSuccessState: InitializerSuccessPage<T> = this.initializerSuccessPage,
    initErrorState: InitializerErrorPage<T> = this.initializerErrorPage
) {
    if (lockRefresh) throw RefreshWasLockedException()
    return refresh(
        pages = this.pages,
        loadingSilently = loadingSilently,
        finalSilently = finalSilently,
        loadGuard = loadGuard,
        enableCacheFlow = enableCacheFlow,
        initProgressState = initProgressState,
        initEmptyState = initEmptyState,
        initSuccessState = initSuccessState,
        initErrorState = initErrorState
    )
}


