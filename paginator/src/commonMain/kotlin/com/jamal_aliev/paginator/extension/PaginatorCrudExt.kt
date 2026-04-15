package com.jamal_aliev.paginator.extension

import com.jamal_aliev.paginator.MutablePaginator
import com.jamal_aliev.paginator.page.PageState

// ──────────────────────────────────────────────────────────────────────────────
//  Single-element add / set / remove
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Appends [element] to the end of the **last** cached page.
 *
 * Returns `false` if no last page exists (cache is empty) and the element could
 * not be appended.
 *
 * **L2 note:** modifies L1 only. Use [MutablePaginator.flush] or wrap the call
 * in [MutablePaginator.transaction] to persist.
 */
fun <T> MutablePaginator<T>.addElement(
    element: T,
    silently: Boolean = false,
    initSuccessPageState: ((page: Int, data: List<T>) -> PageState<T>)? = null,
): Boolean {
    val lastPage: Int = core.lastPage() ?: return false
    val lastPageData = cache.getStateOf(lastPage)?.data ?: return false
    addElement(element, lastPage, lastPageData.size, silently, initSuccessPageState)
    return true
}

/**
 * Inserts [element] into [page] at [index].
 *
 * Convenience wrapper around [MutablePaginator.addAllElements] for a single element.
 *
 * **L2 note:** modifies L1 only. Use [MutablePaginator.flush] or wrap the call
 * in [MutablePaginator.transaction] to persist.
 */
fun <T> MutablePaginator<T>.addElement(
    element: T,
    page: Int,
    index: Int,
    silently: Boolean = false,
    initPageState: ((page: Int, data: List<T>) -> PageState<T>)? = null,
) {
    return addAllElements(
        elements = listOf(element),
        targetPage = page,
        index = index,
        silently = silently,
        initPageState = initPageState,
    )
}

/**
 * Replaces the **first** element matching [predicate] with [element].
 *
 * Stops after the first replacement. For a "replace every match" semantics
 * see [updateWhere].
 *
 * **L2 note:** modifies L1 only. Use [MutablePaginator.flush] or wrap the call
 * in [MutablePaginator.transaction] to persist.
 */
inline fun <T> MutablePaginator<T>.setElement(
    element: T,
    silently: Boolean = false,
    predicate: (T) -> Boolean,
) {
    this.smartForEach { _, _, pageState ->
        var index = 0
        while (index < pageState.data.size) {
            if (predicate(pageState.data[index])) {
                setElement(
                    element = element,
                    page = pageState.page,
                    index = index,
                    silently = silently,
                )
                return
            }
            index++
        }
        return@smartForEach true
    }
}

/**
 * Removes the **first** element matching [predicate] across all cached pages.
 *
 * Stops after the first removal. For a "remove every match" semantics see [removeAll].
 *
 * @return The removed element, or `null` if no element matched.
 *
 * **L2 note:** modifies L1 only. Use [MutablePaginator.flush] or wrap the call
 * in [MutablePaginator.transaction] to persist.
 */
fun <T> MutablePaginator<T>.removeElement(predicate: (T) -> Boolean): T? {
    for (page in cache.pages) {
        val removed: T? = removeElement(page, predicate)
        if (removed != null) {
            return removed
        }
    }
    return null
}

/**
 * Removes the first element on [page] matching [predicate].
 *
 * @return The removed element, or `null` if no element matched (or [page] is missing).
 */
fun <T> MutablePaginator<T>.removeElement(page: Int, predicate: (T) -> Boolean): T? {
    val state: PageState<T>? = cache.getStateOf(page)
    state ?: return null
    for ((index, element) in state.data.withIndex()) {
        if (predicate(element)) {
            return removeElement(
                page = page,
                index = index,
            )
        }
    }
    return null
}

