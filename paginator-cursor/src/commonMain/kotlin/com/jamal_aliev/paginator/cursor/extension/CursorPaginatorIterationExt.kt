package com.jamal_aliev.paginator.cursor.extension

import com.jamal_aliev.paginator.cursor.CursorPaginator
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.page.CursorPageState

/**
 * Iterates through each cached [PageState] in the cursor paginator, in
 * head-to-tail order.
 */
inline fun <K : Any, T> CursorPaginator<K, T>.forEach(action: (CursorPageState<K, T>) -> Unit) {
    for (state in this) action(state)
}

/**
 * Iterates over the cached `(cursor, state)` pairs, in head-to-tail order.
 */
inline fun <K : Any, T> CursorPaginator<K, T>.forEachEntry(
    action: (cursor: CursorBookmark<K>, state: CursorPageState<K, T>) -> Unit,
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
inline fun <K : Any, T> CursorPaginator<K, T>.smartForEach(
    initialIndex: (list: List<CursorBookmark<K>>) -> Int = { 0 },
    step: (index: Int) -> Int = { it + 1 },
    actionAndContinue: (
        cursors: List<CursorBookmark<K>>,
        index: Int,
        currentCursor: CursorBookmark<K>,
        currentState: CursorPageState<K, T>,
    ) -> Boolean,
): List<CursorBookmark<K>> {
    val cursors: List<CursorBookmark<K>> = this.core.cursors
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
inline fun <K : Any, T> CursorPaginator<K, T>.walkForwardWhile(
    pivot: CursorBookmark<K>?,
    predicate: (CursorPageState<K, T>) -> Boolean = { true },
): Pair<CursorBookmark<K>, CursorPageState<K, T>>? {
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
inline fun <K : Any, T> CursorPaginator<K, T>.walkBackwardWhile(
    pivot: CursorBookmark<K>?,
    predicate: (CursorPageState<K, T>) -> Boolean = { true },
): Pair<CursorBookmark<K>, CursorPageState<K, T>>? {
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
