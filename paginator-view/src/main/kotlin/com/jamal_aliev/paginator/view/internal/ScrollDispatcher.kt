package com.jamal_aliev.paginator.view.internal

import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.jamal_aliev.paginator.prefetch.VisibleDataRange
import com.jamal_aliev.paginator.view.ScrollBinding
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Internal dispatcher shared by every [com.jamal_aliev.paginator.view.bindToRecyclerView]
 * overload. Owns the listener lifecycle, the dedup state, optional throttling, and the
 * lifecycle-bound teardown — keeping the public extension surface a thin facade.
 */
internal class ScrollDispatcher(
    private val recyclerView: RecyclerView,
    private val lifecycleOwner: LifecycleOwner,
    private val scrollSampleMillis: Long,
    private val dataItemCount: () -> Int,
    private val headerCount: () -> Int,
    private val onScroll: (firstVisibleIndex: Int, lastVisibleIndex: Int, totalItemCount: Int) -> Unit,
) : ScrollBinding {

    private var lastEmitted: ScrollSignal? = null
    private var disposed: Boolean = false

    private val signals = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
            tick()
        }
    }

    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        tick()
    }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            unbind()
        }
    }

    private var collectorJob: Job? = null

    @OptIn(FlowPreview::class)
    fun start() {
        if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            disposed = true
            return
        }
        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.addOnLayoutChangeListener(layoutListener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        collectorJob = lifecycleOwner.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val source =
                if (scrollSampleMillis > 0L) signals.sample(scrollSampleMillis) else signals
            source.collectLatest { dispatch() }
        }
        // Drive an initial pass so a first page that's shorter than the viewport doesn't stall.
        tick()
    }

    private fun tick() {
        if (disposed) return
        signals.tryEmit(Unit)
    }

    private fun dispatch() {
        if (disposed) return
        if (recyclerView.layoutManager == null) return
        val signal = recyclerView.readScrollSignal()
        if (signal == lastEmitted) return
        val range = VisibleDataRange.from(
            firstVisibleIndex = signal.firstVisibleIndex,
            lastVisibleIndex = signal.lastVisibleIndex,
            dataItemCount = dataItemCount(),
            dataOffset = headerCount(),
        )
        lastEmitted = signal
        if (!range.isNone) {
            onScroll(
                range.firstVisibleIndex,
                range.lastVisibleIndex,
                dataItemCount(),
            )
        }
    }

    override fun recalibrate() {
        if (disposed) return
        lastEmitted = null
        tick()
    }

    override fun unbind() {
        if (disposed) return
        disposed = true
        recyclerView.removeOnScrollListener(scrollListener)
        recyclerView.removeOnLayoutChangeListener(layoutListener)
        lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        collectorJob?.cancel()
        collectorJob = null
    }
}
