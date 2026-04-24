package com.jamal_aliev.paginator.extension

import com.jamal_aliev.paginator.MutableCursorPaginator
import com.jamal_aliev.paginator.MutableCursorPaginator.CursorBookmarkFactory
import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.page.PageState

// ──────────────────────────────────────────────────────────────────────────
//  Append / prepend / set / remove
// ──────────────────────────────────────────────────────────────────────────

/**
 * Appends [element] to the end of the tail page.
 *
 * Returns `false` if the cache is empty and the element could not be appended.
 */
fun <T> MutableCursorPaginator<T>.addElement(
    element: T,
    silently: Boolean = false,
    bookmarkFactory: CursorBookmarkFactory? = null,
    initPageState: ((previous: CursorBookmark, data: List<T>) -> PageState<T>)? = null,
): Boolean {
    val tail: CursorBookmark = core.tailCursor() ?: return false
    val tailData: List<T> = cache.getStateOf(tail.self)?.data ?: return false
    addElement(
        element = element,
        self = tail.self,
        index = tailData.size,
        silently = silently,
        bookmarkFactory = bookmarkFactory,
        initPageState = initPageState,
    )
    return true
}

/**
 * Inserts [element] into the page identified by [self] at [index].
 */
fun <T> MutableCursorPaginator<T>.addElement(
    element: T,
    self: Any,
    index: Int,
    silently: Boolean = false,
    bookmarkFactory: CursorBookmarkFactory? = null,
    initPageState: ((previous: CursorBookmark, data: List<T>) -> PageState<T>)? = null,
) {
    addAllElements(
        elements = listOf(element),
        targetSelf = self,
        index = index,
        silently = silently,
        bookmarkFactory = bookmarkFactory,
        initPageState = initPageState,
    )
}

/**
 * Inserts [element] at the beginning of the head page.
 *
 * Overflow is cascaded via `addAllElements`.
 */
fun <T> MutableCursorPaginator<T>.prependElement(
    element: T,
    silently: Boolean = false,
    bookmarkFactory: CursorBookmarkFactory? = null,
    initPageState: ((previous: CursorBookmark, data: List<T>) -> PageState<T>)? = null,
): Boolean {
    val head: CursorBookmark = core.headCursor() ?: return false
    addAllElements(
        elements = listOf(element),
        targetSelf = head.self,
        index = 0,
        silently = silently,
        bookmarkFactory = bookmarkFactory,
        initPageState = initPageState,
    )
    return true
}

/**
 * Replaces the first element matching [predicate] with [element].
 */
inline fun <T> MutableCursorPaginator<T>.setElement(
    element: T,
    silently: Boolean = false,
    predicate: (T) -> Boolean,
) {
    val (cursor, idx) = indexOfFirst(predicate) ?: return
    setElement(
        element = element,
        self = cursor.self,
        index = idx,
        silently = silently,
    )
}

/**
 * Removes the first element matching [predicate] from anywhere in the cache.
 */
fun <T> MutableCursorPaginator<T>.removeElement(predicate: (T) -> Boolean): T? {
    val (cursor, idx) = indexOfFirst(predicate) ?: return null
    return removeElement(self = cursor.self, index = idx)
}

/**
 * Removes the first element matching [predicate] from the page identified by [self].
 */
fun <T> MutableCursorPaginator<T>.removeElement(self: Any, predicate: (T) -> Boolean): T? {
    val state: PageState<T> = cache.getStateOf(self) ?: return null
    for ((index, element) in state.data.withIndex()) {
        if (predicate(element)) {
            return removeElement(self = self, index = index)
        }
    }
    return null
}

// ──────────────────────────────────────────────────────────────────────────
//  Swap / move / insertBefore / insertAfter / bulk transforms
// ──────────────────────────────────────────────────────────────────────────

