package com.jamal_aliev.paginator.pagestate

import com.jamal_aliev.paginator.extension.isEmptyState
import com.jamal_aliev.paginator.extension.isRealSuccessState
import com.jamal_aliev.paginator.extension.isSuccessState
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SuccessPageStateTest {

    @Test
    fun `test the integrity of stored data for SuccessPage`() {
        val samplePageNumber = 3
        val sampleListOfData: List<String> =
            MutableList(Random.nextInt(from = 0, until = 100)) { index: Int ->
                return@MutableList "Num of index $index"
            }

        val successPageState: SuccessPage<String> =
            SuccessPage(
                page = samplePageNumber,
                data = sampleListOfData
            )

        assertEquals(samplePageNumber, successPageState.page)
        assertEquals(sampleListOfData, successPageState.data)

        assertThrows(Exception::class.java) {
            SuccessPage<String>(
                page = 1,
                data = emptyList()
            )
        }
    }

    @Test
    fun `test hashCode for SuccessPage`() {
        val page = 4
        val sampleListOfData: List<String> =
            MutableList(Random.nextInt(from = 0, until = 100)) { index: Int ->
                return@MutableList "Num of index $index"
            }
        val successPageState: PageState<String> =
            SuccessPage(
                page = page,
                data = sampleListOfData
            )
        assertEquals(page.hashCode(), successPageState.hashCode())
    }

    @Test
    fun `test compareTo for SuccessPage`() {
        val sampleListOfData: List<String> =
            MutableList(Random.nextInt(from = 0, until = 100)) { index: Int ->
                return@MutableList "Num of index $index"
            }
        val successPageState1: PageState<String> =
            SuccessPage(
                page = 1,
                data = sampleListOfData
            )
        val successPageState2: PageState<String> =
            SuccessPage(
                page = 2,
                data = sampleListOfData
            )
        assertTrue(successPageState1 < successPageState2)
        assertFalse(successPageState1 > successPageState2)
    }

    @Test
    fun `test copy for SuccessPage`() {
        val originalSuccessPageState: SuccessPage<String> =
            SuccessPage(
                page = 1,
                data = listOf("a", "b", "c")
            )

        val copiedSuccessPageState: SuccessPage<String> =
            originalSuccessPageState.copy()

        assertNotSame(originalSuccessPageState, copiedSuccessPageState)
        assertEquals(originalSuccessPageState.page, copiedSuccessPageState.page)
        assertEquals(originalSuccessPageState.data, copiedSuccessPageState.data)
        assertEquals(originalSuccessPageState.hashCode(), copiedSuccessPageState.hashCode())
        assertEquals(originalSuccessPageState.toString(), copiedSuccessPageState.toString())
        assertFalse(originalSuccessPageState < copiedSuccessPageState)
        assertFalse(originalSuccessPageState > copiedSuccessPageState)

        val modifiedSuccessPageState: SuccessPage<String> =
            originalSuccessPageState.copy(
                page = 3,
                data = listOf("x", "y", "z")
            )

        assertNotSame(originalSuccessPageState, modifiedSuccessPageState)
        assertNotEquals(originalSuccessPageState.page, modifiedSuccessPageState.page)
        assertNotEquals(originalSuccessPageState.data, modifiedSuccessPageState.data)
        assertNotEquals(originalSuccessPageState.hashCode(), modifiedSuccessPageState.hashCode())
        assertNotEquals(originalSuccessPageState.toString(), modifiedSuccessPageState.toString())
        assertTrue(originalSuccessPageState < modifiedSuccessPageState)

        val emptySuccessPageState:PageState<String> =
            originalSuccessPageState.copy(
                data = emptyList()
            )
        assertNotSame(originalSuccessPageState, emptySuccessPageState)
        assertEquals(originalSuccessPageState.page, emptySuccessPageState.page)
        assertNotEquals(originalSuccessPageState.data, emptySuccessPageState.data)
        assertEquals(originalSuccessPageState.hashCode(), emptySuccessPageState.hashCode())
        assertNotEquals(originalSuccessPageState.toString(), emptySuccessPageState.toString())
        assertFalse(originalSuccessPageState < emptySuccessPageState)
        assertFalse(originalSuccessPageState > emptySuccessPageState)
        assertFalse(emptySuccessPageState.isSuccessState())
        assertTrue(emptySuccessPageState.isEmptyState())
    }

    @Test
    fun `test true using isSuccessState for SuccessPage`() {
        val sampleListOfData: List<String> =
            MutableList(Random.nextInt(from = 0, until = 100)) { index: Int ->
                return@MutableList "Num of index $index"
            }
        val successPageState: SuccessPage<String> =
            SuccessPage(
                page = 1,
                data = sampleListOfData
            )
        assertTrue(successPageState.isSuccessState())
    }

    @Test
    fun `test using isRealSuccessState for SuccessPage`() {
        val sampleListOfData: List<String> =
            MutableList(Random.nextInt(from = 0, until = 100)) { index: Int ->
                return@MutableList "Num of index $index"
            }
        val successPageState: SuccessPage<String> =
            SuccessPage(
                page = 1,
                data = sampleListOfData
            )
        assertTrue(successPageState.isRealSuccessState(SuccessPage::class))
    }
}
