package com.jamal_aliev.paginator.compose.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.util.packInts
import androidx.compose.ui.util.unpackInt1
import androidx.compose.ui.util.unpackInt2
import com.jamal_aliev.paginator.prefetch.ScrollWindow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.sample
import kotlin.jvm.JvmInline

/**
 * Snapshot of the data we read from a lazy-container state on every layout pass.
 *
 * Packs [firstVisibleIndex] and [lastVisibleIndex] into a single [Long] via Compose's
 * [packInts] — zero heap allocation on every layout-snapshot read. The same scheme backs
 * `IntOffset` / `IntSize`. Value-equality (required for upstream `distinctUntilChanged` to
 * collapse pixel-level scrolls that don't shift the visible-index range) is just a [Long]
 * comparison.
 */
@Immutable
@JvmInline
internal value class ScrollSignal(val packed: Long) {
    val firstVisibleIndex: Int get() = unpackInt1(packed)
    val lastVisibleIndex: Int get() = unpackInt2(packed)
}

internal fun ScrollSignal(firstVisibleIndex: Int, lastVisibleIndex: Int): ScrollSignal =
    ScrollSignal(packInts(firstVisibleIndex, lastVisibleIndex))

/**
 * Stable reader that produces a [ScrollSignal] on every layout-snapshot read.
 *
 * Modeled as a `fun interface` so the call-site SAM-converted lambda can be cached via
 * `remember(state)` — that turns "one bound-reference allocation per recomposition" into
 * "one allocation per `state` instance change", which matters because every recomposition of
 * a paginated container would otherwise invalidate the controller's reader reference.
 */
@Stable
internal fun interface ScrollSignalReader {
    fun read(): ScrollSignal
}

/**
 * Stable callback the binder calls with data-only indices. Same allocation
 * argument as [ScrollSignalReader] — `remember(controller)` at the call site keeps the SAM
 * instance pinned across recompositions of the binder's container.
 */
@Stable
internal fun interface ScrollCallback {
    fun onScroll(firstVisibleIndex: Int, lastVisibleIndex: Int, totalItemCount: Int)
}

/**
 * Subscribes to a Compose lazy-container's scroll signal, applies header/data-count remapping,
 * and forwards the result to [callback].
 *
 * Container-agnostic core used by every `BindTo*` overload (LazyList, LazyGrid,
 * LazyStaggeredGrid). Each overload supplies a [reader] tied to the lazy-state instance and a
 * [callback] tied to the controller.
 *
 * The effect is keyed on [controllerKey], [sourceKey], [restartKey], and [scrollSampleMillis].
 * [dataItemCount], [headerCount], [reader] and [callback] are kept fresh through
 * [rememberUpdatedState] — but [dataItemCount] and [headerCount] are read from *inside*
 * [snapshotFlow], so changes to either re-evaluate the remap without restarting the
 * coroutine. That keeps the controller's calibration state intact across page loads while
 * still reacting to header/footer changes mid-flight.
 *
 * The remap runs inside the snapshot; [ScrollWindow.NONE] emissions (viewport outside the
 * data range) are filtered out and never reach [distinctUntilChanged], so the dedupe window
 * covers only actionable scroll states.
 *
 * @param restartKey Pass any value (e.g. a refresh counter) to restart from scratch — useful
 *   after a manual `paginator.refresh()` when you want the controller to recalibrate from the
 *   current viewport.
 * @param scrollSampleMillis When > 0, throttles the upstream signal via
 *   [kotlinx.coroutines.flow.sample] — only the latest emission per window is forwarded.
 *   Useful when [callback] does non-trivial work and the user scrolls fast.
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
    reader: ScrollSignalReader,
    callback: ScrollCallback,
) {
    val dataItemCountState = rememberUpdatedState(dataItemCount)
    val headerCountState = rememberUpdatedState(headerCount)
    val readerState = rememberUpdatedState(reader)
    val callbackState = rememberUpdatedState(callback)

    LaunchedEffect(controllerKey, sourceKey, restartKey, scrollSampleMillis) {
        snapshotFlow {
            val signal = readerState.value.read()
            ScrollWindow.from(
                firstVisibleIndex = signal.firstVisibleIndex,
                lastVisibleIndex = signal.lastVisibleIndex,
                dataItemCount = dataItemCountState.value,
                dataOffset = headerCountState.value,
            )
        }.let { flow: Flow<ScrollWindow> ->
            if (scrollSampleMillis > 0L) {
                flow.sample(scrollSampleMillis)
            } else flow
        }.filter { !it.isNone }
            .distinctUntilChanged()
            .collect { range ->
                callbackState.value.onScroll(
                    range.firstVisibleIndex,
                    range.lastVisibleIndex,
                    dataItemCountState.value,
                )
            }
    }
}
