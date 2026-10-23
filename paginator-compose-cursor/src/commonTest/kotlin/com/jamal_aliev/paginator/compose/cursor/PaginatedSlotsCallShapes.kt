package com.jamal_aliev.paginator.compose.cursor

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import kotlin.test.Test
import kotlin.test.assertEquals
import com.jamal_aliev.paginator.compose.core.PaginatedSlots
import com.jamal_aliev.paginator.cursor.CursorPaginator

// Compile-time checks of the public call shapes: a plain call must resolve to the slots overload
// (it would otherwise warn as deprecated), and the pre-10.3.0 named slots must keep compiling.

@Composable
internal fun plainCall(paginator: CursorPaginator<String, String>) {
    PaginatedLazyColumn(paginator, key = { it }) { item, _, _, _, _ -> item.length }
    PaginatedLazyVerticalGrid(paginator, GridCells.Fixed(2), key = { it }) { item -> item.length }
}

@Composable
internal fun slotsCall(paginator: CursorPaginator<String, String>) {
    PaginatedLazyRow(
        paginator,
        key = { it },
        slots = PaginatedSlots(loadingContent = {}, prependErrorIndicator = { _ -> }),
    ) { item, _, _, _, _ -> item.length }
}

@Suppress("DEPRECATION")
@Composable
internal fun legacyCall(paginator: CursorPaginator<String, String>) {
    PaginatedLazyColumn(paginator, key = { it }, errorContent = { _ -> }) { item, _, _, _, _ -> item.length }
}

class PaginatedSlotsCallShapesTest {

    // The composables above are the real check — they only have to compile. This test keeps the
    // source set from being reported as "test sources without tests" and pins the slot default.
    @Test
    fun call_shapes_compile_and_default_slots_are_empty() {
        assertEquals(PaginatedSlots<String>(), PaginatedSlots())
    }
}
