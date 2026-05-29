package com.jamal_aliev.paginator.view.core.internal

import android.os.Bundle
import android.os.Parcelable
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.jamal_aliev.paginator.view.core.RecyclerViewScrollSnapshotRegistry

/**
 * Restore handle returned by [attachScrollPreservation]. Bridges the in-memory snapshot
 * registry with the host's `Bundle`-based view-state restoration so the calling
 * `bindToRecyclerView(..., preserveScroll = true)` family can transparently expose the
 * `saveScrollState` / `restoreScrollState` methods on its returned `ScrollBinding`.
 *
 * If `scrollKey` was null at attach time, [saveScrollState] / [restoreScrollState] are no-ops
 * (the in-memory entry is keyed by an identity reference that has no Bundle representation).
 */
public class RecyclerViewScrollPreservationHandle internal constructor(
    private val recyclerView: RecyclerView,
    private val registryKey: Any,
    private val scrollKey: String?,
) {

    /**
     * Persist the current scroll state into [outState] under [bundleKey], for surviving process
     * death. The host (`Activity` / `Fragment`) is responsible for calling this from
     * `onSaveInstanceState` and the matching [restoreScrollState] from `onCreate` /
     * `onViewStateRestored`.
     *
     * No-op when the attach call did not supply a `scrollKey` — in that case the in-memory
     * registry is keyed by an identity reference and cannot survive process death anyway.
     */
    public fun saveScrollState(outState: Bundle, bundleKey: String) {
        if (scrollKey == null) return
        val state = recyclerView.layoutManager?.onSaveInstanceState() ?: return
        outState.putParcelable(bundleKey, state)
    }

    /**
     * Restore a scroll state previously written by [saveScrollState] into the in-memory
     * registry. The next attach (or the current one, if the RecyclerView is already populated)
     * will pick it up and apply it to the `LayoutManager`.
     *
     * No-op when the attach call did not supply a `scrollKey`.
     */
    @Suppress("DEPRECATION")
    public fun restoreScrollState(state: Bundle, bundleKey: String) {
        if (scrollKey == null) return
        val parcel: Parcelable = state.getParcelable(bundleKey) ?: return
        RecyclerViewScrollSnapshotRegistry[registryKey] = parcel
    }
}

/**
 * Wire scroll preservation onto [recyclerView]:
 *
 *  - On every `ON_STOP` of [lifecycleOwner], snapshot the current `LayoutManager` state into
 *    the [RecyclerViewScrollSnapshotRegistry] under [key]. `ON_STOP` is the right
 *    Android-lifecycle hook: it fires when the activity/fragment is no longer visible (back
 *    navigation, app backgrounded) but the host is still alive enough to read view state.
 *  - Immediately, **and** on the next layout pass, attempt to restore a previously saved
 *    snapshot. Restoring before the adapter has populated produces no visible effect; the
 *    `doOnNextLayout` pass catches that case.
 *
 * Returns a handle that the calling `bindToRecyclerView` wrapper can hand back to user code
 * so they can save/restore through their host's Bundle.
 */
public fun attachScrollPreservation(
    recyclerView: RecyclerView,
    lifecycleOwner: LifecycleOwner,
    key: Any,
    scrollKey: String?,
): RecyclerViewScrollPreservationHandle {
    val saved = RecyclerViewScrollSnapshotRegistry[key]
    if (saved != null) {
        val lm = recyclerView.layoutManager
        if (lm != null && lm.itemCount > 0) {
            lm.onRestoreInstanceState(saved)
        } else {
            recyclerView.doOnNextLayout {
                val current = RecyclerViewScrollSnapshotRegistry[key] ?: return@doOnNextLayout
                recyclerView.layoutManager?.onRestoreInstanceState(current)
            }
        }
    }

    val observer = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            val state = recyclerView.layoutManager?.onSaveInstanceState() ?: return
            RecyclerViewScrollSnapshotRegistry[key] = state
        }

        override fun onDestroy(owner: LifecycleOwner) {
            lifecycleOwner.lifecycle.removeObserver(this)
        }
    }
    lifecycleOwner.lifecycle.addObserver(observer)

    return RecyclerViewScrollPreservationHandle(
        recyclerView = recyclerView,
        registryKey = key,
        scrollKey = scrollKey,
    )
}
