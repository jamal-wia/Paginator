package com.jamal_aliev.paginator.offset.pagestate


import com.jamal_aliev.paginator.core.extension.isEmptyState
import com.jamal_aliev.paginator.core.extension.isRealSuccessState
import com.jamal_aliev.paginator.core.extension.isSuccessState
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class SuccessPageStateTest {

    @Test
    fun `test the integrity of stored data for Success`() {
        val samplePageNumber = 3
        val sampleListOfData: List<String> =
            MutableList(Random.nextInt(from = 1, until = 100)) { index: Int ->
                return@MutableList "Num of index $index"
            }

        val successPageState: OffsetPageState.Success<String> =
            OffsetPageState.Success(
                page = samplePageNumber,
                data = sampleListOfData
            )

        assertEquals(samplePageNumber, successPageState.page)
        assertEquals(sampleListOfData, successPageState.data)

        assertNotNull(
            OffsetPageState.Success<String>(
                page = 1,
                data = emptyList()
            )
        )
    }

    @Test
    fun `test hashCode for Success`() {
        val page = 4
        val sampleListOfData: List<String> =
            MutableList(Random.nextInt(from = 1, until = 100)) { index: Int ->
                return@MutableList "Num of index $index"
            }
        val successPageState: OffsetPageState<String> =
            OffsetPageState.Success(
                page = page,
                data = sampleListOfData
            )
        assertEquals(page.hashCode(), successPageState.hashCode())
    }

    @Test
    fun `test compareTo for Success`() {
        val sampleListOfData: List<String> =
            MutableList(Random.nextInt(from = 1, until = 100)) { index: Int ->
                return@MutableList "Num of index $index"
            }
        val successPageState1: OffsetPageState<String> =
            OffsetPageState.Success(
                page = 1,
                data = sampleListOfData
            )
        val successPageState2: OffsetPageState<String> =
            OffsetPageState.Success(
                page = 2,
                data = sampleListOfData
            )
        assertTrue(successPageState1 < successPageState2)
        assertFalse(successPageState1 > successPageState2)
    }

    @Test
    fun `test copy for Success`() {
        val originalSuccessPageState: OffsetPageState.Success<String> =
            OffsetPageState.Success(
                page = 1,
                data = listOf("a", "b", "c")
            )

        val copiedSuccessPageState: OffsetPageState.Success<String> =
            originalSuccessPageState.copy()

        assertNotSame(originalSuccessPageState, copiedSuccessPageState)
        assertEquals(originalSuccessPageState.page, copiedSuccessPageState.page)
        assertEquals(originalSuccessPageState.data, copiedSuccessPageState.data)
        assertEquals(originalSuccessPageState.hashCode(), copiedSuccessPageState.hashCode())
        assertEquals(originalSuccessPageState.toString(), copiedSuccessPageState.toString())
        assertFalse(originalSuccessPageState < copiedSuccessPageState)
        assertFalse(originalSuccessPageState > copiedSuccessPageState)

        val modifiedSuccessPageState: OffsetPageState.Success<String> =
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

        val emptySuccessPageState: OffsetPageState<String> =
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
    fun `test true using isSuccessState for Success`() {
        val sampleListOfData: List<String> =
            MutableList(Random.nextInt(from = 1, until = 100)) { index: Int ->
                return@MutableList "Num of index $index"
            }
        val successPageState: OffsetPageState.Success<String> =
            OffsetPageState.Success(
                page = 1,
                data = sampleListOfData
            )
        assertTrue(successPageState.isSuccessState())
    }

    @Test
    fun `test using isRealSuccessState for Success`() {
        val sampleListOfData: List<String> =
            MutableList(Random.nextInt(from = 1, until = 100)) { index: Int ->
                return@MutableList "Num of index $index"
            }
        val successPageState: OffsetPageState.Success<String> =
            OffsetPageState.Success(
                page = 1,
                data = sampleListOfData
            )
        assertTrue(successPageState.isRealSuccessState(OffsetPageState.Success::class))
    }
}
