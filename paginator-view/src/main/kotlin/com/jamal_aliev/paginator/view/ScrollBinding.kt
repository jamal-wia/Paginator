package com.jamal_aliev.paginator.view

/**
 * Handle returned by [bindToRecyclerView] / [bindPrefetchToRecyclerView] / [bindPaginated].
 *
 * Lifetime is bound to the `LifecycleOwner` passed to the binding call — listeners are removed
 * automatically on `Lifecycle.Event.ON_DESTROY`. Call [unbind] to tear the binding down earlier
 * (e.g., when swapping the adapter on the same `RecyclerView` without recreating the host
 * `Fragment`/`Activity`). Call [recalibrate] after a manual `paginator.refresh()` /
 * `paginator.jump()` so the controller treats the next scroll event as a fresh calibration
 * instead of comparing against stale indices from before the data swap.
 *
 * Idempotent: every method is a no-op once the binding has already been torn down.
 */
public interface ScrollBinding {

    /**
     * Force the next scroll-or-layout event to be re-emitted to the controller, ignoring the
     * binding's internal de-duplication state. Use this after `paginator.refresh()` /
     * `paginator.jump()` when the data identity changes but the visible-index window may
     * coincidentally match the previous one.
     *
     * Has no effect after [unbind].
     */
    public fun recalibrate()

    /**
     * Detach scroll / layout listeners and stop forwarding events to the controller. This is
     * **not** required in normal usage — the binding tears itself down on `ON_DESTROY` — but is
     * useful when the binding's lifetime is shorter than its `LifecycleOwner`'s (e.g., a tab
     * inside a long-lived host).
     *
     * Idempotent. The underlying [com.jamal_aliev.paginator.prefetch.PaginatorPrefetchController]
     * is **not** cancelled here; if you also want to cancel in-flight prefetches, call
     * `controller.cancel()` separately, or rely on the lifecycle-aware controller factory.
     */
    public fun unbind()
}
