package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.extension.smartForEach
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PageState.EmptyPage
import com.jamal_aliev.paginator.page.PageState.ErrorPage
import com.jamal_aliev.paginator.page.PageState.ProgressPage
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Test

class SmartForEachTest {

    @Test
    fun `test smartForEach default forward iteration`() {
        val paginator = createPaginatorWith(5)
        val visited = mutableListOf<Int>()

        paginator.smartForEach { _, index, _ ->
            visited += index
            true
        }

        assertEquals(listOf(0, 1, 2, 3, 4), visited)
    }

    @Test
    fun `test smartForEach custom initial index`() {
        val paginator = createPaginatorWith(5)
        val visited = mutableListOf<Int>()

        paginator.smartForEach(
            initialIndex = { 2 }, // start from index 2
        ) { _, index, _ ->
            visited += index
            true
        }

        assertEquals(listOf(2, 3, 4), visited)
    }

    @Test
    fun `test smartForEach backward iteration`() {
        val paginator = createPaginatorWith(5)
        val visited = mutableListOf<Int>()

        paginator.smartForEach(
            initialIndex = { it.lastIndex },
            step = { it - 1 }
        ) { _, index, _ ->
            visited += index
            true
        }

        assertEquals(listOf(4, 3, 2, 1, 0), visited)
    }

    @Test
    fun `test smartForEach skipping elements`() {
        val paginator = createPaginatorWith(6)
        val visited = mutableListOf<Int>()

        paginator.smartForEach(
            step = { it + 2 } // step = 2
        ) { _, index, _ ->
            visited += index
            true
        }

        assertEquals(listOf(0, 2, 4), visited)
    }

    @Test
    fun `test smartForEach early stop`() {
        val paginator = createPaginatorWith(10)
        val visited = mutableListOf<Int>()

        paginator.smartForEach { _, index, _ ->
            visited += index
            index < 3 // stop after index == 3
        }

        assertEquals(listOf(0, 1, 2, 3), visited)
    }

    @Test
    fun `test smartForEach initial index out of bounds`() {
        val paginator = createPaginatorWith(5)
        val visited = mutableListOf<Int>()

        paginator.smartForEach(
            initialIndex = { 10 } // invalid index
        ) { _, index, _ ->
            visited += index
            true
        }

        assertTrue(visited.isEmpty())
    }

    @Test
    fun `test smartForEach returns equals list`() {
        val paginator = createPaginatorWith(3)
        val result = paginator.smartForEach { _, _, _ -> true }

        assertEquals(paginator.states, result)
    }
}

private fun createPaginatorWith(n: Int): MutablePaginator<String> {
    val paginator = MutablePaginator<String> { emptyList() }
    repeat(n) { index ->
        paginator.setState(
            createRandomPageState(page = index.toInt(), data = listOf("data $index")),
            silently = true
        )
    }
    return paginator
}

private fun <T> createRandomPageState(page: Int, data: List<T>): PageState<T> {
    return when ((0..100).random()) {
        in 0..24 -> ProgressPage(page, data)
        in 25..49 -> EmptyPage(page, data)
        in 50..75 -> ErrorPage(Exception(), page, data)
        else -> SuccessPage(page, data)
    }
}