// ──────────────────────────────────────────────────────────────────────────────
//  Bulk insert / move / swap
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Inserts [element] at the beginning of the first cached page.
 *
 * Symmetric counterpart of [addElement] (which appends at the end).
 *
 * If the cache is empty (no first page exists), the function returns `false`.
 * Overflow elements pushed past the page capacity are cascaded to subsequent
 * pages by [addAllElements].
 *
 * **L2 note:** modifies L1 only. Use [MutablePaginator.flush] or wrap the call
 * in [MutablePaginator.transaction] to persist.
 *
 * @param element The element to insert at the front of the first cached page.
 * @param silently If `true`, no snapshot is emitted after the operation.
 * @param initSuccessPageState Optional factory used by [addAllElements] to create
 *   overflow pages.
 * @return `true` if an element was inserted, `false` if the cache had no pages.
 */
fun <T> MutablePaginator<T>.prependElement(
    element: T,
    silently: Boolean = false,
    initSuccessPageState: ((page: Int, data: List<T>) -> PageState<T>)? = null
): Boolean {
    val firstPage: Int = cache.pages.firstOrNull() ?: return false
    addAllElements(
        elements = listOf(element),
        targetPage = firstPage,
        index = 0,
        silently = silently,
        initPageState = initSuccessPageState,
    )
    return true
}

/**
 * Swaps two elements in the cache by their (page, index) coordinates.
 *
 * Performs two [setElement] calls under the hood. Both pages must exist in the cache.
 *
 * **L2 note:** modifies L1 only. Use [MutablePaginator.flush] or wrap the call
 * in [MutablePaginator.transaction] to persist.
 *
 * @throws NoSuchElementException If either page is not in the cache.
 * @throws IndexOutOfBoundsException If either index is out of range.
 */
fun <T> MutablePaginator<T>.swapElements(
    aPage: Int, aIndex: Int,
    bPage: Int, bIndex: Int,
    silently: Boolean = false,
) {
    if (aPage == bPage && aIndex == bIndex) return
    val aState: PageState<T> = cache.getStateOf(aPage)
        ?: throw NoSuchElementException("page-$aPage was not found in cache")
    val bState: PageState<T> = cache.getStateOf(bPage)
        ?: throw NoSuchElementException("page-$bPage was not found in cache")
    val aElement: T = aState.data[aIndex]
    val bElement: T = bState.data[bIndex]
    setElement(element = bElement, page = aPage, index = aIndex, silently = true)
    setElement(element = aElement, page = bPage, index = bIndex, silently = true)
    if (!silently) core.snapshot()
}

/**
 * Moves an element from one (page, index) coordinate to another.
 *
 * Semantics follow `RecyclerView#notifyItemMoved`: after the call the moved
 * element is positioned at the **target** ([toPage], [toIndex]) coordinate.
 * This is implemented as a "remove from source, then insert at target" sequence
 * — no extra index gymnastics needed at the call site, the natural insertion
 * index lands the element at the requested final position.
 *
 * If the source page becomes empty after extraction, it is dropped from the
 * cache via [removeState], which collapses any subsequent pages by 1. When the
 * destination is one of those collapsed pages (i.e. [toPage] > [fromPage]),
 * [toPage] is automatically shifted down by 1 so the element still lands on
 * the page the caller meant.
 *
 * **L2 note:** modifies L1 only. Use [MutablePaginator.flush] or wrap the call
 * in [MutablePaginator.transaction] to persist.
 *
 * @throws NoSuchElementException If [fromPage] is not in the cache.
 * @throws IndexOutOfBoundsException If [fromIndex] is out of range.
 */
fun <T> MutablePaginator<T>.moveElement(
    fromPage: Int, fromIndex: Int,
    toPage: Int, toIndex: Int,
    silently: Boolean = false,
) {
    if (fromPage == toPage && fromIndex == toIndex) return

    val fromState: PageState<T> = cache.getStateOf(fromPage)
        ?: throw NoSuchElementException("page-$fromPage was not found in cache")

    @Suppress("UNCHECKED_CAST")
    val fromData: MutableList<T> = fromState.data as MutableList<T>
    val element: T = fromData.removeAt(fromIndex)

    var effectiveToPage: Int = toPage
    if (fromData.isEmpty()) {
        // Source page emptied — drop it. removeState collapses pages > fromPage by 1.
        removeState(pageToRemove = fromPage, silently = true)
        if (toPage > fromPage) effectiveToPage = toPage - 1
    }

    addAllElements(
        elements = listOf(element),
        targetPage = effectiveToPage,
        index = toIndex,
        silently = true,
    )

    if (!silently) core.snapshot()
}