fun <T> MutableCursorPaginator<T>.swapElements(
    aSelf: Any, aIndex: Int,
    bSelf: Any, bIndex: Int,
    silently: Boolean = false,
) {
    if (aSelf == bSelf && aIndex == bIndex) return
    val aState = cache.getStateOf(aSelf)
        ?: throw NoSuchElementException("self=$aSelf was not found in cache")
    val bState = cache.getStateOf(bSelf)
        ?: throw NoSuchElementException("self=$bSelf was not found in cache")
    val aElement: T = aState.data[aIndex]
    val bElement: T = bState.data[bIndex]
    setElement(element = bElement, self = aSelf, index = aIndex, silently = true)
    setElement(element = aElement, self = bSelf, index = bIndex, silently = true)
    if (!silently) core.snapshot()
}

fun <T> MutableCursorPaginator<T>.moveElement(
    fromSelf: Any, fromIndex: Int,
    toSelf: Any, toIndex: Int,
    silently: Boolean = false,
    bookmarkFactory: CursorBookmarkFactory? = null,
) {
    if (fromSelf == toSelf && fromIndex == toIndex) return

    val fromState = cache.getStateOf(fromSelf)
        ?: throw NoSuchElementException("self=$fromSelf was not found in cache")

    @Suppress("UNCHECKED_CAST")
    val fromData: MutableList<T> = fromState.data as MutableList<T>
    val element: T = fromData.removeAt(fromIndex)

    val pageEmptied = fromData.isEmpty()
    if (pageEmptied) {
        // Relinks neighbours automatically; no index-collapse needed in the cursor world.
        removeState(selfToRemove = fromSelf, silently = true)
    }

    addAllElements(
        elements = listOf(element),
        targetSelf = toSelf,
        index = toIndex,
        silently = true,
        bookmarkFactory = bookmarkFactory,
    )

    if (!silently) core.snapshot()
}

inline fun <T> MutableCursorPaginator<T>.insertBefore(
    element: T,
    silently: Boolean = false,
    predicate: (T) -> Boolean,
): Boolean {
    val (cursor, idx) = indexOfFirst(predicate) ?: return false
    addAllElements(
        elements = listOf(element),
        targetSelf = cursor.self,
        index = idx,
        silently = silently,
    )
    return true
}

inline fun <T> MutableCursorPaginator<T>.insertAfter(
    element: T,
    silently: Boolean = false,
    predicate: (T) -> Boolean,
): Boolean {
    val (cursor, idx) = indexOfFirst(predicate) ?: return false
    addAllElements(
        elements = listOf(element),
        targetSelf = cursor.self,
        index = idx + 1,
        silently = silently,
    )
    return true
}

inline fun <T> MutableCursorPaginator<T>.removeAll(
    silently: Boolean = false,
    predicate: (T) -> Boolean,
): Int {
    var removed = 0
    replaceAllElements(
        providerElement = { _, _, _ -> null },
        silently = silently,
        predicate = { current, _, _ ->
            if (predicate(current)) {
                removed++
                true
            } else false
        },
    )
    return removed
}

inline fun <T> MutableCursorPaginator<T>.retainAll(
    silently: Boolean = false,
    predicate: (T) -> Boolean,
): Int = removeAll(silently = silently) { !predicate(it) }

inline fun <T> MutableCursorPaginator<T>.distinctBy(
    silently: Boolean = false,
    crossinline selector: (T) -> Any?,
): Int {
    val seen = HashSet<Any?>()
    return removeAll(silently = silently) { !seen.add(selector(it)) }
}

inline fun <T> MutableCursorPaginator<T>.updateAll(
    silently: Boolean = false,
    transform: (T) -> T,
) {
    replaceAllElements(
        providerElement = { current, _, _ -> transform(current) },
        silently = silently,
        predicate = { _, _, _ -> true },
    )
}

inline fun <T> MutableCursorPaginator<T>.updateWhere(
    silently: Boolean = false,
    predicate: (T) -> Boolean,
    transform: (T) -> T,
): Int {
    var count = 0
    replaceAllElements(
        providerElement = { current, _, _ ->
            count++
            transform(current)
        },
        silently = silently,
        predicate = { current, _, _ -> predicate(current) },
    )
    return count
}
