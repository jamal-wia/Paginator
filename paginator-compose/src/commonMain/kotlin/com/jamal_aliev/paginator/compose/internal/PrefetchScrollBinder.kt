package com.jamal_aliev.paginator.compose.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.jamal_aliev.paginator.prefetch.remapIndices
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample

/**
 * Snapshot of the data we read from a lazy-container state on every layout pass.
 *
 * Marked [Immutable] so that [snapshotFlow]'s `distinctUntilChanged` collapses passes where
 * nothing relevant changed (e.g., pixel-level scrolls that don't shift the visible index range)
 * and so the Compose compiler can skip unnecessary recomposition checks.
 */
@Immutable
internal data class ScrollSignal(
    val firstVisibleIndex: Int,
    val lastVisibleIndex: Int,
    val totalItemCount: Int,
)

/**
 * Subscribes to a Compose lazy-container's scroll signal and forwards remapped, data-only
 * indices to the supplied [onScroll] callback.
 *
 * Container-agnostic core used by every `BindTo*` overload (LazyList, LazyGrid,
 * LazyStaggeredGrid). Each overload supplies a [readSignal] lambda that reads the relevant
 * state's `firstVisibleItemIndex` / `visibleItemsInfo.lastOrNull()?.index` /
 * `totalItemsCount`, plus a [sourceKey] tied to the lazy-state instance.
 *
 * The effect is keyed on the controller identity, [sourceKey], [restartKey], and
 * [scrollSampleMillis] — `dataItemCount`, `headerCount`, and the `onScroll` lambda are read
 * through [rememberUpdatedState] so that changes don't restart the calibration cycle.
 *
 * @param restartKey Optional extra key. Pass any value (e.g. a refresh counter) to force the
 *   underlying coroutine to restart from scratch — useful after a manual `paginator.refresh()`
 *   when you want the controller to recalibrate from the current viewport.
 * @param scrollSampleMillis When > 0, throttles the upstream signal via
 *   [kotlinx.coroutines.flow.sample] — only the latest emission per window is forwarded.
 *   Useful when [onScroll] does non-trivial work and the user scrolls fast.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun BindScrollInternal(
    controllerKey: Any,
    sourceKey: Any,
    restartKey: Any?,
    scrollSampleMillis: Long,
    dataItemCount: Int,
    headerCount: Int,
    onScroll: (firstVisibleIndex: Int, lastVisibleIndex: Int, totalItemCount: Int) -> Unit,
    readSignal: () -> ScrollSignal,
) {
    val dataItemCountState = rememberUpdatedState(dataItemCount)
    val headerCountState = rememberUpdatedState(headerCount)
    val onScrollState = rememberUpdatedState(onScroll)
    val readSignalState = rememberUpdatedState(readSignal)

    LaunchedEffect(controllerKey, sourceKey, restartKey, scrollSampleMillis) {
        val raw = snapshotFlow { readSignalState.value() }.distinctUntilChanged()
        val source = if (scrollSampleMillis > 0L) raw.sample(scrollSampleMillis) else raw
        source.collect { signal ->
            val remapped = remapIndices(
                firstVisibleIndex = signal.firstVisibleIndex,
                lastVisibleIndex = signal.lastVisibleIndex,
                totalItemCount = signal.totalItemCount,
                dataItemCount = dataItemCountState.value,
                headerCount = headerCountState.value,
            ) ?: return@collect

            onScrollState.value(
                remapped.firstVisibleIndex,
                remapped.lastVisibleIndex,
                remapped.totalItemCount,
            )
        }
    }
}
