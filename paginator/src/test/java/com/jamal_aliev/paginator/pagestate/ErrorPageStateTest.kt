package com.jamal_aliev.paginator.pagestate

import com.jamal_aliev.paginator.extension.isErrorState
import com.jamal_aliev.paginator.extension.isRealErrorState
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PageState.ErrorPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ErrorPageStateTest {

    @Test
    fun `test the integrity of stored data for ErrorPage`() {
        val sampleException = Exception("Test error")
        val samplePageNumber = 3
        val sampleListOfData: List<String> =
            MutableList(Random.nextInt(from = 0, until = 100)) { index: Int ->
                return@MutableList "Num of index $index"
            }

        val errorPageState: ErrorPage<String> =
            ErrorPage(
                exception = sampleException,
                page = samplePageNumber,
                data = sampleListOfData
            )

        assertEquals(samplePageNumber, errorPageState.page)
        assertEquals(sampleListOfData, errorPageState.data)
        assertEquals(sampleException, errorPageState.exception)
    }

    @Test
    fun `test hashCode for ErrorPage`() {
        val page = 4
        val errorPageState: PageState<String> =
            ErrorPage(
                exception = Exception("Error"),
                page = page,
                data = emptyList()
            )
        assertEquals(page.hashCode(), errorPageState.hashCode())
    }

    @Test
    fun `test compareTo for ErrorPage`() {
        val errorPageState1: PageState<String> =
            ErrorPage(
                exception = Exception("First error"),
                page = 1,
                data = emptyList()
            )
        val errorPageState2: PageState<String> =
            ErrorPage(
                exception = Exception("Second error"),
                page = 2,
                data = emptyList()
            )
        assertTrue(errorPageState1 < errorPageState2)
        assertFalse(errorPageState1 > errorPageState2)
    }

    @Test
    fun `test copy for ErrorPage and preserving the exception`() {
        val originalErrorPageState: ErrorPage<String> =
            ErrorPage(
                exception = Exception("Original error"),
                page = 1,
                data = listOf("a", "b", "c")
            )

        val copiedErrorPageState: ErrorPage<String> =
            originalErrorPageState.copy()

        assertNotSame(originalErrorPageState, copiedErrorPageState)
        assertEquals(originalErrorPageState.exception, copiedErrorPageState.exception)
        assertEquals(originalErrorPageState.page, copiedErrorPageState.page)
        assertEquals(originalErrorPageState.data, copiedErrorPageState.data)
        assertEquals(originalErrorPageState.hashCode(), copiedErrorPageState.hashCode())
        assertEquals(originalErrorPageState.toString(), copiedErrorPageState.toString())
        assertFalse(originalErrorPageState < copiedErrorPageState)
        assertFalse(originalErrorPageState > copiedErrorPageState)

        val modifiedErrorPageState: ErrorPage<String> =
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
    fun `test true using isErrorState for ErrorPage`() {
        val errorPageState: ErrorPage<String> =
            ErrorPage(
                exception = Exception("Test error"),
                page = 1,
                data = emptyList()
            )
        assertTrue(errorPageState.isErrorState())
    }

    @Test
    fun `test using isRealErrorState for ErrorPage`() {
        val errorPageState: ErrorPage<String> =
            ErrorPage(
                exception = Exception("Test error"),
                page = 1,
                data = emptyList()
            )
        assertTrue(errorPageState.isRealErrorState(ErrorPage::class))
    }
}
