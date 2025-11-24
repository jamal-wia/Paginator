package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.extension.isEmptyState
import com.jamal_aliev.paginator.extension.isRealEmptyState
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PageState.EmptyPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EmptyPageStateTest {

    @Test
    fun `test the integrity of stored data for EmptyPage`() {
        val samplePageNumber = 3u
        val sampleListOfData: List<String> =
            MutableList(Random.nextInt(from = 0, until = 100)) { index: Int ->
                return@MutableList "Num of index $index"
            }

        val emptyPageState: EmptyPage<String> =
            EmptyPage(
                page = samplePageNumber,
                data = sampleListOfData
            )

        assertEquals(samplePageNumber, emptyPageState.page)
        assertEquals(sampleListOfData, emptyPageState.data)

        assertNotNull(
            EmptyPage<String>(
                page = 1u,
                data = emptyList()
            )
        )
    }

    @Test
    fun `test hashCode for EmptyPage`() {
        val page = 4u
        val emptyPageState: PageState<String> =
            EmptyPage(
                page = page,
                data = emptyList()
            )
        assertEquals(page.hashCode(), emptyPageState.hashCode())
    }

    @Test
    fun `test compareTo for EmptyPage`() {
        val emptyPageState1: PageState<String> =
            EmptyPage(
                page = 1u,
                data = emptyList()
            )
        val emptyPageState2: PageState<String> =
            EmptyPage(
                page = 2u,
                data = emptyList()
            )
        assertTrue(emptyPageState1 < emptyPageState2)
        assertFalse(emptyPageState1 > emptyPageState2)
    }

    @Test
    fun `test copy for EmptyPage`() {
        val originalEmptyPageState: EmptyPage<String> =
            EmptyPage(
                page = 1u,
                data = listOf("a", "b", "c")
            )

        val copiedEmptyPageState: EmptyPage<String> =
            originalEmptyPageState.copy()

        assertNotSame(originalEmptyPageState, copiedEmptyPageState)
        assertEquals(originalEmptyPageState.page, copiedEmptyPageState.page)
        assertEquals(originalEmptyPageState.data, copiedEmptyPageState.data)
        assertEquals(originalEmptyPageState.hashCode(), copiedEmptyPageState.hashCode())
        assertEquals(originalEmptyPageState.toString(), copiedEmptyPageState.toString())
        assertFalse(originalEmptyPageState < copiedEmptyPageState)
        assertFalse(originalEmptyPageState > copiedEmptyPageState)

        val modifiedEmptyPageState: EmptyPage<String> =
            originalEmptyPageState.copy(
                page = 3u,
                data = listOf("x", "y", "z")
            )

        assertNotSame(originalEmptyPageState, modifiedEmptyPageState)
        assertNotEquals(originalEmptyPageState.page, modifiedEmptyPageState.page)
        assertNotEquals(originalEmptyPageState.data, modifiedEmptyPageState.data)
        assertNotEquals(originalEmptyPageState.hashCode(), modifiedEmptyPageState.hashCode())
        assertNotEquals(originalEmptyPageState.toString(), modifiedEmptyPageState.toString())
        assertTrue(originalEmptyPageState < modifiedEmptyPageState)
    }

    @Test
    fun `test true using isEmptyState for EmptyPage`() {
        val emptyPageState: EmptyPage<String> =
            EmptyPage(
                page = 1u,
                data = emptyList()
            )
        assertTrue(emptyPageState.isEmptyState())
    }

    @Test
    fun `test using isRealEmptyState for EmptyPage`() {
        val emptyPageState: EmptyPage<String> =
            EmptyPage(
                page = 1u,
                data = emptyList()
            )
        assertTrue(emptyPageState.isRealEmptyState(EmptyPage::class))
    }
}
