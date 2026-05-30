package com.jamal_aliev.paginator.offset.pagestate


import com.jamal_aliev.paginator.core.extension.isProgressState
import com.jamal_aliev.paginator.core.extension.isRealProgressState
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ProgressPageStateTest {

    @Test
    fun `test the integrity of stored data for Progress`() {
        val samplePageNumber = 1
        val sampleListOfData: List<String> =
            MutableList(Random.nextInt(from = 0, until = 100)) { index: Int ->
                return@MutableList "Num of index $index"
            }
        val progressPageState: OffsetPageState<String> =
            OffsetPageState.Progress(
                page = samplePageNumber,
                data = sampleListOfData.toList()
            )
        assertEquals(samplePageNumber, progressPageState.page)
        assertEquals(sampleListOfData, progressPageState.data)
    }

    @Test
    fun `test hashCode for Progress`() {
        val page = 1
        val progressPageState: OffsetPageState<String> =
            OffsetPageState.Progress(
                page = page,
                data = emptyList()
            )
        assertEquals(page.hashCode(), progressPageState.hashCode())
    }

    @Test
    fun `test compareTo for Progress`() {
        val progressPageState1: OffsetPageState<String> =
            OffsetPageState.Progress(
                page = 1,
                data = emptyList()
            )
        val progressPageState2: OffsetPageState<String> =
            OffsetPageState.Progress(
                page = 2,
                data = emptyList()
            )
        assertTrue(progressPageState1 < progressPageState2)
        assertFalse(progressPageState1 > progressPageState2)
    }

    @Test
    fun `test copy for Progress`() {
        val progressPageState: OffsetPageState<String> =
            OffsetPageState.Progress(
                page = 1,
                data = listOf("a", "b", "c")
            )

        val copiedProgressPageState: OffsetPageState<String> =
            progressPageState.copy()

        assertNotSame(progressPageState, copiedProgressPageState)
        assertEquals(progressPageState.page, copiedProgressPageState.page)
        assertEquals(progressPageState.data, copiedProgressPageState.data)
        assertEquals(progressPageState.hashCode(), copiedProgressPageState.hashCode())
        assertEquals(progressPageState.toString(), copiedProgressPageState.toString())
        assertFalse(progressPageState < copiedProgressPageState)
        assertFalse(progressPageState > copiedProgressPageState)

        val modifiedProgressPageState: OffsetPageState<String> =
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
    fun `test true using isProgressState for Progress`() {
        val progressPageState: OffsetPageState<String> =
            OffsetPageState.Progress(
                page = 1,
                data = emptyList()
            )
        assertTrue(progressPageState.isProgressState())
    }

    @Test
    fun `test using isRealProgressState for Progress`() {
        val progressPageState: OffsetPageState<String> =
            OffsetPageState.Progress(
                page = 1,
                data = emptyList()
            )
        assertTrue(progressPageState.isRealProgressState(OffsetPageState.Progress::class))
    }
}
