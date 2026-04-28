package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.BookmarkInt
import com.jamal_aliev.paginator.exception.FinalPageExceededException
import com.jamal_aliev.paginator.extension.isEmptyState
import com.jamal_aliev.paginator.extension.isSuccessState
import com.jamal_aliev.paginator.load.LoadResult
import com.jamal_aliev.paginator.page.PageState
import com.jamal_aliev.paginator.page.PageState.ErrorPage
import com.jamal_aliev.paginator.page.PageState.ProgressPage
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class MutablePaginatorTest {

    @Test
    fun `test set get remove page state`() {
        val paginator = MutablePaginator<String> { LoadResult(emptyList()) }
        val pageStates: MutableList<PageState<String>> =
            MutableList((1..10_000).random()) { index: Int ->
                createRandomPageState(page = index, listOf("$index page"))
            }
        pageStates.shuffled().forEach { pageState: PageState<String> ->
            paginator.cache.setState(pageState, silently = true)
        }

        assertEquals(pageStates.size, paginator.cache.size)

        assertEquals(pageStates, paginator.core.states)
        assertEquals(pageStates.map { it.page }, paginator.cache.pages)
        pageStates.forEach { pageState: PageState<String> ->
            assertEquals(pageState, paginator[pageState.page])
        }
        pageStates.forEach { pageState: PageState<String> ->
            val removed = paginator.removeState(pageState.page, silently = true)!!
            assertEquals(pageState.page, removed.page)
            assertEquals(pageState.data, removed.data)
        }

        assertEquals(0, paginator.cache.size)
    }

    @Test
    fun `test jump and next`(): Unit = runTest {
        val paginator = MutablePaginator { page: Int ->
            LoadResult(Source.getByPage(page, this.core.capacity))
        }
        do {
            paginator.jump(BookmarkInt(page = 1))
        } while (paginator[1] !is SuccessPage<*>)
        assertEquals(1, paginator.cache.size)

        do {
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        } while (paginator.cache.size < 10)
        assertEquals(10, paginator.cache.size)
        assertEquals(
            listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
            paginator.cache.pages
        )
    }

    @Test
    fun `test jump and previous`(): Unit = runTest {
        val paginator = MutablePaginator { page: Int ->
            LoadResult(Source.getByPage(page, this.core.capacity))
        }
        do {
            paginator.jump(BookmarkInt(page = 10))
        } while (paginator[10] !is SuccessPage<*>)
        assertEquals(1, paginator.cache.size)

        do {
            paginator.goPreviousPage(silentlyLoading = true, silentlyResult = true)
        } while (paginator.cache.size < 10)
        assertEquals(10, paginator.cache.size)
        assertEquals(
            listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
            paginator.cache.pages
        )
    }

    @Test
    fun `test jump next previous`(): Unit = runTest {
        val paginator = MutablePaginator { page: Int ->
            LoadResult(Source.getByPage(page, this.core.capacity))
        }
        do {
            paginator.jump(BookmarkInt(page = 20))
        } while (paginator[20] !is SuccessPage<*>)
        assertEquals(1, paginator.cache.size)

        do {
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        } while (paginator[40] !is SuccessPage<*>)
        do {
            paginator.goPreviousPage(silentlyLoading = true, silentlyResult = true)
        } while (paginator[1] !is SuccessPage<*>)
        assertEquals((1..40).toList(), paginator.cache.pages)
    }

    @Test
    fun `test jump jump and remove`(): Unit = runTest {
        val paginator = MutablePaginator { LoadResult(emptyList<String>()) }.apply {
            core.resize(capacity = 1, resize = false, silently = true)
        }
        val data = listOf(
            SuccessPage(page = 1, data = listOf("data of page")), // 0
            SuccessPage(page = 2, data = listOf("data of page")), // 1
            SuccessPage(page = 3, data = listOf("data of page")), // 2
            SuccessPage(page = 11, data = listOf("data of page")), // 3
            SuccessPage(page = 12, data = listOf("data of page")), // 4
            SuccessPage(page = 13, data = listOf("data of page")), // 5
            SuccessPage(page = 21, data = listOf("data of page")), // 6
            SuccessPage(page = 22, data = listOf("data of page")), // 7
            SuccessPage(page = 23, data = listOf("data of page")), // 8
        )

        assertFalse(paginator.cache.isStarted)
        assertEquals(0, paginator.cache.size)
        data.forEach(paginator.cache::setState)
        assertEquals(data.size, paginator.cache.size)
        paginator.jump(BookmarkInt(page = 13))
        assertTrue(paginator.cache.isStarted)
        assertEquals(data[0], paginator[1])
        assertEquals(data[1], paginator[2])
        assertEquals(data[2], paginator[3])
        assertEquals(data[3], paginator[11])
        assertEquals(data[4], paginator[12])
        assertEquals(data[5], paginator[13])
        assertEquals(data[6], paginator[21])
        assertEquals(data[7], paginator[22])
        assertEquals(data[8], paginator[23])
        assertEquals(11, paginator.cache.startContextPage)
        assertEquals(13, paginator.cache.endContextPage)

        assertEquals(data[1], paginator.removeState(pageToRemove = 2))
        assertEquals(data[0], paginator[1])
        assertEquals(data[2], paginator[2])
        assertNull(paginator[3])
        assertEquals(data[4], paginator[11])
        assertEquals(data[5], paginator[12])
        assertNull(paginator[13])
        assertEquals(data[7], paginator[21])
        assertEquals(data[8], paginator[22])
        assertNull(paginator[23])
        assertEquals(11, paginator.cache.startContextPage)
        assertEquals(12, paginator.cache.endContextPage)

        assertEquals(data[8], paginator.removeState(pageToRemove = 22))
        assertEquals(data[0], paginator[1])
        assertEquals(data[2], paginator[2])
        assertNull(paginator[3])
        assertEquals(data[4], paginator[11])
        assertEquals(data[5], paginator[12])
        assertNull(paginator[13])
        assertEquals(data[7], paginator[21])
        assertNull(paginator[22])
        assertNull(paginator[23])
        assertEquals(11, paginator.cache.startContextPage)
        assertEquals(12, paginator.cache.endContextPage)

        assertEquals(data[5], paginator.removeState(pageToRemove = 12))
        assertEquals(data[0], paginator[1])
        assertEquals(data[2], paginator[2])
        assertNull(paginator[3])
        assertEquals(data[4], paginator[11])
        assertNull(paginator[12])
        assertNull(paginator[13])
        assertNull(paginator[21])
        assertNull(paginator[22])
        assertNull(paginator[23])
        assertEquals(11, paginator.cache.startContextPage)
        assertEquals(11, paginator.cache.endContextPage)

        assertEquals(data[0], paginator.removeState(pageToRemove = 1))
        assertEquals(data[2], paginator[1])
        assertNull(paginator[2])
        assertNull(paginator[3])
        assertNull(paginator[11])
        assertNull(paginator[12])
        assertNull(paginator[13])
        assertNull(paginator[21])
        assertNull(paginator[22])
        assertNull(paginator[23])
        assertEquals(1, paginator.cache.startContextPage)
        assertEquals(1, paginator.cache.endContextPage)

        assertEquals(data[2], paginator.removeState(pageToRemove = 1))
        assertNull(paginator[1])
        assertNull(paginator[2])
        assertNull(paginator[3])
        assertNull(paginator[11])
        assertNull(paginator[12])
        assertNull(paginator[13])
        assertNull(paginator[21])
        assertNull(paginator[22])
        assertNull(paginator[23])
        assertEquals(0, paginator.cache.size)
        assertEquals(0, paginator.cache.startContextPage)
        assertEquals(0, paginator.cache.endContextPage)
    }


    @Test
    fun `test context findNearContextPage`(): Unit = runTest {
        val paginator = MutablePaginator { LoadResult(emptyList<String>()) }.apply {
            core.resize(capacity = 1, resize = false, silently = true)
        }
        val data = listOf(
            SuccessPage(page = 1, data = listOf("data of page")), // 0
            SuccessPage(page = 2, data = listOf("data of page")), // 1
            SuccessPage(page = 3, data = listOf("data of page")), // 2
            SuccessPage(page = 11, data = listOf("data of page")), // 3
            SuccessPage(page = 12, data = listOf("data of page")), // 4
            SuccessPage(page = 13, data = listOf("data of page")), // 5
            SuccessPage(page = 21, data = listOf("data of page")), // 6
            SuccessPage(page = 22, data = listOf("data of page")), // 7
            SuccessPage(page = 23, data = listOf("data of page")), // 8
        )
        assertFalse(paginator.cache.isStarted)
        assertEquals(0, paginator.cache.size)
        data.forEach(paginator.cache::setState)
        assertEquals(data.size, paginator.cache.size)

        paginator.core.findNearContextPage(startPoint = 1)
        assertEquals(1, paginator.cache.startContextPage)
        assertEquals(3, paginator.cache.endContextPage)

        paginator.core.findNearContextPage(startPoint = 5, endPoint = 12)
        assertEquals(11, paginator.cache.startContextPage)
        assertEquals(13, paginator.cache.endContextPage)

        paginator.core.findNearContextPage(startPoint = 4, endPoint = 6)
        assertEquals(1, paginator.cache.startContextPage)
        assertEquals(3, paginator.cache.endContextPage)

        paginator.core.findNearContextPage(startPoint = 7)
        assertEquals(1, paginator.cache.startContextPage)
        assertEquals(3, paginator.cache.endContextPage)

        paginator.core.findNearContextPage(startPoint = 7, endPoint = 8)
        assertEquals(11, paginator.cache.startContextPage)
        assertEquals(13, paginator.cache.endContextPage)

        paginator.core.findNearContextPage(startPoint = 9, endPoint = 15)
        assertEquals(11, paginator.cache.startContextPage)
        assertEquals(13, paginator.cache.endContextPage)

        paginator.core.findNearContextPage(startPoint = 9, endPoint = 20)
        assertEquals(21, paginator.cache.startContextPage)
        assertEquals(23, paginator.cache.endContextPage)
    }

    @Test
    fun `test finalPage with goNextPage`(): Unit = runTest {
        val paginator = MutablePaginator { page: Int ->
            // Deterministic source - no random exceptions
            LoadResult(List(this.core.capacity) { "item $it of page $page" })
        }
        paginator.finalPage = 3

        // Jump to page 1
        val page1: PageState<String> = paginator.jump(BookmarkInt(1)).second
        assertTrue(page1.isSuccessState())

        // Go to page 2
        val page2: PageState<String> = paginator.goNextPage()
        assertTrue(page2.isSuccessState())
        assertEquals(2, page2.page)

        // Go to page 3 (finalPage)
        val page3: PageState<String> = paginator.goNextPage()
        assertTrue(page3.isSuccessState())
        assertEquals(3, page3.page)

        // Try to go to page 4 (should throw FinalPageExceededException)
        try {
            paginator.goNextPage()
            fail("Expected FinalPageExceededException for page exceeding finalPage")
        } catch (e: FinalPageExceededException) {
            assertEquals(4, e.attemptedPage)
            assertEquals(3, e.finalPage)
        }
    }

    @Test
    fun `test finalPage with jump`(): Unit = runTest {
        val paginator = MutablePaginator { page: Int ->
            // Deterministic source - no random exceptions
            LoadResult(List(this.core.capacity) { "item $it of page $page" })
        }
        paginator.finalPage = 5

        // Jump to page 3 should work
        val page3: PageState<String> = paginator.jump(BookmarkInt(3)).second
        assertTrue(page3.isSuccessState())
        assertEquals(3, page3.page)

        // Jump to page 5 (finalPage) should work
        val page5: PageState<String> = paginator.jump(BookmarkInt(5)).second
        assertTrue(page5.isSuccessState())
        assertEquals(5, page5.page)

        // Try to jump to page 6 (exceeds finalPage) should throw FinalPageExceededException
        try {
            paginator.jump(BookmarkInt(6))
            fail("Expected FinalPageExceededException for page exceeding finalPage")
        } catch (e: FinalPageExceededException) {
            assertEquals(6, e.attemptedPage)
            assertEquals(5, e.finalPage)
        }
    }

    @Test
    fun `test finalPage default allows unlimited pages`(): Unit = runTest {
        val paginator = MutablePaginator { page: Int ->
            // Use a simple source that doesn't throw random exceptions
            if (page <= 10) {
                LoadResult(List(this.core.capacity) { "item $it of page $page" })
            } else {
                LoadResult(emptyList())
            }
        }
        // finalPage is Int.MAX_VALUE by default (effectively unlimited)
        assertEquals(Int.MAX_VALUE, paginator.finalPage)

        // Jump to a high page number should work (page 100)
        val page100 = paginator.jump(BookmarkInt(100)).second
        // Should be an empty SuccessPage since our source returns empty list for page > 10
        assertTrue(page100.isEmptyState())
        assertEquals(100, page100.page)

        // Jump to a lower page should work fine
        val page5 = paginator.jump(BookmarkInt(5)).second
        assertTrue(page5.isSuccessState())
        assertEquals(5, page5.page)
    }
}

private data object Source {

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

private fun <T> createRandomPageState(page: Int, data: List<T>): PageState<T> {
    return when ((0..100).random()) {
        in 0..24 -> ProgressPage(page, data)
        in 25..49 -> SuccessPage(page, emptyList())
        in 50..75 -> ErrorPage(Exception(), page, data)
        else -> SuccessPage(page, data)
    }
}
