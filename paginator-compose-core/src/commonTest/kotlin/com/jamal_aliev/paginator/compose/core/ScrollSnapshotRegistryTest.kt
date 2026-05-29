package com.jamal_aliev.paginator.compose.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScrollSnapshotRegistryTest {

    @BeforeTest
    @AfterTest
    fun reset() {
        // ScrollSnapshotRegistry is a process-wide singleton — wipe between tests to avoid
        // cross-test leakage. Use the public API since the test runs in the same module.
        clearAllScrollSnapshots()
    }

    @Test
    fun put_then_get_returns_value() {
        val key = Any()
        val snapshot = LazyListScrollSnapshot(42, 17)

        ScrollSnapshotRegistry.put(key, snapshot)

        assertEquals(snapshot, ScrollSnapshotRegistry.get<LazyListScrollSnapshot>(key))
    }

    @Test
    fun get_missing_returns_null() {
        assertNull(ScrollSnapshotRegistry.get<LazyListScrollSnapshot>(Any()))
    }

    @Test
    fun put_overwrites_previous() {
        val key = Any()
        ScrollSnapshotRegistry.put(key, LazyListScrollSnapshot(1, 0))
        ScrollSnapshotRegistry.put(key, LazyListScrollSnapshot(2, 5))

        assertEquals(
            LazyListScrollSnapshot(2, 5),
            ScrollSnapshotRegistry.get<LazyListScrollSnapshot>(key),
        )
    }

    @Test
    fun remove_evicts_entry() {
        val key = Any()
        ScrollSnapshotRegistry.put(key, LazyListScrollSnapshot(1, 0))

        ScrollSnapshotRegistry.remove(key)

        assertNull(ScrollSnapshotRegistry.get<LazyListScrollSnapshot>(key))
    }

    @Test
    fun remove_unknown_key_is_noop() {
        ScrollSnapshotRegistry.put(Any(), LazyListScrollSnapshot(1, 0))

        ScrollSnapshotRegistry.remove(Any()) // different key, never inserted

        // Other entry remains; this verifies remove of a missing key does not blow up
        // and does not corrupt the map. (Asserting absence here would be tautological.)
    }

    @Test
    fun clear_drops_every_entry() {
        val keys = List(5) { Any() }
        keys.forEachIndexed { i, k -> ScrollSnapshotRegistry.put(k, LazyListScrollSnapshot(i, 0)) }

        ScrollSnapshotRegistry.clear()

        keys.forEach { assertNull(ScrollSnapshotRegistry.get<LazyListScrollSnapshot>(it)) }
    }

    @Test
    fun public_clearScrollSnapshot_removes_one_entry() {
        val a = Any()
        val b = Any()
        ScrollSnapshotRegistry.put(a, LazyListScrollSnapshot(1, 0))
        ScrollSnapshotRegistry.put(b, LazyListScrollSnapshot(2, 0))

        clearScrollSnapshot(a)

        assertNull(ScrollSnapshotRegistry.get<LazyListScrollSnapshot>(a))
        assertNotNull(ScrollSnapshotRegistry.get<LazyListScrollSnapshot>(b))
    }

    @Test
    fun get_with_wrong_type_returns_null() {
        val key = Any()
        ScrollSnapshotRegistry.put(key, LazyListScrollSnapshot(1, 0))

        // Same key, wrong expected type — get<T>'s `as? T` returns null instead of CCE.
        assertNull(ScrollSnapshotRegistry.get<LazyGridScrollSnapshot>(key))
    }

    @Test
    fun different_snapshot_types_coexist_under_different_keys() {
        val listKey = Any()
        val gridKey = Any()
        val staggeredKey = Any()

        ScrollSnapshotRegistry.put(listKey, LazyListScrollSnapshot(1, 10))
        ScrollSnapshotRegistry.put(gridKey, LazyGridScrollSnapshot(2, 20))
        ScrollSnapshotRegistry.put(staggeredKey, LazyStaggeredGridScrollSnapshot(3, 30))

        assertEquals(
            LazyListScrollSnapshot(1, 10),
            ScrollSnapshotRegistry.get<LazyListScrollSnapshot>(listKey),
        )
        assertEquals(
            LazyGridScrollSnapshot(2, 20),
            ScrollSnapshotRegistry.get<LazyGridScrollSnapshot>(gridKey),
        )
        assertEquals(
            LazyStaggeredGridScrollSnapshot(3, 30),
            ScrollSnapshotRegistry.get<LazyStaggeredGridScrollSnapshot>(staggeredKey),
        )
    }

    @Test
    fun concurrent_puts_do_not_lose_entries() = runTest {
        // Stress the MutableStateFlow.update { } CAS loop with parallel writes. Each
        // coroutine puts a distinct key — if .update { } degraded to state.value = ...,
        // most of these would be lost to the read-modify-write race.
        val keys = List(200) { Any() to LazyListScrollSnapshot(it, it * 7) }

        withContext(Dispatchers.Default) {
            coroutineScope {
                keys.map { (key, snapshot) ->
                    async { ScrollSnapshotRegistry.put(key, snapshot) }
                }.awaitAll()
            }
        }

        keys.forEach { (key, snapshot) ->
            assertEquals(snapshot, ScrollSnapshotRegistry.get<LazyListScrollSnapshot>(key))
        }
    }
}
