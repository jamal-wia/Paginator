package com.jamal_aliev.paginator.compose.core.internal

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import com.jamal_aliev.paginator.compose.core.LazyStaggeredGridScrollSnapshot
import com.jamal_aliev.paginator.compose.core.PaginatorInternalApi
import com.jamal_aliev.paginator.compose.core.ScrollSnapshotRegistry
import kotlinx.coroutines.flow.collect

/**
 * Staggered-grid counterpart of [BindLazyListScrollPreservation]. See its KDoc for full contract.
 *
 * Note: staggered grids re-order items across lanes, so `firstVisibleItemIndex` may map to a
 * visually different cell when lane widths or item heights change between save and restore.
 * The math is best-effort — a saved index inside the data range will always result in a
 * sensible restore, but pixel-perfect resume is not guaranteed on layout-changed re-entry.
 */
@PaginatorInternalApi
@Composable
public fun BindLazyStaggeredGridScrollPreservation(
    gridState: LazyStaggeredGridState,
    key: Any,
    scrollKey: String?,
) {
    val saveableSnapshot = if (scrollKey != null) {
        rememberSaveable(scrollKey, stateSaver = LazyStaggeredGridScrollSnapshot.Saver) {
            mutableStateOf(LazyStaggeredGridScrollSnapshot.Empty)
        }
    } else null

    LaunchedEffect(gridState, key) {
        val fromRegistry: LazyStaggeredGridScrollSnapshot? = ScrollSnapshotRegistry.get(key)
        val fromSaveable: LazyStaggeredGridScrollSnapshot? = saveableSnapshot?.value
            ?.takeIf { it != LazyStaggeredGridScrollSnapshot.Empty }
        val snapshot = fromRegistry ?: fromSaveable ?: return@LaunchedEffect
        if (snapshot.firstVisibleItemIndex < gridState.layoutInfo.totalItemsCount) {
            gridState.scrollToItem(
                snapshot.firstVisibleItemIndex,
                snapshot.firstVisibleItemScrollOffset,
            )
        }
    }

    if (saveableSnapshot != null) {
        LaunchedEffect(gridState, scrollKey) {
            snapshotFlow {
                LazyStaggeredGridScrollSnapshot(
                    firstVisibleItemIndex = gridState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = gridState.firstVisibleItemScrollOffset,
                )
            }.collect { saveableSnapshot.value = it }
        }
    }

    DisposableEffect(gridState, key) {
        onDispose {
            ScrollSnapshotRegistry.put(
                key,
                LazyStaggeredGridScrollSnapshot(
                    firstVisibleItemIndex = gridState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = gridState.firstVisibleItemScrollOffset,
                ),
            )
        }
    }
}
