package com.jamal_aliev.paginator.compose.core.internal

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import com.jamal_aliev.paginator.compose.core.LazyGridScrollSnapshot
import com.jamal_aliev.paginator.compose.core.PaginatorInternalApi
import com.jamal_aliev.paginator.compose.core.ScrollSnapshotRegistry
import kotlinx.coroutines.flow.collect

/** Grid-state counterpart of [BindLazyListScrollPreservation]. See its KDoc for full contract. */
@PaginatorInternalApi
@Composable
public fun BindLazyGridScrollPreservation(
    gridState: LazyGridState,
    key: Any,
    scrollKey: String?,
) {
    val saveableSnapshot = if (scrollKey != null) {
        rememberSaveable(scrollKey, stateSaver = LazyGridScrollSnapshot.Saver) {
            mutableStateOf(LazyGridScrollSnapshot.Empty)
        }
    } else null

    LaunchedEffect(gridState, key) {
        val fromRegistry: LazyGridScrollSnapshot? = ScrollSnapshotRegistry.get(key)
        val fromSaveable: LazyGridScrollSnapshot? = saveableSnapshot?.value
            ?.takeIf { it != LazyGridScrollSnapshot.Empty }
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
                LazyGridScrollSnapshot(
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
                LazyGridScrollSnapshot(
                    firstVisibleItemIndex = gridState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = gridState.firstVisibleItemScrollOffset,
                ),
            )
        }
    }
}
