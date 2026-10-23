package com.jamal_aliev.paginator.compose.core

import androidx.compose.runtime.Composable
import com.jamal_aliev.paginator.core.page.PageState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class PaginatedSlotsTest {

    private val empty: @Composable () -> Unit = {}
    private val retry: @Composable (PageState.ErrorState<String>) -> Unit = { _ -> }

    @Test
    fun slots_with_the_same_lambdas_are_equal() {
        // Equality on the slot lambdas keeps a PaginatedSlots(...) rebuilt in composition from
        // forcing the list to recompose.
        val a = PaginatedSlots<String>(emptyContent = empty, appendErrorIndicator = retry)
        val b = PaginatedSlots<String>(emptyContent = empty, appendErrorIndicator = retry)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, PaginatedSlots(emptyContent = empty))
    }

    @Test
    fun every_slot_defaults_to_null() {
        val slots = PaginatedSlots<String>()

        assertNull(slots.prependIndicator)
        assertNull(slots.appendIndicator)
        assertNull(slots.prependErrorIndicator)
        assertNull(slots.appendErrorIndicator)
        assertNull(slots.loadingContent)
        assertNull(slots.emptyContent)
        assertNull(slots.errorContent)
    }

    @Test
    fun copy_replaces_only_the_given_slots() {
        val slots = PaginatedSlots<String>(emptyContent = empty)

        val copy = slots.copy(appendErrorIndicator = retry)

        assertSame(empty, copy.emptyContent)
        assertSame(retry, copy.appendErrorIndicator)
        assertNull(copy.loadingContent)
    }
}
