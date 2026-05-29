package com.jamal_aliev.paginator.compose.offset

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jamal_aliev.paginator.compose.core.PaginatorInternalApi
import com.jamal_aliev.paginator.compose.core.internal.BindLazyListScrollPreservation
import com.jamal_aliev.paginator.compose.core.internal.BindScrollInternal
import com.jamal_aliev.paginator.compose.core.internal.ScrollCallback
import com.jamal_aliev.paginator.compose.core.internal.ScrollSignal
import com.jamal_aliev.paginator.compose.core.internal.ScrollSignalReader
import com.jamal_aliev.paginator.offset.prefetch.PaginatorPrefetchController

@OptIn(PaginatorInternalApi::class)
private fun LazyListState.readScrollSignal(): ScrollSignal {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    val lastIndex = if (visible.isEmpty()) -1 else visible[visible.size - 1].index
    return ScrollSignal(
        firstVisibleIndex = firstVisibleItemIndex,
        lastVisibleIndex = lastIndex,
    )
}

/**
 * Binds this [PaginatorPrefetchController] to a [LazyListState] so that scroll-driven prefetch
 * happens automatically — without manually wiring `LaunchedEffect` / `snapshotFlow` /
 * `onScroll(...)` the way the platform-agnostic guide in `docs/7. prefetch.md` demonstrates.
 *
 * The binding observes [listState] through `snapshotFlow` and calls
 * [PaginatorPrefetchController.onScroll] with **data-only** indices: any [headerCount] is
 * subtracted from the visible-index range, the last index is clamped to `dataItemCount - 1`,
 * and `totalItemCount` forwarded to the controller equals [dataItemCount]. This is what the
 * controller's contract requires and getting it wrong is a common source of misfires.
 *
 * If the visible viewport sits entirely inside headers (the user hasn't scrolled into data yet)
 * or entirely inside footers, the binding skips the call. The controller's first real call
 * still acts as calibration as documented.
 *
 * Recompositions with new [dataItemCount] / [headerCount] / [footerCount] do **not** restart
 * the underlying flow — the values are read fresh on every emission, so a newly loaded page
 * does not trigger re-calibration. The flow restarts only when the controller identity or the
 * [listState] instance changes.
 *
 * @param listState The Compose lazy-list state to observe.
 * @param dataItemCount The number of **data** items currently rendered (excluding headers,
 *   footers, dividers, sticky labels, and loading indicators). For a [PaginatorPrefetchController]
 *   driven from `paginator.uiState`, this is the size of `PaginatorUiState.Content.items` (or `0`
 *   for any non-`Content` state).
 * @param headerCount Number of non-data items rendered **before** the data range. Used to shift
 *   the visible-index window down to the data origin.
 * @param footerCount Reserved for self-documentation at the call site; the math relies on
 *   [dataItemCount] and [headerCount] only.
 * @param preserveScroll When `true`, the binding additionally saves [listState]'s scroll
 *   position to a process-wide registry when leaving composition, and restores it on
 *   (re)entry. The intended use case is: a screen that owns a long-lived
 *   [PaginatorPrefetchController] (e.g., via DI / `ViewModel`), where backing out to another
 *   screen tears down the composable and Compose forgets the [LazyListState], yet the next
 *   visit should resume at the same item. Defaults to `false` (no behavior change for
 *   existing callers).
 * @param scrollKey Optional stable string key for the scroll snapshot. When `null` (default)
 *   and [preserveScroll] is `true`, the snapshot is keyed by this controller's identity —
 *   simple and collision-free when a new controller naturally replaces the old one. When
 *   non-null, the snapshot is keyed by this string and **also** persisted via
 *   `rememberSaveable` to survive process death. Ignored when [preserveScroll] is `false`.
 */
@OptIn(PaginatorInternalApi::class)
@Composable
fun PaginatorPrefetchController<*>.BindToLazyList(
    listState: LazyListState,
    dataItemCount: Int,
    headerCount: Int = 0,
    @Suppress("UNUSED_PARAMETER") footerCount: Int = 0,
    restartKey: Any? = null,
    scrollSampleMillis: Long = 0L,
    preserveScroll: Boolean = false,
    scrollKey: String? = null,
) {
    val controller = this
    val reader = remember(listState) { ScrollSignalReader { listState.readScrollSignal() } }
    val callback = remember(controller) {
        ScrollCallback { f, l, t -> controller.onScroll(f, l, t) }
    }
    BindScrollInternal(
        controllerKey = controller,
        sourceKey = listState,
        restartKey = restartKey,
        scrollSampleMillis = scrollSampleMillis,
        dataItemCount = dataItemCount,
        headerCount = headerCount,
        reader = reader,
        callback = callback,
    )
    if (preserveScroll) {
        BindLazyListScrollPreservation(
            listState = listState,
            key = scrollKey ?: controller,
            scrollKey = scrollKey,
        )
    }
}
