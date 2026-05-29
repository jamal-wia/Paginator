package com.jamal_aliev.paginator.compose.core.internal

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import com.jamal_aliev.paginator.compose.core.LazyListScrollSnapshot
import com.jamal_aliev.paginator.compose.core.PaginatorInternalApi
import com.jamal_aliev.paginator.compose.core.ScrollSnapshotRegistry
import kotlinx.coroutines.flow.collect

/**
 * Save the current `LazyListState` scroll position when leaving composition, restore it on
 * (re)entry. Backs the `preserveScroll = true` parameter of `BindToLazyList` in
 * `paginator-compose-offset` and `paginator-compose-cursor`.
 *
 * The save/restore key is [key]. Two sources of truth are consulted on entry, in priority order:
 *
 *  1. [ScrollSnapshotRegistry] — process-wide in-memory map. This is the canonical source for
 *     the in-process navigation cycle (the SourceChew kind of "search → results → back →
 *     same search → results").
 *  2. `rememberSaveable` value — only populated when [scrollKey] is non-null. This is the
 *     fallback for process death: the in-memory registry is gone, but Compose restored the
 *     saved value into the `mutableStateOf`.
 *
 * Out-of-range guard: if the saved index is beyond the current `layoutInfo.totalItemsCount`,
 * the restore is **silently skipped** and the list stays at position 0. Matches the chosen UX:
 * never silently land the user in an unexpected place.
 *
 * @param listState The list state to mirror.
 * @param key Identity-or-string key for the in-memory registry entry. Typically the calling
 *   `PaginatorPrefetchController` (identity key) when the caller did not provide a `scrollKey`,
 *   or the `scrollKey` string itself when they did.
 * @param scrollKey Optional stable string key. When non-null, also persists the position via
 *   `rememberSaveable` so the snapshot survives process death.
 */
@PaginatorInternalApi
@Composable
public fun BindLazyListScrollPreservation(
    listState: LazyListState,
    key: Any,
    scrollKey: String?,
) {
    val saveableSnapshot = if (scrollKey != null) {
        rememberSaveable(scrollKey, stateSaver = LazyListScrollSnapshot.Saver) {
            mutableStateOf(LazyListScrollSnapshot.Empty)
        }
    } else null

    LaunchedEffect(listState, key) {
        val fromRegistry: LazyListScrollSnapshot? = ScrollSnapshotRegistry.get(key)
        val fromSaveable: LazyListScrollSnapshot? = saveableSnapshot?.value
            ?.takeIf { it != LazyListScrollSnapshot.Empty }
        val snapshot = fromRegistry ?: fromSaveable ?: return@LaunchedEffect
        if (snapshot.firstVisibleItemIndex < listState.layoutInfo.totalItemsCount) {
            listState.scrollToItem(
                snapshot.firstVisibleItemIndex,
                snapshot.firstVisibleItemScrollOffset,
            )
        }
    }

    if (saveableSnapshot != null) {
        LaunchedEffect(listState, scrollKey) {
            snapshotFlow {
                LazyListScrollSnapshot(
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                )
            }.collect { saveableSnapshot.value = it }
        }
    }

    DisposableEffect(listState, key) {
        onDispose {
            ScrollSnapshotRegistry.put(
                key,
                LazyListScrollSnapshot(
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                ),
            )
        }
    }
}
