package com.jamal_aliev.paginator.pagestate

import com.jamal_aliev.paginator.extension.isProgressState
import com.jamal_aliev.paginator.extension.isRealProgressState
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PageState.ProgressPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ProgressPageStateTest {

    @Test
    fun `test the integrity of stored data for ProgressPage`() {
        val samplePageNumber = 1
        val sampleListOfData: List<String> =
            MutableList(Random.nextInt(from = 0, until = 100)) { index: Int ->
                return@MutableList "Num of index $index"
            }
        val progressPageState: PageState<String> =
            ProgressPage(
                page = samplePageNumber,
                data = sampleListOfData.toList()
            )
        assertEquals(samplePageNumber, progressPageState.page)
        assertEquals(sampleListOfData, progressPageState.data)
    }

    @Test
    fun `test hashCode for ProgressPage`() {
        val page = 1
        val progressPageState: PageState<String> =
            ProgressPage(
                page = page,
                data = emptyList()
            )
        assertEquals(page.hashCode(), progressPageState.hashCode())
    }

    @Test
    fun `test compareTo for ProgressPage`() {
        val progressPageState1: PageState<String> =
            ProgressPage(
                page = 1,
                data = emptyList()
            )
        val progressPageState2: PageState<String> =
            ProgressPage(
                page = 2,
                data = emptyList()
            )
        assertTrue(progressPageState1 < progressPageState2)
        assertFalse(progressPageState1 > progressPageState2)
    }

    @Test
    fun `test copy for ProgressPage`() {
        val progressPageState: PageState<String> =
            ProgressPage(
                page = 1,
                data = listOf("a", "b", "c")
            )

        val copiedProgressPageState: PageState<String> =
            progressPageState.copy()

        assertNotSame(progressPageState, copiedProgressPageState)
        assertEquals(progressPageState.page, copiedProgressPageState.page)
        assertEquals(progressPageState.data, copiedProgressPageState.data)
        assertEquals(progressPageState.hashCode(), copiedProgressPageState.hashCode())
        assertEquals(progressPageState.toString(), copiedProgressPageState.toString())
        assertFalse(progressPageState < copiedProgressPageState)
        assertFalse(progressPageState > copiedProgressPageState)

        val modifiedProgressPageState: PageState<String> =
            progressPageState.copy(
                page = 2,
                data = listOf("x", "y", "z")
            )

        assertNotSame(progressPageState, modifiedProgressPageState)
        assertNotEquals(progressPageState.page, modifiedProgressPageState.page)
        assertNotEquals(progressPageState.data, modifiedProgressPageState.data)
        assertNotEquals(progressPageState.hashCode(), modifiedProgressPageState.hashCode())
        assertNotEquals(progressPageState.toString(), modifiedProgressPageState.toString())
        assertTrue(progressPageState < modifiedProgressPageState)
    }

    @Test
    fun `test true using isProgressState for ProgressPage`() {
        val progressPageState: PageState<String> =
            ProgressPage(
                page = 1,
                data = emptyList()
            )
        assertTrue(progressPageState.isProgressState())
    }

    @Test
    fun `test using isRealProgressState for ProgressPage`() {
        val progressPageState: PageState<String> =
            ProgressPage(
                page = 1,
                data = emptyList()
            )
        assertTrue(progressPageState.isRealProgressState(ProgressPage::class))
    }
}