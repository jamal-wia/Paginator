package com.jamal_aliev.paginator.view.internal

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jamal_aliev.paginator.CursorPaginator
import com.jamal_aliev.paginator.Paginator
import com.jamal_aliev.paginator.extension.uiState
import com.jamal_aliev.paginator.page.PaginatorUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reactive tracker that exposes a synchronous [count] reader and a [signal] that fires whenever
 * the data item count changes. The count is sourced from the paginator's
 * [com.jamal_aliev.paginator.extension.uiState] flow — only the integer derived from
 * [PaginatorUiState.Content.items] size is observed, transient loading / error states do not
 * cause spurious changes thanks to [distinctUntilChanged].
 *
 * Collection runs on `lifecycleOwner.repeatOnLifecycle(STARTED)`, so the tracker is paused while
 * the screen is in the background and resumed automatically. This matches the lifecycle-safe
 * collection pattern recommended by the AndroidX team.
 *
 * @param onChanged Callback invoked **on the main thread** whenever the count changes. Used by
 *   the binding to drive a re-dispatch (so a freshly loaded first page that was empty before
 *   triggers a recompute without waiting for a scroll event).
 */
internal class DataItemCountTracker(
    private val source: Flow<Int>,
    private val lifecycleOwner: LifecycleOwner,
    private val onChanged: () -> Unit,
) {
    private val countRef = AtomicInteger(0)

    /** Most recent observed count. Read on every dispatch; never throws. */
    val count: () -> Int = { countRef.get() }

    fun start() {
        if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) return
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                source
                    .map { it.coerceAtLeast(0) }
                    .distinctUntilChanged()
                    .collect { value ->
                        countRef.set(value)
                        onChanged()
                    }
            }
        }
    }

    companion object {
        fun forPaginator(paginator: Paginator<*>): Flow<Int> =
            paginator.uiState.map { dataItemCountOf(it) }

        fun forCursorPaginator(paginator: CursorPaginator<*>): Flow<Int> =
            paginator.uiState.map { dataItemCountOf(it) }

        private fun dataItemCountOf(state: PaginatorUiState<*>): Int =
            (state as? PaginatorUiState.Content<*>)?.items?.size ?: 0
    }
}
