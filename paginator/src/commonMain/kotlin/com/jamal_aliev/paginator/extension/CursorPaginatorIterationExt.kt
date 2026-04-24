package com.jamal_aliev.paginator.extension

import com.jamal_aliev.paginator.CursorPaginator
import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.page.PageState

/**
 * Iterates through each cached [PageState] in the cursor paginator, in
 * head-to-tail order.
 */
inline fun <T> CursorPaginator<T>.forEach(action: (PageState<T>) -> Unit) {
    for (state in this) action(state)
}

/**
 * Iterates over the cached `(cursor, state)` pairs, in head-to-tail order.
 */
inline fun <T> CursorPaginator<T>.forEachEntry(
    action: (cursor: CursorBookmark, state: PageState<T>) -> Unit,
) {
    for (cursor in core.cursors) {
        val state = cache.getStateOf(cursor.self) ?: continue
        action(cursor, state)
    }
}

/**
 * Safe iteration counterpart of [com.jamal_aliev.paginator.extension.smartForEach]
 * for the cursor paginator.
 *
 * Allows full control over how iteration starts, progresses, and stops, keyed
 * by index into the materialised `cursors` list.
 */
inline fun <T> CursorPaginator<T>.smartForEach(
    initialIndex: (list: List<CursorBookmark>) -> Int = { 0 },
    step: (index: Int) -> Int = { it + 1 },
    actionAndContinue: (
        cursors: List<CursorBookmark>,
        index: Int,
        currentCursor: CursorBookmark,
        currentState: PageState<T>,
    ) -> Boolean,
): List<CursorBookmark> {
    val cursors: List<CursorBookmark> = this.core.cursors
    var index = initialIndex.invoke(cursors)
    while (0 <= index && index < cursors.size) {
        val currentCursor = cursors[index]
        val currentState = cache.getStateOf(currentCursor.self)
        if (currentState != null) {
            if (!actionAndContinue.invoke(cursors, index, currentCursor, currentState)) break
        }
        index = step.invoke(index)
    }
    return cursors
}

/**
 * Walks forward from [pivot] through consecutive pages that satisfy [predicate]
 * (default: always true), returning the last cursor/state pair in the chain.
 */
inline fun <T> CursorPaginator<T>.walkForwardWhile(
    pivot: CursorBookmark?,
    predicate: (PageState<T>) -> Boolean = { true },
): Pair<CursorBookmark, PageState<T>>? {
    pivot ?: return null
    val pivotState = cache.getStateOf(pivot.self) ?: return null
    val result = core.walkWhile(
        pivotState = pivotState,
        pivotCursor = pivot,
        next = { _, c -> cache.walkForward(c) },
        predicate = { state -> predicate.invoke(state) },
    ) ?: return null
    return result.second to result.first
}

/**
 * Walks backward from [pivot] through consecutive pages that satisfy [predicate]
 * (default: always true), returning the earliest cursor/state pair in the chain.
 */
inline fun <T> CursorPaginator<T>.walkBackwardWhile(
    pivot: CursorBookmark?,
    predicate: (PageState<T>) -> Boolean = { true },
): Pair<CursorBookmark, PageState<T>>? {
    pivot ?: return null
    val pivotState = cache.getStateOf(pivot.self) ?: return null
    val result = core.walkWhile(
        pivotState = pivotState,
        pivotCursor = pivot,
        next = { _, c -> cache.walkBackward(c) },
        predicate = { state -> predicate.invoke(state) },
    ) ?: return null
    return result.second to result.first
}
