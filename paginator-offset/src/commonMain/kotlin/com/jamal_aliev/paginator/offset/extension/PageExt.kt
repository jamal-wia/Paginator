package com.jamal_aliev.paginator.offset.extension

import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlin.math.abs

/**
 * Determines whether this [OffsetPageState] is adjacent to, or identical with, another
 * based on their positional gap.
 *
 * Two states are considered "near" if the distance between them (as returned by [gap]) is either
 * `0` (same page) or `1` (directly adjacent).
 *
 * @param other The other page state to compare with.
 * @return `true` if the gap is within `[0, 1]`, otherwise `false`.
 */
@Suppress("NOTHING_TO_INLINE")
inline infix fun OffsetPageState<*>.near(other: OffsetPageState<*>): Boolean {
    val gap: Int = this gap other
    return gap == 0 || gap == 1
}

/**
 * Determines whether this [OffsetPageState] is not adjacent to, and not identical with, another
 * based on their positional gap. The logical inverse of [near].
 *
 * @param other The other page state to compare with.
 * @return `true` if the gap is outside the range `[0, 1]`, otherwise `false`.
 */
@Suppress("NOTHING_TO_INLINE")
inline infix fun OffsetPageState<*>.far(other: OffsetPageState<*>): Boolean {
    val gap: Int = this gap other
    return gap != 0 && gap != 1
}

/**
 * Computes the absolute page distance between this [OffsetPageState] and another, ignoring
 * direction. Same page → `0`, adjacent pages → `1`, and so on.
 *
 * @param other The other page state to measure the distance to.
 * @return An `Int` representing the absolute page difference.
 */
@Suppress("NOTHING_TO_INLINE")
inline infix fun OffsetPageState<*>.gap(other: OffsetPageState<*>): Int {
    return gapInternal(this.page, other.page)
}

@Suppress("NOTHING_TO_INLINE")
inline infix fun OffsetPageState<*>.gap(other: Int): Int {
    return gapInternal(this.page, other)
}

@Suppress("NOTHING_TO_INLINE")
inline infix fun Int.gap(other: OffsetPageState<*>): Int {
    return gapInternal(this, other.page)
}

@PublishedApi
@Suppress("NOTHING_TO_INLINE")
internal inline fun gapInternal(first: Int, second: Int): Int {
    return abs(first - second)
}
