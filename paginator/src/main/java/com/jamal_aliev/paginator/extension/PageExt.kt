package com.jamal_aliev.paginator.extension

import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PageState.EmptyPage
import com.jamal_aliev.paginator.page.PageState.ErrorPage
import com.jamal_aliev.paginator.page.PageState.ProgressPage
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * Checks if the PageState is in progress state.
 *
 * @return True if the PageState is ProgressPage, false otherwise.
 */
@OptIn(ExperimentalContracts::class)
@Suppress("NOTHING_TO_INLINE")
inline fun PageState<*>?.isProgressState(): Boolean {
    contract {
        returns(true) implies (this@isProgressState is ProgressPage)
    }
    return this is ProgressPage<*>
}

/**
 * Checks if the PageState is a real progress state.
 *
 * @return True if the PageState is ProgressPage of type T, false otherwise.
 */
@OptIn(ExperimentalContracts::class)
inline fun <reified T> PageState<*>.isRealProgressState(): Boolean {
    contract {
        returns(true) implies (this@isRealProgressState is ProgressPage)
    }
    return this.isProgressState() && this::class == T::class
}

/**
 * Checks if the PageState is in empty state.
 *
 * @return True if the PageState is EmptyPage, false otherwise.
 */
@OptIn(ExperimentalContracts::class)
@Suppress("NOTHING_TO_INLINE")
inline fun PageState<*>?.isEmptyState(): Boolean {
    contract {
        returns(true) implies (this@isEmptyState is EmptyPage)
    }
    return this is EmptyPage<*>
}

/**
 * Checks if the PageState is a real empty state.
 *
 * @return True if the PageState is EmptyPage of type T, false otherwise.
 */
@OptIn(ExperimentalContracts::class)
inline fun <reified T> PageState<*>.isRealEmptyState(): Boolean {
    contract {
        returns(true) implies (this@isRealEmptyState is EmptyPage)
    }
    return this.isEmptyState() && this::class == T::class
}

/**
 * Checks if the PageState is in success state.
 *
 * @return True if the PageState is SuccessPage and not EmptyPage, false otherwise.
 */
@OptIn(ExperimentalContracts::class)
@Suppress("NOTHING_TO_INLINE")
inline fun PageState<*>?.isSuccessState(): Boolean {
    contract {
        returns(true) implies (this@isSuccessState is SuccessPage<*>
                && this@isSuccessState !is EmptyPage<*>)
        returns(false) implies (this@isSuccessState is EmptyPage<*>)
    }
    return this is SuccessPage<*> && this !is EmptyPage<*>
}

/**
 * Checks if the PageState is a real success state.
 *
 * @return True if the PageState is SuccessPage of type T, false otherwise.
 */
@OptIn(ExperimentalContracts::class)
inline fun <reified T> PageState<*>.isRealSuccessState(): Boolean {
    contract {
        returns(true) implies (this@isRealSuccessState is SuccessPage)
    }
    return this.isSuccessState() && this::class == T::class
}

/**
 * Checks if the PageState is in error state.
 *
 * @return True if the PageState is ErrorPage, false otherwise.
 */
@OptIn(ExperimentalContracts::class)
@Suppress("NOTHING_TO_INLINE")
inline fun PageState<*>?.isErrorState(): Boolean {
    contract {
        returns(true) implies (this@isErrorState is ErrorPage)
    }
    return this is ErrorPage<*>
}

/**
 * Checks if the PageState is a real error state.
 *
 * @return True if the PageState is ErrorPage of type T, false otherwise.
 */
@OptIn(ExperimentalContracts::class)
inline fun <reified T> PageState<*>.isRealErrorState(): Boolean {
    contract {
        returns(true) implies (this@isRealErrorState is ErrorPage)
    }
    return this.isErrorState() && this::class == T::class
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