package com.jamal_aliev.paginator.compose.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide in-memory store for scroll snapshots. Bridges the gap between two facts:
 *
 *  1. A scroll position is a UI concern. It lives in `LazyListState`, which Compose discards
 *     when the host composable leaves composition.
 *  2. The user expects the position to come back when they revisit the same logical screen
 *     (e.g., back-navigate to a cached search-results list).
 *
 * The registry persists snapshots **outside** any composable lifetime — for the entire process
 * lifetime, until evicted via [clearScrollSnapshot] / [clearAllScrollSnapshots].
 *
 * Keys are `Any`: either an identity reference (e.g., the calling
 * `PaginatorPrefetchController`) or a `String` chosen by the caller. Identity keys collide
 * across recreations of the underlying object, which is exactly what makes the snapshot
 * silently invalid when a new paginator replaces the old one — see the `scrollKey` discussion
 * in the `BindToLazyList` doc.
 *
 * Storage is a [MutableStateFlow]`<Map<Any, Any>>` and updates go through
 * [MutableStateFlow.update] — a lock-free CAS loop. This avoids blocking threads on a
 * `synchronized` lock and plays nicely with the coroutines world the rest of the library
 * lives in. The map itself is replaced on every write (functional update), which is fine at
 * the contention this registry sees: writes happen on Compose lifecycle boundaries
 * (`DisposableEffect.onDispose`), reads happen on `LaunchedEffect` entry, and the map carries
 * at most a handful of entries.
 *
 * Process-death survival is NOT in scope here — that's what the `scrollKey` + `Saver` path
 * in `Bind*ScrollPreservation` is for.
 */
internal object ScrollSnapshotRegistry {

    private val state: MutableStateFlow<Map<Any, Any>> = MutableStateFlow(emptyMap())

    // inline + reified so the `as? T` at call site compiles into a real `instanceof` check.
    // Without reification, JVM erasure would degrade `as? T` to `as? Any` and the typed
    // accessor would silently return wrong-typed snapshots — defeating its purpose.
    inline fun <reified T : Any> get(key: Any): T? = peek(key) as? T

    @PublishedApi
    internal fun peek(key: Any): Any? = state.value[key]

    fun put(key: Any, snapshot: Any) {
        state.update { it + (key to snapshot) }
    }

    fun remove(key: Any) {
        state.update { if (key in it) it - key else it }
    }

    fun clear() {
        state.update { emptyMap() }
    }
}

/**
 * Remove the saved scroll snapshot for [key] from the registry. Call this when a screen's data
 * has logically reset (e.g., the user explicitly refreshes, the search query changes, the
 * underlying paginator is released) and the saved position no longer makes sense.
 *
 * No-op if no snapshot is registered for [key]. Safe to call from any thread.
 *
 * If [key] is the [PaginatorPrefetchController][com.jamal_aliev.paginator.offset.prefetch.PaginatorPrefetchController]
 * itself (the default identity key when `scrollKey == null`), eviction usually happens
 * implicitly: as soon as the old controller is garbage-collected its entry is unreachable.
 * Call this method explicitly only when [key] is a string the application chose deliberately.
 */
public fun clearScrollSnapshot(key: Any): Unit = ScrollSnapshotRegistry.remove(key)

/**
 * Wipe the entire scroll-snapshot registry. Intended for tests; in production code prefer the
 * targeted [clearScrollSnapshot].
 */
public fun clearAllScrollSnapshots(): Unit = ScrollSnapshotRegistry.clear()
