package com.jamal_aliev.paginator.view

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reactive container for the **most recent** prefetch error.
 *
 * View-flavor counterpart of `paginator-compose`'s `PrefetchErrorChannel`. Idiomatic replacement
 * for `onPrefetchError: (Exception) -> Unit` when you want to surface the failure on the UI
 * thread via a `Flow` instead of a synchronous callback — collect [errors] inside
 * `repeatOnLifecycle(STARTED) { … }` to drive a `Snackbar`, retry banner, or analytics ping,
 * then call [consume] to clear the slot.
 *
 * The [onError] property is a stable callback reference — pass it directly into the binding /
 * controller's `onPrefetchError` slot without worrying about identity changes between
 * configuration changes (the channel itself is not retained across them; create one per
 * `viewLifecycleOwner` or hold one in a `ViewModel`).
 *
 * Example:
 * ```
 * private val errors = PrefetchErrorChannel()
 *
 * override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
 *     paginator.bindPaginated(
 *         recyclerView = binding.recyclerView,
 *         lifecycleOwner = viewLifecycleOwner,
 *         onPrefetchError = errors.onError,
 *     )
 *
 *     viewLifecycleOwner.lifecycleScope.launch {
 *         viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
 *             errors.errors.collect { e ->
 *                 if (e != null) {
 *                     Snackbar.make(view, e.message ?: "Prefetch failed", LENGTH_SHORT).show()
 *                     errors.consume()
 *                 }
 *             }
 *         }
 *     }
 * }
 * ```
 */
public class PrefetchErrorChannel {

    private val state = MutableStateFlow<Exception?>(null)

    /** Latest unhandled prefetch error, or `null` if none / already consumed. */
    public val errors: StateFlow<Exception?> = state.asStateFlow()

    /** Latest unhandled prefetch error as a synchronous getter — convenient outside coroutines. */
    public val current: Exception? get() = state.value

    /** Stable callback to pass into `onPrefetchError`. Records the latest exception. */
    public val onError: (Exception) -> Unit = { state.value = it }

    /** Clear the slot after the UI has surfaced [current]. */
    public fun consume() {
        state.value = null
    }
}
