package com.jamal_aliev.paginator.interweave

import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PaginatorUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Interweaves meta-elements ("decorations") into the items of every
 * [PaginatorUiState.Content] emitted by this flow, producing a flow of
 * `PaginatorUiState<WovenEntry<T, I>>`.
 *
 * States that do not carry item data ([PaginatorUiState.Idle],
 * [PaginatorUiState.Loading], [PaginatorUiState.Empty], [PaginatorUiState.Error])
 * pass through structurally unchanged. For [PaginatorUiState.Content], the
 * `items` list is transformed by [weave] and any non-null `prependState` /
 * `appendState` has every element of its `data` wrapped in [WovenEntry.Data]
 * — so the boundary indicator still surfaces the previously visible items
 * (e.g. during a reload of a partial page) and the UI can render them with
 * the same dispatch logic it uses for regular content rows.
 *
 * This operator is the **only** public integration point of the interweaving
 * feature. It does not touch the paginator core, cache, CRUD, serialization,
 * or DSL. Use it only where you want marker rows; leave `uiState` alone
 * everywhere else.
 *
 * Example:
 * ```kotlin
 * paginator.uiState
 *     .interweave(
 *         interweaver<Message, DateLabel> {
 *             itemKey { it.id }
 *             decorationKey { _, _, next -> "date-${next?.date?.toLocalDate()}" }
 *             between { prev, next ->
 *                 val p = prev?.date?.toLocalDate()
 *                 val n = next?.date?.toLocalDate()
 *                 if (p != n && n != null) DateLabel(n) else null
 *             }
 *         }
 *     )
 *     .onEach(::render)
 *     .launchIn(viewModelScope)
 * ```
 */
fun <T, I> Flow<PaginatorUiState<T>>.interweave(
    weaver: Interweaver<T, I>,
): Flow<PaginatorUiState<WovenEntry<T, I>>> = map { it.interweave(weaver) }

/**
 * Interweaves meta-elements into a single [PaginatorUiState] snapshot. See the
 * flow-operator overload for the full contract.
 */
fun <T, I> PaginatorUiState<T>.interweave(
    weaver: Interweaver<T, I>,
): PaginatorUiState<WovenEntry<T, I>> = when (this) {
    PaginatorUiState.Idle -> PaginatorUiState.Idle
    is PaginatorUiState.Loading -> this
    is PaginatorUiState.Empty -> this
    is PaginatorUiState.Error -> this
    is PaginatorUiState.Content -> PaginatorUiState.Content(
        prependState = prependState?.wrapData(weaver),
        items = items.weave(weaver),
        appendState = appendState?.wrapData(weaver),
    )
}

/**
 * Core projection: walks [this] once and emits [WovenEntry.Data] for every item,
 * consulting [Interweaver.between] at each adjacent pair and (optionally) at the
 * leading / trailing edges to emit [WovenEntry.Decoration] rows.
 *
 * Public so callers who manage their own `List<T>` (outside of `uiState`) can
 * still reuse the same weaving logic.
 */
fun <T, I> List<T>.weave(weaver: Interweaver<T, I>): List<WovenEntry<T, I>> {
    if (isEmpty()) return emptyList()

    val out = ArrayList<WovenEntry<T, I>>(size * 2)

    if (weaver.emitLeading) {
        val first = this[0]
        weaver.between(prev = null, next = first)?.let { decoration ->
            out += WovenEntry.Decoration(
                value = decoration,
                wovenKey = weaver.decorationKey(decoration, null, first),
            )
        }
    }

    var prev: T? = null
    for (next in this) {
        val capturedPrev = prev
        if (capturedPrev != null) {
            weaver.between(capturedPrev, next)?.let { decoration ->
                out += WovenEntry.Decoration(
                    value = decoration,
                    wovenKey = weaver.decorationKey(decoration, capturedPrev, next),
                )
            }
        }
        out += WovenEntry.Data(value = next, wovenKey = weaver.itemKey(next))
        prev = next
    }

    if (weaver.emitTrailing) {
        val last = prev
        if (last != null) {
            weaver.between(prev = last, next = null)?.let { decoration ->
                out += WovenEntry.Decoration(
                    value = decoration,
                    wovenKey = weaver.decorationKey(decoration, last, null),
                )
            }
        }
    }
    return out
}

private fun <T, I> PageState<T>.wrapData(
    weaver: Interweaver<T, I>,
): PageState<WovenEntry<T, I>> {
    val wrapped: List<WovenEntry<T, I>> = data.map { item ->
        WovenEntry.Data(value = item, wovenKey = weaver.itemKey(item))
    }
    return when (this) {
        is PageState.EmptyPage -> PageState.EmptyPage(page, wrapped, metadata, id)
        is PageState.SuccessPage -> PageState.SuccessPage(page, wrapped, metadata, id)
        is PageState.ProgressPage -> PageState.ProgressPage(page, wrapped, metadata, id)
        is PageState.ErrorPage -> PageState.ErrorPage(exception, page, wrapped, metadata, id)
    }
}
