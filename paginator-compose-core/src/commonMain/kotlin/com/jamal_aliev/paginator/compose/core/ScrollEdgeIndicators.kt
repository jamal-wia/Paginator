package com.jamal_aliev.paginator.compose.core

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jamal_aliev.paginator.core.page.PageState
import com.jamal_aliev.paginator.core.page.PaginatorUiState

/**
 * Wraps a lazy layout with **scroll-anchor-safe** "loading previous / next page" indicators.
 *
 * The indicators sit at the start / end edge of a [Box] around [content] — deliberately **not** as
 * items of the lazy layout. They look like a row after the last (or before the first) item: as a
 * bar animates in, [content] is placed shifted away by the bar's size, and shifts back as it animates
 * out. The lazy viewport keeps a constant size, so the edge detection ([edgeGated]) cannot feed
 * back on the bar itself.
 * A lazy list keeps its scroll position by anchoring on the first visible item's key; if a loading
 * indicator were an item at the edge of the list, it would become that anchor, and when it is
 * replaced by the freshly-loaded page the anchor key disappears and the list "jumps" to the start
 * of the new page. Keeping the indicator out of the list means the anchor stays a real content item,
 * so the list holds its position and the newly loaded previous page is inserted past the edge.
 *
 * Loading state is taken from [PaginatorUiState.Content.prependState] / [appendState][PaginatorUiState.Content.appendState]:
 * the prepend indicator shows while the first visible page is loading, the append indicator while
 * the last visible page is loading. Cached data carried by a reloading page is already part of
 * [PaginatorUiState.Content.items], so it stays visible in [content] while the indicator animates.
 *
 * When [edgeGated] is `true` (default) each indicator is only visible while the list is scrolled to
 * the matching edge (start for prepend, end for append), so it behaves like an inline item that
 * scrolls out of view — without ever participating in scroll anchoring. Set it to `false` for a bar
 * that stays pinned while its page loads.
 *
 * **Boundary errors.** When the first / last visible page failed to load, the matching
 * [prependErrorIndicator] / [appendErrorIndicator] slot is rendered in the same edge position (and
 * gated / animated exactly like the loading bar). It receives the [PageState.ErrorState], so it can
 * read `exception` and offer a retry (e.g. `paginator.goNextPage()` / `goPreviousPage()`). Both
 * slots default to `null` — no error UI, the previous behaviour. Because the error sits outside the
 * lazy layout it never becomes a scroll anchor, so a retry that succeeds does not make the list jump.
 *
 * [scrollState] is any [ScrollableState] — a `LazyListState`, `LazyGridState`, or
 * `LazyStaggeredGridState` — so this composable works with `LazyColumn` / `LazyRow`, grids, and
 * staggered grids alike, in either orientation. It is also strategy-agnostic; use it with either
 * `Paginator` or `CursorPaginator`.
 *
 * ```
 * val uiState by paginator.uiState.collectAsStateWithLifecycle(PaginatorUiState.Idle)
 * val listState = rememberLazyListState()
 * PaginatorScrollEdgeIndicators(uiState, listState, Modifier.fillMaxSize()) {
 *     LazyColumn(Modifier.fillMaxSize(), state = listState) {   // content fills the weighted slot
 *         val content = uiState as? PaginatorUiState.Content ?: return@LazyColumn
 *         items(content.items, key = { it.id }) { ItemRow(it) } // stable keys keep the anchor
 *     }
 * }
 * ```
 *
 * @param uiState the paginator UI state (from `Paginator.uiState` / `CursorPaginator.uiState`).
 * @param scrollState the same scroll state used by the lazy layout inside [content].
 * @param orientation the layout axis of the lazy layout inside [content].
 * @param edgeGated when `true`, show each indicator only while the list is at the matching edge.
 * @param prependIndicator indicator for a loading previous page.
 * @param appendIndicator indicator for a loading next page.
 * @param prependErrorIndicator error UI for a failed previous-page load; `null` renders nothing.
 * @param appendErrorIndicator error UI for a failed next-page load; `null` renders nothing.
 * @param content the lazy layout; give it `Modifier.fillMaxSize()` and the same [scrollState].
 */
