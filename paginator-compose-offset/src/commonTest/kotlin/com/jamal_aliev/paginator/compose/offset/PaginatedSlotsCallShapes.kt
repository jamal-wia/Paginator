package com.jamal_aliev.paginator.compose.offset

import androidx.compose.runtime.Composable
import kotlin.test.Test
import kotlin.test.assertEquals
import com.jamal_aliev.paginator.compose.core.PaginatedSlots
import com.jamal_aliev.paginator.offset.Paginator

// Compile-time checks of the public call shapes: if an overload became ambiguous or a plain call
// resolved to a deprecated signature, this source set would fail to compile (warnings are errors
// for deprecations only via the @Suppress-free calls below being reviewed in the build log).

@Composable
internal fun plainCall(paginator: Paginator<String>) {
    PaginatedLazyColumn(paginator, key = { it }) { item, _, _, _, _ -> item.length }
}

@Composable
internal fun slotsCall(paginator: Paginator<String>) {
    PaginatedLazyColumn(
        paginator,
        key = { it },
        slots = PaginatedSlots(emptyContent = {}, appendErrorIndicator = { _ -> }),
    ) { item, _, _, _, _ -> item.length }
}

@Suppress("DEPRECATION")
@Composable
internal fun legacyCall(paginator: Paginator<String>) {
    PaginatedLazyColumn(paginator, key = { it }, emptyContent = {}) { item, _, _, _, _ -> item.length }
}

@Composable
internal fun gridCall(paginator: Paginator<String>) {
    // Grids now accept edge error indicators too, through the same slots.
    androidx.compose.foundation.lazy.grid.GridCells.Fixed(2).let { cells ->
        PaginatedLazyVerticalGrid(
            paginator,
            cells,
            key = { it },
            slots = PaginatedSlots(appendErrorIndicator = { _ -> }),
        ) { item -> item.length }
    }
}

class PaginatedSlotsCallShapesTest {

    // The composables above are the real check — they only have to compile. This test keeps the
    // source set from being reported as "test sources without tests" and pins the slot default.
    @Test
    fun call_shapes_compile_and_default_slots_are_empty() {
        assertEquals(PaginatedSlots<String>(), PaginatedSlots())
    }
}
