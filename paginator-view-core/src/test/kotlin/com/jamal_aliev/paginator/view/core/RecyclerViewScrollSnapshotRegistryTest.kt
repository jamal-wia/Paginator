package com.jamal_aliev.paginator.view.core

import android.os.Parcel
import android.os.Parcelable
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
import kotlin.test.assertSame

/**
 * Stub Parcelable that survives the Android-library test JVM. We never serialize it — the
 * registry only stores and returns the reference — so the Parcel-touching methods are
 * unreachable in these tests. They throw if the test environment is misconfigured and
 * actually reaches them.
 */
private class StubParcelable(val tag: String) : Parcelable {
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) {
        error("StubParcelable should not be serialized in unit tests")
    }
}

class RecyclerViewScrollSnapshotRegistryTest {

    @BeforeTest
    @AfterTest
    fun reset() {
        clearAllRecyclerScrollSnapshots()
    }

    @Test
    fun set_then_get_returns_same_instance() {
        val key = Any()
        val parcel = StubParcelable("a")

        RecyclerViewScrollSnapshotRegistry[key] = parcel

        // Identity equality — the registry holds references, it does not serialize/clone.
        assertSame(parcel, RecyclerViewScrollSnapshotRegistry[key])
    }

    @Test
    fun get_missing_returns_null() {
        assertNull(RecyclerViewScrollSnapshotRegistry[Any()])
    }

    @Test
    fun set_overwrites_previous() {
        val key = Any()
        RecyclerViewScrollSnapshotRegistry[key] = StubParcelable("first")
        val second = StubParcelable("second")
        RecyclerViewScrollSnapshotRegistry[key] = second

        assertSame(second, RecyclerViewScrollSnapshotRegistry[key])
    }

    @Test
    fun remove_evicts_entry() {
        val key = Any()
        RecyclerViewScrollSnapshotRegistry[key] = StubParcelable("a")

        RecyclerViewScrollSnapshotRegistry.remove(key)

        assertNull(RecyclerViewScrollSnapshotRegistry[key])
    }

    @Test
    fun public_clearRecyclerScrollSnapshot_removes_one_entry() {
        val a = Any()
        val b = Any()
        RecyclerViewScrollSnapshotRegistry[a] = StubParcelable("a")
        RecyclerViewScrollSnapshotRegistry[b] = StubParcelable("b")

        clearRecyclerScrollSnapshot(a)

        assertNull(RecyclerViewScrollSnapshotRegistry[a])
        assertNotNull(RecyclerViewScrollSnapshotRegistry[b])
    }

    @Test
    fun clear_drops_every_entry() {
        val keys = List(5) { Any() }
        keys.forEach { RecyclerViewScrollSnapshotRegistry[it] = StubParcelable(it.toString()) }

        RecyclerViewScrollSnapshotRegistry.clear()

        keys.forEach { assertNull(RecyclerViewScrollSnapshotRegistry[it]) }
    }

    @Test
    fun concurrent_puts_do_not_lose_entries() = runTest {
        // Same rationale as ScrollSnapshotRegistryTest: validate that .update { } provides
        // CAS semantics and that parallel writes from different threads all land.
        val entries = List(200) { Any() to StubParcelable("p$it") }

        withContext(Dispatchers.Default) {
            coroutineScope {
                entries.map { (key, parcel) ->
                    async { RecyclerViewScrollSnapshotRegistry[key] = parcel }
                }.awaitAll()
            }
        }

        entries.forEach { (key, parcel) ->
            assertEquals(parcel, RecyclerViewScrollSnapshotRegistry[key])
        }
    }
}
