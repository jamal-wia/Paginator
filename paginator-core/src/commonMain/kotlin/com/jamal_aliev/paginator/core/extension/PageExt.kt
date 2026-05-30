package com.jamal_aliev.paginator.core.extension

import com.jamal_aliev.paginator.core.page.PageState
import com.jamal_aliev.paginator.core.page.PageState.ErrorState
import com.jamal_aliev.paginator.core.page.PageState.ProgressState
import com.jamal_aliev.paginator.core.page.PageState.SuccessState
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.reflect.KClass

/**
 * Checks if the PageState is in progress state.
 *
 * @return True if the PageState is a [ProgressState], false otherwise.
 */
@OptIn(ExperimentalContracts::class)
@Suppress("NOTHING_TO_INLINE")
inline fun <T> PageState<T>?.isProgressState(): Boolean {
    contract {
        returns(true) implies (this@isProgressState is ProgressState<T>)
    }
    return this is ProgressState<*>
}

/**
 * Checks if the PageState is a real progress state — a [ProgressState] that is exactly the
 * supplied [clazz] subtype.
 *
 * @return True if the PageState is a [ProgressState] of type [clazz], false otherwise.
 */
@OptIn(ExperimentalContracts::class)
@Suppress("NOTHING_TO_INLINE")
inline fun <T> PageState<T>.isRealProgressState(
    clazz: KClass<out ProgressState<*>>
): Boolean {
    contract {
        returns(true) implies (this@isRealProgressState is ProgressState<T>)
    }
    return this.isProgressState() && clazz.isInstance(this)
}

/**
 * Checks if the PageState is an "empty" success — a [SuccessState] whose `data` list is empty.
 *
 * The predicate is derived from the data itself, so any `SuccessState` (including custom
 * subclasses) qualifies as empty iff it carries no items.
 *
 * @return True if the PageState is a SuccessState with no items, false otherwise.
 */
@OptIn(ExperimentalContracts::class)
@Suppress("NOTHING_TO_INLINE")
inline fun <T> PageState<T>?.isEmptyState(): Boolean {
    contract {
        returns(true) implies (this@isEmptyState is SuccessState<T>)
    }
    return this is SuccessState<*> && this.data.isEmpty()
}

/**
 * Checks if the PageState is in success state — a [SuccessState] carrying at least one item.
 *
 * @return True if the PageState is SuccessState with non-empty data, false otherwise.
 */
@OptIn(ExperimentalContracts::class)
@Suppress("NOTHING_TO_INLINE")
inline fun <T> PageState<T>?.isSuccessState(): Boolean {
    contract {
        returns(true) implies (this@isSuccessState is SuccessState<T>)
    }
    return this is SuccessState<*> && this.data.isNotEmpty()
}

/**
 * Checks if the PageState is a real success state — exactly the supplied [clazz] subtype
 * with non-empty data.
 *
 * @return True if the PageState is SuccessState of type [clazz] with non-empty data, false otherwise.
 */
@OptIn(ExperimentalContracts::class)
@Suppress("NOTHING_TO_INLINE")
inline fun <T> PageState<T>.isRealSuccessState(
    clazz: KClass<out SuccessState<*>>
): Boolean {
    contract {
        returns(true) implies (this@isRealSuccessState is SuccessState<T>)
    }
    return this.isSuccessState() && clazz.isInstance(this)
}

/**
 * Checks if the PageState is in error state.
 *
 * @return True if the PageState is an [ErrorState], false otherwise.
 */
@OptIn(ExperimentalContracts::class)
@Suppress("NOTHING_TO_INLINE")
inline fun <T> PageState<T>?.isErrorState(): Boolean {
    contract {
        returns(true) implies (this@isErrorState is ErrorState<T>)
    }
    return this is ErrorState<*>
}

/**
 * Checks if the PageState is a real error state — a [ErrorState] that is exactly the supplied
 * [clazz] subtype.
 *
 * @return True if the PageState is an [ErrorState] of type [clazz], false otherwise.
 */
@OptIn(ExperimentalContracts::class)
@Suppress("NOTHING_TO_INLINE")
inline fun <T> PageState<T>.isRealErrorState(
    clazz: KClass<out ErrorState<*>>
): Boolean {
    contract {
        returns(true) implies (this@isRealErrorState is ErrorState<T>)
    }
    return this.isErrorState() && clazz.isInstance(this)
}
