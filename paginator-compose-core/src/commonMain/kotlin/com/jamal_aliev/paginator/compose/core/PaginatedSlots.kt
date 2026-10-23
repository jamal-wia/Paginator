package com.jamal_aliev.paginator.compose.core

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import com.jamal_aliev.paginator.core.page.PageState
import com.jamal_aliev.paginator.core.page.PaginatorUiState

/**
 * The state slots of the turnkey paginated lists and grids (`PaginatedLazyColumn`,
 * `PaginatedLazyRow`, `PaginatedLazyVerticalGrid`, … in `paginator-compose-offset` /
 * `paginator-compose-cursor`), grouped so the components keep a short parameter list.
 *
 * Two kinds of slots:
 *
 * - **Edge indicators** — drawn outside the list at the matching edge by
 *   [PaginatorScrollEdgeIndicators], so a page load never jumps the scroll:
 *   [prependIndicator] / [appendIndicator] while the previous / next page loads,
 *   [prependErrorIndicator] / [appendErrorIndicator] when it failed.
 * - **Full-screen states** — replace the whole list: [loadingContent] for the first load,
 *   [emptyContent] when the source returned nothing, [errorContent] when the first load failed.
 *
 * Every slot is optional. A `null` loading indicator means the default [PaginatorLoadingIndicator]
 * for the list's orientation; pass `{}` to show nothing. A `null` error indicator or full-screen
 * slot means that state is not rendered specially.
 *
 * ```
 * PaginatedLazyColumn(
 *     paginator,
 *     key = { it.id },
 *     slots = PaginatedSlots(
 *         appendErrorIndicator = { error -> RetryRow(error.exception) { retry() } },
 *         emptyContent = { EmptyFeed() },
 *     ),
 * ) { item, _, _, _, _ -> ItemRow(item) }
 * ```
 *
 * Equality compares the slot lambdas, so a `PaginatedSlots(...)` built in composition with the
 * same (remembered) lambdas does not force the list to recompose.
 */
@Immutable
class PaginatedSlots<T>(
    val prependIndicator: (@Composable (PageState.ProgressState<T>) -> Unit)? = null,
    val appendIndicator: (@Composable (PageState.ProgressState<T>) -> Unit)? = null,
    val prependErrorIndicator: (@Composable (PageState.ErrorState<T>) -> Unit)? = null,
    val appendErrorIndicator: (@Composable (PageState.ErrorState<T>) -> Unit)? = null,
    val loadingContent: (@Composable () -> Unit)? = null,
    val emptyContent: (@Composable () -> Unit)? = null,
    val errorContent: (@Composable (PageState.ErrorState<T>) -> Unit)? = null,
) {

    /** Returns a copy with the given slots replaced. */
    fun copy(
        prependIndicator: (@Composable (PageState.ProgressState<T>) -> Unit)? = this.prependIndicator,
        appendIndicator: (@Composable (PageState.ProgressState<T>) -> Unit)? = this.appendIndicator,
        prependErrorIndicator: (@Composable (PageState.ErrorState<T>) -> Unit)? = this.prependErrorIndicator,
        appendErrorIndicator: (@Composable (PageState.ErrorState<T>) -> Unit)? = this.appendErrorIndicator,
        loadingContent: (@Composable () -> Unit)? = this.loadingContent,
        emptyContent: (@Composable () -> Unit)? = this.emptyContent,
        errorContent: (@Composable (PageState.ErrorState<T>) -> Unit)? = this.errorContent,
    ): PaginatedSlots<T> = PaginatedSlots(
        prependIndicator = prependIndicator,
        appendIndicator = appendIndicator,
        prependErrorIndicator = prependErrorIndicator,
        appendErrorIndicator = appendErrorIndicator,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PaginatedSlots<*>) return false
        return prependIndicator == other.prependIndicator &&
                appendIndicator == other.appendIndicator &&
                prependErrorIndicator == other.prependErrorIndicator &&
                appendErrorIndicator == other.appendErrorIndicator &&
                loadingContent == other.loadingContent &&
                emptyContent == other.emptyContent &&
                errorContent == other.errorContent
    }

    override fun hashCode(): Int {
        var result = prependIndicator.hashCode()
        result = 31 * result + appendIndicator.hashCode()
        result = 31 * result + prependErrorIndicator.hashCode()
        result = 31 * result + appendErrorIndicator.hashCode()
        result = 31 * result + loadingContent.hashCode()
        result = 31 * result + emptyContent.hashCode()
        result = 31 * result + errorContent.hashCode()
        return result
    }

    override fun toString(): String =
        "PaginatedSlots(prependIndicator=${prependIndicator != null}, " +
                "appendIndicator=${appendIndicator != null}, " +
                "prependErrorIndicator=${prependErrorIndicator != null}, " +
                "appendErrorIndicator=${appendErrorIndicator != null}, " +
                "loadingContent=${loadingContent != null}, emptyContent=${emptyContent != null}, " +
                "errorContent=${errorContent != null})"
}

/**
 * [PaginatorScrollEdgeIndicators] driven by the edge slots of [slots]; a `null` loading indicator
 * falls back to [PaginatorLoadingIndicator] for [orientation]. The full-screen slots are not used
 * here — the caller renders them instead of the list.
 */
@Composable
fun <T> PaginatorScrollEdgeIndicators(
    uiState: PaginatorUiState<T>,
    scrollState: ScrollableState,
    slots: PaginatedSlots<T>,
    modifier: Modifier = Modifier,
    orientation: Orientation = Orientation.Vertical,
    edgeGated: Boolean = true,
    content: @Composable () -> Unit,
) {
    PaginatorScrollEdgeIndicators(
        uiState = uiState,
        scrollState = scrollState,
        modifier = modifier,
        orientation = orientation,
        edgeGated = edgeGated,
        prependIndicator = slots.prependIndicator ?: { PaginatorLoadingIndicator(orientation = orientation) },
        appendIndicator = slots.appendIndicator ?: { PaginatorLoadingIndicator(orientation = orientation) },
        prependErrorIndicator = slots.prependErrorIndicator,
        appendErrorIndicator = slots.appendErrorIndicator,
        content = content,
    )
}
