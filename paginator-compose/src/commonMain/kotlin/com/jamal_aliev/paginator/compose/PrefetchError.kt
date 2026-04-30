package com.jamal_aliev.paginator.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * Reactive container for the **most recent** prefetch error.
 *
 * Compose-idiomatic replacement for `onPrefetchError: (Exception) -> Unit` — observe
 * [current] in a `LaunchedEffect` to surface a snackbar, retry banner, or analytics call,
 * then call [consume] to clear the slot.
 *
 * Stable reference: [onError] is captured once and is safe to pass through recompositions
 * without causing the prefetch controller to be re-created.
 *
 * Example:
 * ```
 * val errors = rememberPrefetchErrorChannel()
 * paginator.PrefetchOnScroll(
 *     state = listState,
 *     dataItemCount = uiState.items.size,
 *     onPrefetchError = errors.onError,
 * )
 * errors.current?.let { e ->
 *     LaunchedEffect(e) {
 *         snackbar.showSnackbar(e.message ?: "Prefetch failed")
 *         errors.consume()
 *     }
 * }
 * ```
 */
@Stable
class PrefetchErrorChannel internal constructor() {
    private val state: MutableState<Exception?> = mutableStateOf(null)

    /** Latest unhandled prefetch error, or `null` if none / already consumed. */
    val current: Exception? get() = state.value

    /** Stable callback to pass into `onPrefetchError`. Records the latest exception. */
    val onError: (Exception) -> Unit = { state.value = it }

    /** Clear the slot after the UI has surfaced [current]. */
    fun consume() {
        state.value = null
    }
}

/**
 * Remembers a [PrefetchErrorChannel] across recompositions. Tied to the calling composable's
 * lifetime — leaving composition resets the channel on next entry.
 */
@Composable
fun rememberPrefetchErrorChannel(): PrefetchErrorChannel = remember { PrefetchErrorChannel() }
