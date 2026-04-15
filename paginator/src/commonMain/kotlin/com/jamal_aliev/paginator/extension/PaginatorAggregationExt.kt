package com.jamal_aliev.paginator.extension

import com.jamal_aliev.paginator.Paginator
import com.jamal_aliev.paginator.page.PageState

// ──────────────────────────────────────────────────────────────────────────────
//  Search
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Returns the first element across all cached pages that satisfies [predicate],
 * or `null` if no element matches.
 *
 * Iterates pages in ascending page order. Equivalent to [getElement] but follows
 * standard Kotlin collection naming.
 */
inline fun <T> Paginator<T>.find(predicate: (T) -> Boolean): T? = getElement(predicate)

/**
 * Returns the first element across all cached pages that satisfies [predicate],
 * or `null` if no element matches.
 *
 * The historical name kept for backwards compatibility; prefer [find] in new code.
 */
inline fun <T> Paginator<T>.getElement(
    predicate: (T) -> Boolean,
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

/**
 * Finds the index of the first element matching the given [predicate] in the paginator.
 *
 * @return A pair `(page, index)` of the first matching element, or `null` if none found.
 */
inline fun <T> Paginator<T>.indexOfFirst(
    predicate: (T) -> Boolean,
): Pair<Int, Int>? {
    for (page in core.states) {
        val result = page.data.indexOfFirst(predicate)
        if (result != -1) return page.page to result
    }
    return null
}

/**
 * Finds the index of the first element matching the given [predicate] in the specified [page].
 *
 * @param page The page number to search in.
 * @return A pair `(page, index)` of the first matching element, or `null` if none found.
 * @throws IllegalArgumentException If [page] is not in the cache.
 */
inline fun <T> Paginator<T>.indexOfFirst(
    page: Int,
    predicate: (T) -> Boolean,
): Pair<Int, Int>? {
    val pageState = checkNotNull(cache.getStateOf(page)) { "Page $page is not found" }
    for ((i, e) in pageState.data.withIndex()) {
        if (predicate(e)) {
            return page to i
        }
    }
    return null
}

/**
 * Finds the index of the last element matching the given [predicate] in the paginator.
 *
 * @return A pair `(page, index)` of the last matching element, or `null` if none found.
 */
inline fun <T> Paginator<T>.indexOfLast(
    predicate: (T) -> Boolean,
): Pair<Int, Int>? {
    for (page in core.states.asReversed()) {
        val result = page.data.indexOfLast(predicate)
        if (result != -1) return page.page to result
    }
    return null
}

/**
 * Finds the index of the last element matching the given [predicate] in the specified [page].
 *
 * @param page The page number to search in.
 * @return A pair `(page, index)` of the last matching element, or `null` if none found.
 * @throws IllegalArgumentException If [page] is not in the cache.
 */
inline fun <T> Paginator<T>.indexOfLast(
    page: Int,
    predicate: (T) -> Boolean,
): Pair<Int, Int>? {
    val pageState = checkNotNull(cache.getStateOf(page)) { "Page $page is not found" }
    val result = pageState.data.indexOfLast(predicate)
    if (result != -1) return page to result
    return null
}

/**
 * Returns the last element across all cached pages that satisfies [predicate],
 * or `null` if no element matches.
 *
 * Iterates pages in descending page order, scanning each page right-to-left.
 */
inline fun <T> Paginator<T>.findLast(predicate: (T) -> Boolean): T? {
    val states: List<PageState<T>> = core.states
    for (i in states.indices.reversed()) {
        val data: List<T> = states[i].data
        for (j in data.indices.reversed()) {
            val element: T = data[j]
            if (predicate(element)) return element
        }
    }
    return null
}

// ──────────────────────────────────────────────────────────────────────────────
//  Predicates
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Returns `true` if at least one element across all cached pages satisfies [predicate].
 */
inline fun <T> Paginator<T>.any(predicate: (T) -> Boolean): Boolean {
    for (page in core.states) {
        for (element in page.data) {
            if (predicate(element)) return true
        }
    }
    return false
}

/**
 * Returns `true` if no element across all cached pages satisfies [predicate].
 */
inline fun <T> Paginator<T>.none(predicate: (T) -> Boolean): Boolean = !any(predicate)

/**
 * Returns `true` if every element across all cached pages satisfies [predicate].
 *
 * Vacuously `true` when no pages are loaded.
 */
inline fun <T> Paginator<T>.all(predicate: (T) -> Boolean): Boolean {
    for (page in core.states) {
        for (element in page.data) {
            if (!predicate(element)) return false
        }
    }
    return true
}

/**
 * Counts elements across all cached pages that satisfy [predicate].
 *
 * With the default predicate, returns the total number of loaded elements.
 */
inline fun <T> Paginator<T>.count(predicate: (T) -> Boolean = { true }): Int {
    var total = 0
    for (page in core.states) {
        for (element in page.data) {
            if (predicate(element)) total++
        }
    }
    return total
}

// ──────────────────────────────────────────────────────────────────────────────
//  Positional access
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Returns the first loaded element across all cached pages, or `null` if no
 * non-empty page exists.
 */
fun <T> Paginator<T>.firstOrNull(): T? {
    for (page in core.states) {
        if (page.data.isNotEmpty()) return page.data.first()
    }
    return null
}

/**
 * Returns the last loaded element across all cached pages, or `null` if no
 * non-empty page exists.
 */
fun <T> Paginator<T>.lastOrNull(): T? {
    val states: List<PageState<T>> = core.states
    for (i in states.indices.reversed()) {
        val data: List<T> = states[i].data
        if (data.isNotEmpty()) return data.last()
    }
    return null
}

/**
 * Returns the element at the given **flat** [globalIndex] across all cached pages,
 * or `null` if [globalIndex] is out of bounds.
 *
 * Pages are concatenated in ascending page order, so `globalIndex = 0` is the first
 * element of the lowest-numbered cached page. Negative indices return `null`.
 */
fun <T> Paginator<T>.elementAtOrNull(globalIndex: Int): T? {
    if (globalIndex < 0) return null
    var skipped = 0
    for (page in core.states) {
        val size = page.data.size
        if (globalIndex < skipped + size) return page.data[globalIndex - skipped]
        skipped += size
    }
    return null
}

// ──────────────────────────────────────────────────────────────────────────────
//  Transformation
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Returns a single flat list of every element across all cached pages, in page order.
 *
 * Useful as the input to a UI list adapter (e.g. `LazyColumn(items = paginator.flatten())`).
 */
fun <T> Paginator<T>.flatten(): List<T> {
    val states: List<PageState<T>> = core.states
    var totalSize = 0
    for (page in states) totalSize += page.data.size
    val result: ArrayList<T> = ArrayList(totalSize)
    for (page in states) result.addAll(page.data)
    return result
}

/**
 * Applies [transform] to every element across all cached pages and flattens the
 * resulting iterables into a single list.
 */
inline fun <T, R> Paginator<T>.flatMap(transform: (T) -> Iterable<R>): List<R> {
    val result: MutableList<R> = mutableListOf()
    for (page in core.states) {
        for (element in page.data) {
            result.addAll(transform(element))
        }
    }
    return result
}

/**
 * Maps each cached [PageState] through [transform] and returns the results in page order.
 *
 * Use this when you need page-level metadata (page number, metadata, error state)
 * rather than the elements themselves.
 */
inline fun <T, R> Paginator<T>.mapPages(transform: (PageState<T>) -> R): List<R> {
    return core.states.map(transform)
}

/**
 * Iterates over every loaded element with its global flat index across all cached pages.
 *
 * The index increments contiguously across page boundaries.
 */
inline fun <T> Paginator<T>.forEachIndexed(
    action: (globalIndex: Int, element: T) -> Unit,
) {
    var index = 0
    for (page in core.states) {
        for (element in page.data) {
            action(index, element)
            index++
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
//  Operators / properties
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Returns `true` if [element] is present anywhere across the cached pages.
 *
 * Enables the natural `element in paginator` syntax.
 */
operator fun <T> Paginator<T>.contains(element: T): Boolean {
    for (page in core.states) {
        if (element in page.data) return true
    }
    return false
}

/**
 * Total number of elements currently loaded across all cached pages.
 *
 * Convenience alias for `count()` when no predicate is needed; offered as a
 * property to read fluently from non-reactive call sites.
 */
val <T> Paginator<T>.loadedItemsCount: Int
    get() = count()

/**
 * Number of pages currently in the cache.
 *
 * Convenience alias for `core.size` exposed at the [Paginator] level.
 */
val <T> Paginator<T>.loadedPagesCount: Int
    get() = core.size