@Composable
fun <T> PaginatorScrollEdgeIndicators(
    uiState: PaginatorUiState<T>,
    scrollState: ScrollableState,
    modifier: Modifier = Modifier,
    orientation: Orientation = Orientation.Vertical,
    edgeGated: Boolean = true,
    enter: EnterTransition = defaultEdgeEnter(orientation),
    exit: ExitTransition = defaultEdgeExit(orientation),
    prependIndicator: @Composable (PageState.ProgressState<T>) -> Unit = {
        PaginatorLoadingIndicator(orientation = orientation)
    },
    appendIndicator: @Composable (PageState.ProgressState<T>) -> Unit = {
        PaginatorLoadingIndicator(orientation = orientation)
    },
    prependErrorIndicator: (@Composable (PageState.ErrorState<T>) -> Unit)? = null,
    appendErrorIndicator: (@Composable (PageState.ErrorState<T>) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // A boundary is either loading or failed, never both; an error only counts when a slot for it
    // was provided.
    val prepend: PageState<T>? = uiState.prependProgressState()
        ?: uiState.prependErrorState().takeIf { prependErrorIndicator != null }
    val append: PageState<T>? = uiState.appendProgressState()
        ?: uiState.appendErrorState().takeIf { appendErrorIndicator != null }

    // canScroll* flips only at the edges, so these do not recompose on every scroll frame.
    val atStart by remember(scrollState) { derivedStateOf { !scrollState.canScrollBackward } }
    val atEnd by remember(scrollState) { derivedStateOf { !scrollState.canScrollForward } }

    // Retain the last non-null boundary state so the collapse animation still has content to draw
    // after the page has finished loading and `prepend`/`append` is already null.
    var lastPrepend by remember { mutableStateOf<PageState<T>?>(null) }
    var lastAppend by remember { mutableStateOf<PageState<T>?>(null) }
    if (prepend != null) lastPrepend = prepend
    if (append != null) lastAppend = append

    // Defined outside the Box so `AnimatedVisibility` resolves to the orientation-neutral
    // overload (we pass explicit enter/exit anyway).
    val prependBar: @Composable () -> Unit = {
        AnimatedVisibility(
            visible = prepend != null && (!edgeGated || atStart),
            enter = enter,
            exit = exit,
        ) {
            when (val last = lastPrepend) {
                is PageState.ProgressState -> prependIndicator(last)
                is PageState.ErrorState -> prependErrorIndicator?.invoke(last)
                else -> Unit
            }
        }
    }
    val appendBar: @Composable () -> Unit = {
        AnimatedVisibility(
            visible = append != null && (!edgeGated || atEnd),
            enter = enter,
            exit = exit,
        ) {
            when (val last = lastAppend) {
                is PageState.ProgressState -> appendIndicator(last)
                is PageState.ErrorState -> appendErrorIndicator?.invoke(last)
                else -> Unit
            }
        }
    }

    // The bars take no extra space: the lazy layout keeps a constant size and is only *placed*
    // shifted by the bars' current main-axis size, so a bar still looks like a row that pushes the
    // list away. Resizing the lazy viewport instead would flip canScroll*, hide the bar, grow the
    // viewport back and show the bar again — a visible blink at the edge.
    EdgeIndicatorsLayout(
        vertical = orientation == Orientation.Vertical,
        modifier = modifier,
        content = { Box { content() } },
        prependBar = { Box { prependBar() } },
        appendBar = { Box { appendBar() } },
    )
}

/**
 * Lays out [content] over the full incoming size, pins [prependBar] to the start edge and
 * [appendBar] to the end edge, and places [content] shifted away from whichever bar is currently
 * (partly) visible. The shift is applied at placement, in the same layout pass that measures the
 * bars, so it never lags behind a bar's animation and needs no state. [placeRelative] makes the
 * horizontal axis follow the layout direction.
 */
@Composable
private fun EdgeIndicatorsLayout(
    vertical: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
    prependBar: @Composable () -> Unit,
    appendBar: @Composable () -> Unit,
) {
    Layout(
        content = {
            content()
            prependBar()
            appendBar()
        },
        modifier = modifier.clipToBounds(),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val prepend = measurables[1].measure(loose)
        val append = measurables[2].measure(loose)
        val body = measurables[0].measure(constraints)

        val width = maxOf(constraints.minWidth, body.width)
        val height = maxOf(constraints.minHeight, body.height)
        layout(width, height) {
            if (vertical) {
                body.placeRelative(0, prepend.height - append.height)
                prepend.placeRelative(0, 0)
                append.placeRelative(0, height - append.height)
            } else {
                body.placeRelative(prepend.width - append.width, 0)
                prepend.placeRelative(0, 0)
                append.placeRelative(width - append.width, 0)
            }
        }
    }
}

private fun defaultEdgeEnter(orientation: Orientation): EnterTransition =
    (if (orientation == Orientation.Vertical) expandVertically() else expandHorizontally()) + fadeIn()

private fun defaultEdgeExit(orientation: Orientation): ExitTransition =
    (if (orientation == Orientation.Vertical) shrinkVertically() else shrinkHorizontally()) + fadeOut()

/**
 * The loading progress state at the top of the visible snapshot, or `null` when the top is a
 * success page or the boundary is an error (not a load-in-progress).
 */
internal fun <T> PaginatorUiState<T>.prependProgressState(): PageState.ProgressState<T>? =
    (this as? PaginatorUiState.Content<T>)?.prependState
        ?.let { it as? PageState.ProgressState }

/**
 * The loading progress state at the bottom of the visible snapshot, or `null` when the bottom is a
 * success page or the boundary is an error (not a load-in-progress).
 */
internal fun <T> PaginatorUiState<T>.appendProgressState(): PageState.ProgressState<T>? =
    (this as? PaginatorUiState.Content<T>)?.appendState
        ?.let { it as? PageState.ProgressState }

/**
 * The failed [PageState.ErrorState] at the top of the visible snapshot, or `null` when the top is a
 * success page or is still loading.
 */
internal fun <T> PaginatorUiState<T>.prependErrorState(): PageState.ErrorState<T>? =
    (this as? PaginatorUiState.Content<T>)?.prependState
        ?.let { it as? PageState.ErrorState }

/**
 * The failed [PageState.ErrorState] at the bottom of the visible snapshot, or `null` when the bottom
 * is a success page or is still loading.
 */
internal fun <T> PaginatorUiState<T>.appendErrorState(): PageState.ErrorState<T>? =
    (this as? PaginatorUiState.Content<T>)?.appendState
        ?.let { it as? PageState.ErrorState }

private val DefaultIndicatorColor = Color(0xFF9E9E9E)

/**
 * A dependency-light indeterminate spinner used as the default page-loading indicator. Drawn with
 * `foundation` only (no Material dependency); pass a [color] from your theme to match your app,
 * e.g. `PaginatorLoadingIndicator(color = MaterialTheme.colorScheme.primary)`. [orientation]
 * controls which cross-axis the bar fills (full width for a vertical list, full height for a
 * horizontal one).
 */
@Composable
fun PaginatorLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = DefaultIndicatorColor,
    diameter: Dp = 20.dp,
    strokeWidth: Dp = 2.5.dp,
    orientation: Orientation = Orientation.Vertical,
) {
    val transition = rememberInfiniteTransition(label = "PaginatorLoadingIndicator")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "rotation",
    )
    Box(
        modifier = modifier
            .then(
                if (orientation == Orientation.Vertical) Modifier.fillMaxWidth()
                else Modifier.fillMaxHeight()
            )
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(diameter)) {
            val stroke = strokeWidth.toPx()
            drawArc(
                color = color,
                startAngle = rotation,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
            )
        }
    }
}
