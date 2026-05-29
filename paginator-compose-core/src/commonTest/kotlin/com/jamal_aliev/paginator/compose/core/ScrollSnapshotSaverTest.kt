package com.jamal_aliev.paginator.compose.core

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ScrollSnapshotSaverTest {

    // SaverScope is needed to invoke Saver.save — it lets the Saver veto values that aren't
    // saveable on the current platform. Our snapshots only carry Ints, which are trivially
    // saveable, so a permissive scope is fine for tests.
    private val scope = SaverScope { true }

    // Saver.save is `fun SaverScope.save(value: Original): Saveable?` declared on the Saver
    // interface — two receivers (dispatch = the Saver, extension = the SaverScope). A small
    // helper binds both so call sites stay clean.
    private fun <T : Any> Saver<T, Any>.saveIn(scope: SaverScope, value: T): Any? =
        with(this) { with(scope) { save(value) } }

    @Test
    fun lazyListScrollSnapshot_saver_round_trips() {
        val original =
            LazyListScrollSnapshot(firstVisibleItemIndex = 42, firstVisibleItemScrollOffset = 314)

        val saved = LazyListScrollSnapshot.Saver.saveIn(scope, original)
        assertNotNull(saved)
        val restored = LazyListScrollSnapshot.Saver.restore(saved)

        assertEquals(original, restored)
    }

    @Test
    fun lazyListScrollSnapshot_saver_round_trips_empty() {
        val saved = LazyListScrollSnapshot.Saver.saveIn(scope, LazyListScrollSnapshot.Empty)
        assertNotNull(saved)
        assertEquals(LazyListScrollSnapshot.Empty, LazyListScrollSnapshot.Saver.restore(saved))
    }

    @Test
    fun lazyGridScrollSnapshot_saver_round_trips() {
        val original =
            LazyGridScrollSnapshot(firstVisibleItemIndex = 7, firstVisibleItemScrollOffset = 1024)

        val saved = LazyGridScrollSnapshot.Saver.saveIn(scope, original)
        assertNotNull(saved)
        assertEquals(original, LazyGridScrollSnapshot.Saver.restore(saved))
    }

    @Test
    fun lazyStaggeredGridScrollSnapshot_saver_round_trips() {
        val original = LazyStaggeredGridScrollSnapshot(
            firstVisibleItemIndex = 99,
            firstVisibleItemScrollOffset = -3, // negative is legal — Saver does not validate
        )

        val saved = LazyStaggeredGridScrollSnapshot.Saver.saveIn(scope, original)
        assertNotNull(saved)
        assertEquals(original, LazyStaggeredGridScrollSnapshot.Saver.restore(saved))
    }

    @Test
    fun empty_constants_are_zero_initialized() {
        assertEquals(LazyListScrollSnapshot(0, 0), LazyListScrollSnapshot.Empty)
        assertEquals(LazyGridScrollSnapshot(0, 0), LazyGridScrollSnapshot.Empty)
        assertEquals(LazyStaggeredGridScrollSnapshot(0, 0), LazyStaggeredGridScrollSnapshot.Empty)
    }
}
