package com.jamal_aliev.paginator.compose.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Snapshot of the data we read from a lazy-container state on every layout pass.
 *
 * Kept as a value class so that [snapshotFlow]'s `distinctUntilChanged` collapses passes where
 * nothing relevant changed (e.g., pixel-level scrolls that don't shift the visible index range).
 */
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
 * The effect is keyed on the controller identity and [sourceKey] only — `dataItemCount`,
 * `headerCount`, and the `onScroll` lambda are read through [rememberUpdatedState] so that
 * changes don't restart the calibration cycle.
 */
@Composable
internal fun BindScrollInternal(
    controllerKey: Any,
    sourceKey: Any,
    dataItemCount: Int,
    headerCount: Int,
    onScroll: (firstVisibleIndex: Int, lastVisibleIndex: Int, totalItemCount: Int) -> Unit,
    readSignal: () -> ScrollSignal,
) {
    val dataItemCountState = rememberUpdatedState(dataItemCount)
    val headerCountState = rememberUpdatedState(headerCount)
    val onScrollState = rememberUpdatedState(onScroll)
    val readSignalState = rememberUpdatedState(readSignal)

    LaunchedEffect(controllerKey, sourceKey) {
        snapshotFlow { readSignalState.value() }
            .distinctUntilChanged()
            .collect { signal ->
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
