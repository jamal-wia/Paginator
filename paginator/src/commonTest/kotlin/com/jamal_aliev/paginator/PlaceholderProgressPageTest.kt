package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.page.PageState.ProgressPage
import com.jamal_aliev.paginator.page.PlaceholderProgressPage
import com.jamal_aliev.paginator.page.PlaceholderProgressPage.Placeholder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlaceholderProgressPageTest {

    // ──────────────────────────────────────────────────────────────────────
    //  Basic construction
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `adds placeholders to empty data`() {
        val page = PlaceholderProgressPage<Any>(
            page = 1,
            data = mutableListOf(),
            placeholderCapacity = 3,
        )
        assertEquals(3, page.data.size)
        assertTrue(page.data.all { it is Placeholder })
    }

    @Test
    fun `adds placeholders after existing data`() {
        val page = PlaceholderProgressPage<Any>(
            page = 1,
            data = mutableListOf("a", "b"),
            placeholderCapacity = 2,
        )
        assertEquals(4, page.data.size)
        assertEquals("a", page.data[0])
        assertEquals("b", page.data[1])
        assertIs<Placeholder>(page.data[2])
        assertIs<Placeholder>(page.data[3])
    }

    @Test
    fun `zero placeholders adds nothing`() {
        val original = mutableListOf<Any>("a", "b")
        val page = PlaceholderProgressPage(
            page = 1,
            data = original,
            placeholderCapacity = 0,
        )
        assertEquals(2, page.data.size)
        assertEquals(listOf("a", "b"), page.data)
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Page number
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `page number is preserved`() {
        val page = PlaceholderProgressPage<Any>(
            page = 42,
            data = mutableListOf(),
            placeholderCapacity = 1,
        )
        assertEquals(42, page.page)
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Type hierarchy
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `is a ProgressPage`() {
        val page = PlaceholderProgressPage<Any>(
            page = 1,
            data = mutableListOf(),
            placeholderCapacity = 1,
        )
        assertIs<ProgressPage<*>>(page)
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Data mutability requirement
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `throws when data is unmodifiable list`() {
        // listOf on JVM passes 'is MutableList' but throws on add
        assertFailsWith<UnsupportedOperationException> {
            PlaceholderProgressPage<Any>(
                page = 1,
                data = listOf("a"),
                placeholderCapacity = 2,
            )
        }
    }

    @Test
    fun `throws when data is emptyList`() {
        assertFailsWith<IllegalArgumentException> {
            PlaceholderProgressPage<Any>(
                page = 1,
                data = emptyList(),
                placeholderCapacity = 1,
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Data identity — modifies the same list
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `data reference is the same mutable list passed in`() {
        val original = mutableListOf<Any>("x")
        val page = PlaceholderProgressPage(
            page = 1,
            data = original,
            placeholderCapacity = 2,
        )
        assertSame(original, page.data)
        assertEquals(3, original.size)
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Placeholder object
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `all placeholders are the same Placeholder instance`() {
        val page = PlaceholderProgressPage<Any>(
            page = 1,
            data = mutableListOf(),
            placeholderCapacity = 5,
        )
        page.data.forEach { assertSame(Placeholder, it) }
    }

    @Test
    fun `Placeholder data object equality`() {
        assertEquals(Placeholder, Placeholder)
        assertEquals(Placeholder.hashCode(), Placeholder.hashCode())
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Large placeholderCapacity
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `large placeholder capacity`() {
        val page = PlaceholderProgressPage<Any>(
            page = 1,
            data = mutableListOf(),
            placeholderCapacity = 1000,
        )
        assertEquals(1000, page.data.size)
        assertTrue(page.data.all { it is Placeholder })
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Generic type scenarios
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `works with Int type parameter`() {
        val data: MutableList<Any> = mutableListOf(1, 2, 3)
        val page = PlaceholderProgressPage<Any>(
            page = 1,
            data = data,
            placeholderCapacity = 2,
        )
        assertEquals(5, page.data.size)
        assertEquals(1, page.data[0])
        assertEquals(2, page.data[1])
        assertEquals(3, page.data[2])
        assertIs<Placeholder>(page.data[3])
        assertIs<Placeholder>(page.data[4])
    }
}
