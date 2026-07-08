package com.jamal_aliev.paginator.compose.core

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jamal_aliev.paginator.core.page.PageState
import com.jamal_aliev.paginator.core.page.PaginatorUiState

/**
 * Wraps a lazy list with **scroll-anchor-safe** "loading previous / next page" indicators.
 *
 * The indicators are rendered as siblings of [content] inside a [Column] — deliberately **not**
 * as items of the lazy list. A `LazyColumn` keeps its scroll position by anchoring on the first
 * visible item's key; if a loading indicator were an item at the top of the list, it would become
 * that anchor, and when it is replaced by the freshly-loaded page the anchor key disappears and
 * the list "jumps" to the top of the new page. Keeping the indicator out of the list means the
 * anchor stays a real content row, so the list holds its position and the newly loaded previous
 * page is inserted above the fold.
 *
 * Loading state is taken from [PaginatorUiState.Content.prependState] / [appendState][PaginatorUiState.Content.appendState]:
 * the prepend indicator shows while the first visible page is loading, the append indicator while
 * the last visible page is loading. Cached data carried by a reloading page is already part of
 * [PaginatorUiState.Content.items], so it stays visible in [content] while the indicator animates.
 *
 * When [edgeGated] is `true` (default) each indicator is only visible while the list is scrolled to
 * the matching edge (top for prepend, bottom for append), so it behaves like an inline item that
 * scrolls out of view — without ever participating in scroll anchoring. Set it to `false` for a bar
 * that stays pinned while its page loads.
 *
 * Error / retry states at the boundaries are intentionally **not** rendered here — an error is
 * usually a tappable inline element; render it inside [content] (or observe `uiState` directly).
 * This composable is strategy-agnostic; use it with either `Paginator` or `CursorPaginator`.
 *
 * ```
 * val uiState by paginator.uiState.collectAsState(PaginatorUiState.Idle)
 * val listState = rememberLazyListState()
 * PaginatorScrollEdgeIndicators(uiState, listState, Modifier.fillMaxSize()) {
 *     LazyColumn(Modifier.weight(1f), state = listState) {
 *         val content = uiState as? PaginatorUiState.Content ?: return@LazyColumn
 *         items(content.items, key = { it.id }) { ItemRow(it) } // stable keys keep the anchor
 *     }
 * }
 * ```
 *
 * @param uiState the paginator UI state (from `Paginator.uiState` / `CursorPaginator.uiState`).
 * @param listState the same [LazyListState] used by the lazy list inside [content].
 * @param edgeGated when `true`, show each indicator only while the list is at the matching edge.
 * @param prependIndicator indicator for a loading previous page.
 * @param appendIndicator indicator for a loading next page.
 * @param content the lazy list; give it `Modifier.weight(1f)` and the same [listState].
 */
@Composable
fun <T> PaginatorScrollEdgeIndicators(
    uiState: PaginatorUiState<T>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    edgeGated: Boolean = true,
    enter: EnterTransition = expandVertically() + fadeIn(),
    exit: ExitTransition = shrinkVertically() + fadeOut(),
    prependIndicator: @Composable (PageState.ProgressState<T>) -> Unit = { PaginatorLoadingIndicator() },
    appendIndicator: @Composable (PageState.ProgressState<T>) -> Unit = { PaginatorLoadingIndicator() },
    content: @Composable ColumnScope.() -> Unit,
) {
    val prepend = uiState.prependProgressState()
    val append = uiState.appendProgressState()

    // canScroll* flips only at the edges, so these do not recompose on every scroll frame.
    val atTop by remember(listState) { derivedStateOf { !listState.canScrollBackward } }
    val atBottom by remember(listState) { derivedStateOf { !listState.canScrollForward } }

    // Retain the last non-null progress state so the collapse animation still has content to draw
    // after the page has finished loading and `prepend`/`append` is already null.
    var lastPrepend by remember { mutableStateOf<PageState.ProgressState<T>?>(null) }
    var lastAppend by remember { mutableStateOf<PageState.ProgressState<T>?>(null) }
    if (prepend != null) lastPrepend = prepend
    if (append != null) lastAppend = append

    Column(modifier) {
        AnimatedVisibility(
            visible = prepend != null && (!edgeGated || atTop),
            enter = enter,
            exit = exit,
        ) {
            lastPrepend?.let { prependIndicator(it) }
        }

        content()

        AnimatedVisibility(
            visible = append != null && (!edgeGated || atBottom),
            enter = enter,
            exit = exit,
        ) {
            lastAppend?.let { appendIndicator(it) }
        }
    }
}

/**
 * The loading progress state at the top of the visible snapshot, or `null` when the top is a
 * success page or the boundary is an error (not a load-in-progress).
 */
internal fun <T> PaginatorUiState<T>.prependProgressState(): PageState.ProgressState<T>? =
    (this as? PaginatorUiState.Content<T>)?.prependState
        ?.let { if (it is PageState.ProgressState) it else null }

/**
 * The loading progress state at the bottom of the visible snapshot, or `null` when the bottom is a
 * success page or the boundary is an error (not a load-in-progress).
 */
internal fun <T> PaginatorUiState<T>.appendProgressState(): PageState.ProgressState<T>? =
    (this as? PaginatorUiState.Content<T>)?.appendState
        ?.let { if (it is PageState.ProgressState) it else null }

private val DefaultIndicatorColor = Color(0xFF9E9E9E)

/**
 * A dependency-light indeterminate spinner used as the default page-loading indicator. Drawn with
 * `foundation` only (no Material dependency); pass a [color] from your theme to match your app,
 * e.g. `PaginatorLoadingIndicator(color = MaterialTheme.colorScheme.primary)`.
 */
@Composable
fun PaginatorLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = DefaultIndicatorColor,
    diameter: Dp = 20.dp,
    strokeWidth: Dp = 2.5.dp,
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
            .fillMaxWidth()
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
