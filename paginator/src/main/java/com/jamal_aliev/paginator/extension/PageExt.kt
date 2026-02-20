package com.jamal_aliev.paginator.extension

import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PageState.EmptyPage
import com.jamal_aliev.paginator.page.PageState.ErrorPage
import com.jamal_aliev.paginator.page.PageState.ProgressPage
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.math.abs
import kotlin.reflect.KClass

/**
 * Checks if the PageState is in progress state.
 *
 * @return True if the PageState is ProgressPage, false otherwise.
 */
@OptIn(ExperimentalContracts::class)
@Suppress("NOTHING_TO_INLINE")
inline fun <T> PageState<T>?.isProgressState(): Boolean {
    contract {
        returns(true) implies (this@isProgressState is ProgressPage<T>)
    }
    return this is ProgressPage<*>
}

/**
 * Checks if the PageState is a real progress state.
 *
 * @return True if the PageState is ProgressPage of type T, false otherwise.
 */
@OptIn(ExperimentalContracts::class)
@Suppress("NOTHING_TO_INLINE")
inline fun <T> PageState<T>.isRealProgressState(
    clazz: KClass<out ProgressPage<*>>
): Boolean {
    contract {
        returns(true) implies (this@isRealProgressState is ProgressPage<T>)
    }
    return this.isProgressState() && clazz.isInstance(this)
}

/**
 * Checks if the PageState is in empty state.
 *
 * @return True if the PageState is EmptyPage, false otherwise.
 */
@OptIn(ExperimentalContracts::class)
@Suppress("NOTHING_TO_INLINE")
inline fun <T> PageState<T>?.isEmptyState(): Boolean {
    contract {
        returns(true) implies (this@isEmptyState is EmptyPage<T>)
    }
    return this is EmptyPage<*>
}

/**
 * Checks if the PageState is a real empty state.
 *
 * @return True if the PageState is EmptyPage of type T, false otherwise.
 */
@OptIn(ExperimentalContracts::class)
@Suppress("NOTHING_TO_INLINE")
inline fun <T> PageState<T>.isRealEmptyState(
    clazz: KClass<out EmptyPage<*>>
): Boolean {
    contract {
        returns(true) implies (this@isRealEmptyState is EmptyPage<T>)
    }
    return this.isEmptyState() && clazz.isInstance(this)
}

/**
 * Checks if the PageState is in success state.
 *
 * @return True if the PageState is SuccessPage and not EmptyPage, false otherwise.
 */
@OptIn(ExperimentalContracts::class)
@Suppress("NOTHING_TO_INLINE")
inline fun <T> PageState<T>?.isSuccessState(): Boolean {
    contract {
        returns(true) implies (this@isSuccessState is SuccessPage<T>
                && this@isSuccessState !is EmptyPage<T>)
        returns(true) implies (this@isSuccessState !is EmptyPage<T>)
    }
    return this is SuccessPage<*> && this !is EmptyPage<*>
}

/**
 * Checks if the PageState is a real success state.
 *
 * @return True if the PageState is SuccessPage of type T, false otherwise.
 */
@OptIn(ExperimentalContracts::class)
@Suppress("NOTHING_TO_INLINE")
inline fun <T> PageState<T>.isRealSuccessState(
    clazz: KClass<out SuccessPage<*>>
): Boolean {
    contract {
        returns(true) implies (this@isRealSuccessState is SuccessPage<T>
                && this@isRealSuccessState !is EmptyPage<T>)
        returns(true) implies (this@isRealSuccessState !is EmptyPage<T>)
    }
    return this.isSuccessState() && clazz.isInstance(this)
}

/**
 * Checks if the PageState is in error state.
 *
 * @return True if the PageState is ErrorPage, false otherwise.
 */
@OptIn(ExperimentalContracts::class)
@Suppress("NOTHING_TO_INLINE")
inline fun <T> PageState<T>?.isErrorState(): Boolean {
    contract {
        returns(true) implies (this@isErrorState is ErrorPage<T>)
    }
    return this is ErrorPage<*>
}

/**
 * Checks if the PageState is a real error state.
 *
 * @return True if the PageState is ErrorPage of type T, false otherwise.
 */
@OptIn(ExperimentalContracts::class)
@Suppress("NOTHING_TO_INLINE")
inline fun <T> PageState<T>.isRealErrorState(
    clazz: KClass<out ErrorPage<*>>
): Boolean {
    contract {
        returns(true) implies (this@isRealErrorState is ErrorPage<T>)
    }
    return this.isErrorState() && clazz.isInstance(this)
}

/**
 * Determines whether this `PageState` is adjacent to, or identical with, another `PageState`
 * based on their positional gap.
 *
 * Two states are considered "near" if the distance between them
 * (as returned by `gap(other)`) is either `0` or `1`, meaning:
 *
 * - `0` — both states refer to the same position;
 * - `1` — the states are directly next to each other.
 *
 * This is a convenience infix function for expressing proximity between
 * two `PageState` instances in a clear and readable way.
 *
 * Note: If you want to know **exactly how far apart** two-page states are,
 * use [gap] instead of `near`.
 *
 * @param other The other `PageState` to compare with.
 * @return `true` if the gap is within `[0, 1]`, otherwise `false`.
 */
@Suppress("NOTHING_TO_INLINE")
inline infix fun PageState<*>.near(other: PageState<*>): Boolean {
    val gap: Int = this gap other
    return gap == 0 || gap == 1
}

/**
 * Determines whether this `PageState` is not adjacent to, and not identical with,
 * another `PageState` based on their positional gap.
 *
 * Two states are considered "far" if the distance between them
 * (as returned by `gap(other)`) is greater than `1`, meaning:
 *
 * - the states do not represent the same position (`gap != 0`), and
 * - they are not immediate neighbors (`gap != 1`).
 *
 * This is the logical inverse of the `near` function and provides a readable
 * way to express non-proximity between `PageState` instances.
 *
 * Note: If you want to know **exactly how far apart** two-page states are,
 * use [gap] instead of `far`.
 *
 * @param other The other `PageState` to compare with.
 * @return `true` if the gap is outside the range `[0, 1]`, otherwise `false`.
 */
@Suppress("NOTHING_TO_INLINE")
inline infix fun PageState<*>.far(other: PageState<*>): Boolean {
    val gap: Int = this gap other
    return gap != 0 && gap != 1
}

/**
 * Computes the absolute distance between this `PageState` and another `PageState`.
 *
 * The gap represents how far apart the two pages are, ignoring direction.
 *
 * Examples:
 * - States on the same page -> `0`
 * - Adjacent pages -> `1`
 * - Two pages apart -> `2`, and so on.
 *
 * This function is used by helpers like `near` and `far` to determine relative proximity
 * between page states.
 *
 * @param other The other `PageState` to measure the distance to.
 * @return An `Int` representing the absolute page difference.
 */
@Suppress("NOTHING_TO_INLINE")
inline infix fun PageState<*>.gap(other: PageState<*>): Int {
    return gapInternal(this.page, other.page)
}

@Suppress("NOTHING_TO_INLINE")
internal inline infix fun PageState<*>.gap(other: Int): Int {
    return gapInternal(this.page, other)
}

@Suppress("NOTHING_TO_INLINE")
internal inline infix fun Int.gap(other: PageState<*>): Int {
    return gapInternal(this, other.page)
}

@PublishedApi
@Suppress("NOTHING_TO_INLINE")
internal inline fun gapInternal(first: Int, second: Int): Int {
    return abs(first - second)
}
