package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkUInt
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PageState.EmptyPage
import com.jamal_aliev.paginator.page.PageState.ErrorPage
import com.jamal_aliev.paginator.page.PageState.ProgressPage
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MutablePaginatorTest {

    @Test
    fun `test set get remove page state`() {
        val paginator = MutablePaginator<String> { emptyList() }
        val pageStates: MutableList<PageState<String>> =
            MutableList((1..10_000).random()) { index: Int ->
                createRandomPageState(page = index.toUInt(), listOf("$index page"))
            }
        pageStates.shuffled().forEach { pageState: PageState<String> ->
            paginator.setPageState(pageState, silently = true)
        }

        assertEquals(pageStates.size, paginator.size)

        assertEquals(pageStates, paginator.pageStates)
        assertEquals(pageStates.map { it.page }, paginator.pages)
        pageStates.forEach { pageState: PageState<String> ->
            assertEquals(pageState, paginator[pageState.page])
        }
        pageStates.forEach { pageState: PageState<String> ->
            val removed = paginator.removePageState(pageState.page, silently = true)!!
            assertEquals(pageState.page, removed.page)
            assertEquals(pageState.data, removed.data)
        }

        assertEquals(0, paginator.size)
    }

    @Test
    fun `test jump and next`(): Unit = runBlocking {
        val paginator = MutablePaginator { page: UInt ->
            Source.getByPage(page.toInt(), this.capacity)
        }
        do {
            paginator.jump(BookmarkUInt(page = 1u))
        } while (paginator[1u] !is SuccessPage<*>)
        assertEquals(1, paginator.size)

        do {
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        } while (paginator.size < 10)
        assertEquals(10, paginator.size)
        assertEquals(
            listOf(1u, 2u, 3u, 4u, 5u, 6u, 7u, 8u, 9u, 10u),
            paginator.pages
        )
    }

    @Test
    fun `test jump and previous`(): Unit = runBlocking {
        val paginator = MutablePaginator { page: UInt ->
            Source.getByPage(page.toInt(), this.capacity)
        }
        do {
            paginator.jump(BookmarkUInt(page = 10u))
        } while (paginator[10u] !is SuccessPage<*>)
        assertEquals(1, paginator.size)

        do {
            paginator.goPreviousPage(silentlyLoading = true, silentlyResult = true)
        } while (paginator.size < 10)
        assertEquals(10, paginator.size)
        assertEquals(
            listOf(1u, 2u, 3u, 4u, 5u, 6u, 7u, 8u, 9u, 10u),
            paginator.pages
        )
    }

    @Test
    fun `test jump next previous`(): Unit = runBlocking {
        val paginator = MutablePaginator { page: UInt ->
            Source.getByPage(page.toInt(), this.capacity)
        }
        do {
            paginator.jump(BookmarkUInt(page = 20u))
        } while (paginator[20u] !is SuccessPage<*>)
        assertEquals(1, paginator.size)

        do {
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        } while (paginator[40u] !is SuccessPage<*>)
        do {
            paginator.goPreviousPage(silentlyLoading = true, silentlyResult = true)
        } while (paginator[1u] !is SuccessPage<*>)
        assertEquals((1u..40u).toList(), paginator.pages)
    }

    @Test
    fun `test jump jump and remove`(): Unit = runBlocking {
        val paginator = MutablePaginator { emptyList<String>() }.apply {
            resize(capacity = 1, resize = false, silently = true)
        }
        val data = listOf(
            SuccessPage(page = 1u, data = listOf("data of page")), // 0
            SuccessPage(page = 2u, data = listOf("data of page")), // 1
            SuccessPage(page = 3u, data = listOf("data of page")), // 2
            SuccessPage(page = 11u, data = listOf("data of page")), // 3
            SuccessPage(page = 12u, data = listOf("data of page")), // 4
            SuccessPage(page = 13u, data = listOf("data of page")), // 5
            SuccessPage(page = 21u, data = listOf("data of page")), // 6
            SuccessPage(page = 22u, data = listOf("data of page")), // 7
            SuccessPage(page = 23u, data = listOf("data of page")), // 8
        )

        assertFalse(paginator.isStarted)
        assertEquals(0, paginator.size)
        data.forEach(paginator::setPageState)
        assertEquals(data.size, paginator.size)
        paginator.jump(BookmarkUInt(page = 13u))
        assertTrue(paginator.isStarted)
        assertEquals(data[0], paginator[1u])
        assertEquals(data[1], paginator[2u])
        assertEquals(data[2], paginator[3u])
        assertEquals(data[3], paginator[11u])
        assertEquals(data[4], paginator[12u])
        assertEquals(data[5], paginator[13u])
        assertEquals(data[6], paginator[21u])
        assertEquals(data[7], paginator[22u])
        assertEquals(data[8], paginator[23u])
        assertEquals(11u, paginator.startContextPage)
        assertEquals(13u, paginator.endContextPage)

        assertEquals(data[1], paginator.removePageState(page = 2u))
        assertEquals(data[0], paginator[1u])
        assertEquals(data[2], paginator[2u])
        assertNull(paginator[3u])
        assertEquals(data[4], paginator[11u])
        assertEquals(data[5], paginator[12u])
        assertNull(paginator[13u])
        assertEquals(data[7], paginator[21u])
        assertEquals(data[8], paginator[22u])
        assertNull(paginator[23u])
        assertEquals(11u, paginator.startContextPage)
        assertEquals(12u, paginator.endContextPage)

        assertEquals(data[8], paginator.removePageState(page = 22u))
        assertEquals(data[0], paginator[1u])
        assertEquals(data[2], paginator[2u])
        assertNull(paginator[3u])
        assertEquals(data[4], paginator[11u])
        assertEquals(data[5], paginator[12u])
        assertNull(paginator[13u])
        assertEquals(data[7], paginator[21u])
        assertNull(paginator[22u])
        assertNull(paginator[23u])
        assertEquals(11u, paginator.startContextPage)
        assertEquals(12u, paginator.endContextPage)

        assertEquals(data[5], paginator.removePageState(page = 12u))
        assertEquals(data[0], paginator[1u])
        assertEquals(data[2], paginator[2u])
        assertNull(paginator[3u])
        assertEquals(data[4], paginator[11u])
        assertNull(paginator[12u])
        assertNull(paginator[13u])
        assertNull(paginator[21u])
        assertNull(paginator[22u])
        assertNull(paginator[23u])
        assertEquals(11u, paginator.startContextPage)
        assertEquals(11u, paginator.endContextPage)

        assertEquals(data[0], paginator.removePageState(page = 1u))
        assertEquals(data[2], paginator[1u])
        assertNull(paginator[2u])
        assertNull(paginator[3u])
        assertNull(paginator[11u])
        assertNull(paginator[12u])
        assertNull(paginator[13u])
        assertNull(paginator[21u])
        assertNull(paginator[22u])
        assertNull(paginator[23u])
        assertEquals(1u, paginator.startContextPage)
        assertEquals(1u, paginator.endContextPage)

        assertEquals(data[2], paginator.removePageState(page = 1u))
        assertNull(paginator[1u])
        assertNull(paginator[2u])
        assertNull(paginator[3u])
        assertNull(paginator[11u])
        assertNull(paginator[12u])
        assertNull(paginator[13u])
        assertNull(paginator[21u])
        assertNull(paginator[22u])
        assertNull(paginator[23u])
        assertEquals(0, paginator.size)
        assertEquals(0u, paginator.startContextPage)
        assertEquals(0u, paginator.endContextPage)
    }


    @Test
    fun `test context findNearContextPage`(): Unit = runBlocking {
        val paginator = MutablePaginator { emptyList<String>() }.apply {
            resize(capacity = 1, resize = false, silently = true)
        }
        val data = listOf(
            SuccessPage(page = 1u, data = listOf("data of page")), // 0
            SuccessPage(page = 2u, data = listOf("data of page")), // 1
            SuccessPage(page = 3u, data = listOf("data of page")), // 2
            SuccessPage(page = 11u, data = listOf("data of page")), // 3
            SuccessPage(page = 12u, data = listOf("data of page")), // 4
            SuccessPage(page = 13u, data = listOf("data of page")), // 5
            SuccessPage(page = 21u, data = listOf("data of page")), // 6
            SuccessPage(page = 22u, data = listOf("data of page")), // 7
            SuccessPage(page = 23u, data = listOf("data of page")), // 8
        )
        assertFalse(paginator.isStarted)
        assertEquals(0, paginator.size)
        data.forEach(paginator::setPageState)
        assertEquals(data.size, paginator.size)

        paginator.findNearContextPage(startPoint = 1u)
        assertEquals(1u, paginator.startContextPage)
        assertEquals(3u, paginator.endContextPage)

        paginator.findNearContextPage(startPoint = 5u, endPoint = 12u)
        assertEquals(11u, paginator.startContextPage)
        assertEquals(13u, paginator.endContextPage)

        paginator.findNearContextPage(startPoint = 4u, endPoint = 6u)
        assertEquals(1u, paginator.startContextPage)
        assertEquals(3u, paginator.endContextPage)

        paginator.findNearContextPage(startPoint = 7u)
        assertEquals(1u, paginator.startContextPage)
        assertEquals(3u, paginator.endContextPage)

        paginator.findNearContextPage(startPoint = 7u, endPoint = 8u)
        assertEquals(11u, paginator.startContextPage)
        assertEquals(13u, paginator.endContextPage)

        paginator.findNearContextPage(startPoint = 9u, endPoint = 15u)
        assertEquals(11u, paginator.startContextPage)
        assertEquals(13u, paginator.endContextPage)

        paginator.findNearContextPage(startPoint = 9u, endPoint = 20u)
        assertEquals(21u, paginator.startContextPage)
        assertEquals(23u, paginator.endContextPage)
    }
}

data object Source {

    private val data = MutableList(10_000) { "data of $it" }

    suspend fun getByPage(page: Int, size: Int): MutableList<String> {
        delay((1L..10L).random())
        if ((0..100).random() < 25) throw Exception()
        require(page > 0)
        val startIndex = (page - 1) * size
        val endIndex = minOf(startIndex + size, data.size)
        return data.subList(startIndex, endIndex)
    }
}

private fun <T> createRandomPageState(page: UInt, data: List<T>): PageState<T> {
    return when ((0..100).random()) {
        in 0..24 -> ProgressPage(page, data)
        in 25..49 -> EmptyPage(page, data)
        in 50..75 -> ErrorPage(Exception(), page, data)
        else -> SuccessPage(page, data)
    }
}
