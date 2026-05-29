package com.jamal_aliev.paginator.view.core

import android.os.Parcelable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide in-memory store for `RecyclerView` scroll positions. The View counterpart of
 * `paginator-compose-core`'s `ScrollSnapshotRegistry`.
 *
 * Snapshots are `Parcelable`s produced by `RecyclerView.LayoutManager.onSaveInstanceState()` —
 * the same payload Android uses for view-state restoration. Storing the LayoutManager-produced
 * Parcelable instead of a homegrown (index, offset) pair means we preserve everything the
 * LayoutManager already knows (e.g., anchor item, span info for `StaggeredGridLayoutManager`)
 * without having to recreate it.
 *
 * Storage is a [MutableStateFlow]`<Map<Any, Parcelable>>` updated through
 * [MutableStateFlow.update] — a lock-free CAS loop. No `synchronized` blocks, no thread
 * blocking; consistent with the rest of the coroutines-first Paginator suite. The map is
 * tiny in practice (one entry per active `RecyclerView` binding), so functional updates are
 * cheap.
 *
 * Entries survive for the entire process lifetime. Eviction is the caller's responsibility via
 * [clearRecyclerScrollSnapshot] / [clearAllRecyclerScrollSnapshots].
 */
public object RecyclerViewScrollSnapshotRegistry {

    private val state: MutableStateFlow<Map<Any, Parcelable>> = MutableStateFlow(emptyMap())

    public operator fun get(key: Any): Parcelable? = state.value[key]

    public operator fun set(key: Any, snapshot: Parcelable) {
        state.update { it + (key to snapshot) }
    }

    public fun remove(key: Any) {
        state.update { if (key in it) it - key else it }
    }

    public fun clear() {
        state.update { emptyMap() }
    }
}

/**
 * Remove the saved scroll snapshot for [key] from the registry. Call this when a logical reset
 * happens (e.g., the user explicitly refreshes, the search query changes, the underlying
 * paginator is released) and the saved position no longer makes sense.
 *
 * No-op if no snapshot is registered for [key]. Safe to call from any thread.
 *
 * If [key] is the calling `PaginatorPrefetchController` (the default identity key used when
 * `scrollKey == null`), eviction usually happens implicitly: as soon as the controller is GC'd,
 * its entry is unreachable. Call this method explicitly only when [key] is a string the
 * application chose deliberately.
 */
public fun clearRecyclerScrollSnapshot(key: Any) {
    RecyclerViewScrollSnapshotRegistry.remove(key)
}

/**
 * Wipe the entire RecyclerView scroll-snapshot registry. Intended for tests; in production code
 * prefer the targeted [clearRecyclerScrollSnapshot].
 */
public fun clearAllRecyclerScrollSnapshots() {
    RecyclerViewScrollSnapshotRegistry.clear()
}
