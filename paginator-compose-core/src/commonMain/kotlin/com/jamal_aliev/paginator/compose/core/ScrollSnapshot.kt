package com.jamal_aliev.paginator.compose.core

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * Snapshot of a `LazyListState` scroll position — `firstVisibleItemIndex` and
 * `firstVisibleItemScrollOffset`. Persisted across navigation cycles (via
 * [ScrollSnapshotRegistry]) and optionally across process death (via [Saver]) when the user
 * passes `BindToLazyList(preserveScroll = true, scrollKey = "...")`.
 *
 * Indices are **UI indices** as the LazyList sees them — they include headers/footers. The
 * binding writes the raw `LazyListState.firstVisibleItemIndex`/`scrollOffset` here and restores
 * them with `scrollToItem(...)` of the same values, so any header/footer offset is preserved
 * exactly as long as the screen renders the same number of leading items.
 */
@Immutable
public data class LazyListScrollSnapshot(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
) {
    public companion object {
        public val Empty: LazyListScrollSnapshot = LazyListScrollSnapshot(0, 0)

        public val Saver: Saver<LazyListScrollSnapshot, Any> = listSaver(
            save = { listOf(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset) },
            restore = { LazyListScrollSnapshot(it[0], it[1]) },
        )
    }
}

/** Snapshot of a `LazyGridState` scroll position. See [LazyListScrollSnapshot]. */
@Immutable
public data class LazyGridScrollSnapshot(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
) {
    public companion object {
        public val Empty: LazyGridScrollSnapshot = LazyGridScrollSnapshot(0, 0)

        public val Saver: Saver<LazyGridScrollSnapshot, Any> = listSaver(
            save = { listOf(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset) },
            restore = { LazyGridScrollSnapshot(it[0], it[1]) },
        )
    }
}

/** Snapshot of a `LazyStaggeredGridState` scroll position. See [LazyListScrollSnapshot]. */
@Immutable
public data class LazyStaggeredGridScrollSnapshot(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
) {
    public companion object {
        public val Empty: LazyStaggeredGridScrollSnapshot = LazyStaggeredGridScrollSnapshot(0, 0)

        public val Saver: Saver<LazyStaggeredGridScrollSnapshot, Any> = listSaver(
            save = { listOf(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset) },
            restore = { LazyStaggeredGridScrollSnapshot(it[0], it[1]) },
        )
    }
}