/**
 * Inserts [element] immediately **before** the first element matching [predicate].
 *
 * @return `true` if a matching element was found and the insertion happened,
 *   `false` otherwise.
 */
inline fun <T> MutablePaginator<T>.insertBefore(
    element: T,
    silently: Boolean = false,
    predicate: (T) -> Boolean,
): Boolean {
    val (page, index) = indexOfFirst(predicate) ?: return false
    addAllElements(
        elements = listOf(element),
        targetPage = page,
        index = index,
        silently = silently,
    )
    return true
}

/**
 * Inserts [element] immediately **after** the first element matching [predicate].
 *
 * @return `true` if a matching element was found and the insertion happened,
 *   `false` otherwise.
 */
inline fun <T> MutablePaginator<T>.insertAfter(
    element: T,
    silently: Boolean = false,
    predicate: (T) -> Boolean,
): Boolean {
    val (page, index) = indexOfFirst(predicate) ?: return false
    addAllElements(
        elements = listOf(element),
        targetPage = page,
        index = index + 1,
        silently = silently,
    )
    return true
}

/**
 * Removes every element across all cached pages that satisfies [predicate].
 *
 * Internally delegates to [replaceAllElements] with a `null`-returning provider,
 * so each removal is performed silently and a single snapshot is emitted at the end
 * (unless [silently] is `true`).
 *
 * @return The number of elements removed.
 */
inline fun <T> MutablePaginator<T>.removeAll(
    silently: Boolean = false,
    predicate: (T) -> Boolean,
): Int {
    var removedCount = 0
    replaceAllElements(
        providerElement = { _, _, _ -> null },
        silently = silently,
        predicate = { current, _, _ ->
            if (predicate(current)) {
                removedCount++
                true
            } else {
                false
            }
        },
    )
    return removedCount
}

/**
 * Keeps only elements that satisfy [predicate]; removes the rest.
 *
 * @return The number of elements removed.
 */
inline fun <T> MutablePaginator<T>.retainAll(
    silently: Boolean = false,
    predicate: (T) -> Boolean,
): Int {
    return removeAll(silently = silently) { !predicate(it) }
}

/**
 * Removes duplicate elements based on the key returned by [selector].
 *
 * The first occurrence of each key is retained; subsequent occurrences are removed.
 *
 * @return The number of duplicates removed.
 */
inline fun <T> MutablePaginator<T>.distinctBy(
    silently: Boolean = false,
    crossinline selector: (T) -> Any?,
): Int {
    val seen: HashSet<Any?> = hashSetOf()
    return removeAll(silently = silently) { !seen.add(selector(it)) }
}

/**
 * Replaces every element across all cached pages by applying [transform] to it.
 *
 * Equivalent to [replaceAllElements] with an always-true predicate.
 */
inline fun <T> MutablePaginator<T>.updateAll(
    silently: Boolean = false,
    transform: (T) -> T,
) {
    replaceAllElements(
        providerElement = { current, _, _ -> transform(current) },
        silently = silently,
        predicate = { _, _, _ -> true },
    )
}

/**
 * Replaces every element matching [predicate] with the result of [transform].
 *
 * @return The number of elements replaced.
 */
inline fun <T> MutablePaginator<T>.updateWhere(
    silently: Boolean = false,
    predicate: (T) -> Boolean,
    transform: (T) -> T,
): Int {
    var replacedCount = 0
    replaceAllElements(
        providerElement = { current, _, _ ->
            replacedCount++
            transform(current)
        },
        silently = silently,
        predicate = { current, _, _ -> predicate(current) },
    )
    return replacedCount
}
