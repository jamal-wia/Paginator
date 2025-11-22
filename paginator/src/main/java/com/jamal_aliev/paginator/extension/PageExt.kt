package com.jamal_aliev.paginator.extension

import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PageState.EmptyPage
import com.jamal_aliev.paginator.page.PageState.ErrorPage
import com.jamal_aliev.paginator.page.PageState.ProgressPage
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
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

@Suppress("NOTHING_TO_INLINE")
inline infix fun PageState<*>.near(other: PageState<*>): Boolean =
    this.gap(other) in 0u..1u

@Suppress("NOTHING_TO_INLINE")
inline infix fun PageState<*>.far(other: PageState<*>): Boolean =
    this.gap(other) !in 0u..1u

@Suppress("NOTHING_TO_INLINE")
inline infix fun PageState<*>.gap(other: PageState<*>): UInt =
    maxOf(this.page, other.page) - minOf(this.page, other.page)

@Suppress("NOTHING_TO_INLINE")
internal inline infix fun PageState<*>.gap(other: UInt): UInt =
    maxOf(this.page, other) - minOf(this.page, other)

@Suppress("NOTHING_TO_INLINE")
internal inline infix fun UInt.gap(other: PageState<*>): UInt =
    maxOf(this, other.page) - minOf(this, other.page)