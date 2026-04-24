package com.jamal_aliev.paginator.extension

import com.jamal_aliev.paginator.CursorPaginator
import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.page.PageState

// ── Search ─────────────────────────────────────────────────────────────────

/**
 * Returns the first element across all cached pages that satisfies [predicate],
 * or `null` if no element matches.
 */
inline fun <T> CursorPaginator<T>.find(predicate: (T) -> Boolean): T? = getElement(predicate)

/** Historical alias for [find]. */
inline fun <T> CursorPaginator<T>.getElement(predicate: (T) -> Boolean): T? {
    for (state in core.states) {
        for (element in state.data) {
            if (predicate(element)) return element
        }
    }
    return null
}

/**
 * Returns the first `(cursor, index)` pair whose element satisfies [predicate],
 * or `null` if none does.
 */
inline fun <T> CursorPaginator<T>.indexOfFirst(
    predicate: (T) -> Boolean,
): Pair<CursorBookmark, Int>? {
    for (cursor in core.cursors) {
        val state = cache.getStateOf(cursor.self) ?: continue
        val idx = state.data.indexOfFirst(predicate)
        if (idx != -1) return cursor to idx
    }
    return null
}

/**
 * Like [indexOfFirst] but restricted to a single page identified by [self].
 */
inline fun <T> CursorPaginator<T>.indexOfFirst(
    self: Any,
    predicate: (T) -> Boolean,
): Pair<CursorBookmark, Int>? {
    val cursor = cache.getCursorOf(self) ?: return null
    val state = cache.getStateOf(self) ?: return null
    val idx = state.data.indexOfFirst(predicate)
    if (idx != -1) return cursor to idx
    return null
}

/**
 * Returns the last `(cursor, index)` pair whose element satisfies [predicate].
 */
inline fun <T> CursorPaginator<T>.indexOfLast(
    predicate: (T) -> Boolean,
): Pair<CursorBookmark, Int>? {
    val cursors = core.cursors
    for (i in cursors.indices.reversed()) {
        val cursor = cursors[i]
        val state = cache.getStateOf(cursor.self) ?: continue
        val idx = state.data.indexOfLast(predicate)
        if (idx != -1) return cursor to idx
    }
    return null
}

/** Returns the last matching element, or `null` if none matches. */
inline fun <T> CursorPaginator<T>.findLast(predicate: (T) -> Boolean): T? {
    val cursors = core.cursors
    for (i in cursors.indices.reversed()) {
        val state = cache.getStateOf(cursors[i].self) ?: continue
        val data = state.data
        for (j in data.indices.reversed()) {
            val element = data[j]
            if (predicate(element)) return element
        }
    }
    return null
}

// ── Predicates ─────────────────────────────────────────────────────────────

inline fun <T> CursorPaginator<T>.any(predicate: (T) -> Boolean): Boolean {
    for (state in core.states) {
        for (element in state.data) {
            if (predicate(element)) return true
        }
    }
    return false
}

inline fun <T> CursorPaginator<T>.none(predicate: (T) -> Boolean): Boolean = !any(predicate)

inline fun <T> CursorPaginator<T>.all(predicate: (T) -> Boolean): Boolean {
    for (state in core.states) {
        for (element in state.data) {
            if (!predicate(element)) return false
        }
    }
    return true
}

inline fun <T> CursorPaginator<T>.count(predicate: (T) -> Boolean = { true }): Int {
    var total = 0
    for (state in core.states) {
        for (element in state.data) {
            if (predicate(element)) total++
        }
    }
    return total
}

// ── Positional access ──────────────────────────────────────────────────────

fun <T> CursorPaginator<T>.firstOrNull(): T? {
    for (state in core.states) if (state.data.isNotEmpty()) return state.data.first()
    return null
}

fun <T> CursorPaginator<T>.lastOrNull(): T? {
    val states = core.states
    for (i in states.indices.reversed()) {
        val data = states[i].data
        if (data.isNotEmpty()) return data.last()
    }
    return null
}

/**
 * Returns the element at the given **flat** [globalIndex] across all cached pages,
 * or `null` if out of bounds. O(N) because there is no random access in the
 * cursor-based layout.
 */
fun <T> CursorPaginator<T>.elementAtOrNull(globalIndex: Int): T? {
    if (globalIndex < 0) return null
    var skipped = 0
    for (state in core.states) {
        val size = state.data.size
        if (globalIndex < skipped + size) return state.data[globalIndex - skipped]
        skipped += size
    }
    return null
}

// ── Transformation ─────────────────────────────────────────────────────────

fun <T> CursorPaginator<T>.flatten(): List<T> {
    val states = core.states
    var totalSize = 0
    for (state in states) totalSize += state.data.size
    val result = ArrayList<T>(totalSize)
    for (state in states) result.addAll(state.data)
    return result
}

inline fun <T, R> CursorPaginator<T>.flatMap(transform: (T) -> Iterable<R>): List<R> {
    val result = mutableListOf<R>()
    for (state in core.states) {
        for (element in state.data) result.addAll(transform(element))
    }
    return result
}

inline fun <T, R> CursorPaginator<T>.mapPages(
    transform: (cursor: CursorBookmark, state: PageState<T>) -> R,
): List<R> {
    val cursors = core.cursors
    val result = ArrayList<R>(cursors.size)
    for (cursor in cursors) {
        val state = cache.getStateOf(cursor.self) ?: continue
        result.add(transform(cursor, state))
    }
    return result
}

inline fun <T> CursorPaginator<T>.forEachIndexed(
    action: (globalIndex: Int, element: T) -> Unit,
) {
    var index = 0
    for (state in core.states) {
        for (element in state.data) {
            action(index, element)
            index++
        }
    }
}

// ── Operators / properties ─────────────────────────────────────────────────

operator fun <T> CursorPaginator<T>.contains(element: T): Boolean {
    for (state in core.states) if (element in state.data) return true
    return false
}

val <T> CursorPaginator<T>.loadedItemsCount: Int
    get() = count()

val <T> CursorPaginator<T>.loadedPagesCount: Int
    get() = core.size
