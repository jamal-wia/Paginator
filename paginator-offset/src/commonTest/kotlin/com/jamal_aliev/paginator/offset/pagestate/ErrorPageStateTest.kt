package com.jamal_aliev.paginator.offset.pagestate


import com.jamal_aliev.paginator.core.extension.isErrorState
import com.jamal_aliev.paginator.core.extension.isRealErrorState
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ErrorPageStateTest {

    @Test
    fun `test the integrity of stored data for Error`() {
        val sampleException = Exception("Test error")
        val samplePageNumber = 3
        val sampleListOfData: List<String> =
            MutableList(Random.nextInt(from = 0, until = 100)) { index: Int ->
                return@MutableList "Num of index $index"
            }

        val errorPageState: OffsetPageState.Error<String> =
            OffsetPageState.Error(
                exception = sampleException,
                page = samplePageNumber,
                data = sampleListOfData
            )

        assertEquals(samplePageNumber, errorPageState.page)
        assertEquals(sampleListOfData, errorPageState.data)
        assertEquals(sampleException, errorPageState.exception)
    }

    @Test
    fun `test hashCode for Error`() {
        val page = 4
        val errorPageState: OffsetPageState<String> =
            OffsetPageState.Error(
                exception = Exception("Error"),
                page = page,
                data = emptyList()
            )
        assertEquals(page.hashCode(), errorPageState.hashCode())
    }

    @Test
    fun `test compareTo for Error`() {
        val errorPageState1: OffsetPageState<String> =
            OffsetPageState.Error(
                exception = Exception("First error"),
                page = 1,
                data = emptyList()
            )
        val errorPageState2: OffsetPageState<String> =
            OffsetPageState.Error(
                exception = Exception("Second error"),
                page = 2,
                data = emptyList()
            )
        assertTrue(errorPageState1 < errorPageState2)
        assertFalse(errorPageState1 > errorPageState2)
    }

    @Test
    fun `test copy for Error and preserving the exception`() {
        val originalErrorPageState: OffsetPageState.Error<String> =
            OffsetPageState.Error(
                exception = Exception("Original error"),
                page = 1,
                data = listOf("a", "b", "c")
            )

        val copiedErrorPageState: OffsetPageState.Error<String> =
            originalErrorPageState.copy()

        assertNotSame(originalErrorPageState, copiedErrorPageState)
        assertEquals(originalErrorPageState.exception, copiedErrorPageState.exception)
        assertEquals(originalErrorPageState.page, copiedErrorPageState.page)
        assertEquals(originalErrorPageState.data, copiedErrorPageState.data)
        assertEquals(originalErrorPageState.hashCode(), copiedErrorPageState.hashCode())
        assertEquals(originalErrorPageState.toString(), copiedErrorPageState.toString())
        assertFalse(originalErrorPageState < copiedErrorPageState)
        assertFalse(originalErrorPageState > copiedErrorPageState)

        val modifiedErrorPageState: OffsetPageState.Error<String> =
            originalErrorPageState.copy(
                exception = Exception("New error"),
                page = 3,
                data = listOf("x", "y", "z")
            )

        assertNotSame(originalErrorPageState, modifiedErrorPageState)
        assertNotEquals(originalErrorPageState.exception, modifiedErrorPageState.exception)
        assertNotEquals(originalErrorPageState.page, modifiedErrorPageState.page)
        assertNotEquals(originalErrorPageState.data, modifiedErrorPageState.data)
        assertNotEquals(originalErrorPageState.hashCode(), modifiedErrorPageState.hashCode())
        assertNotEquals(originalErrorPageState.toString(), modifiedErrorPageState.toString())
        assertTrue(originalErrorPageState < modifiedErrorPageState)
    }

    @Test
    fun `test true using isErrorState for Error`() {
        val errorPageState: OffsetPageState.Error<String> =
            OffsetPageState.Error(
                exception = Exception("Test error"),
                page = 1,
                data = emptyList()
            )
        assertTrue(errorPageState.isErrorState())
    }

    @Test
    fun `test using isRealErrorState for Error`() {
        val errorPageState: OffsetPageState.Error<String> =
            OffsetPageState.Error(
                exception = Exception("Test error"),
                page = 1,
                data = emptyList()
            )
        assertTrue(errorPageState.isRealErrorState(OffsetPageState.Error::class))
    }
}
