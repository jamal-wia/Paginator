package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotAndFlowTest {

    @Test
    fun `snapshot emits pages in context range`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.jump(BookmarkInt(1))
        paginator.goNextPage()
        paginator.goNextPage()

        val snapshotValue = paginator.core.snapshot.first()
        assertTrue(snapshotValue.isNotEmpty())
        // Should contain pages in context range
        val pages = snapshotValue.map { it.page }
        assertTrue(1 in pages)
    }

    @Test
    fun `scan returns pages in range`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 5, capacity = 3)
        val result = paginator.core.scan(2..4)
        assertEquals(3, result.size)
        assertEquals(listOf(2, 3, 4), result.map { it.page })
    }

    @Test
    fun `scan skips gaps in range`() = runTest {
        val paginator = MutablePaginator<String> { emptyList() }
        paginator.core.resize(capacity = 1, resize = false, silently = true)
        paginator.core.setState(SuccessPage(page = 1, data = mutableListOf("a")), silently = true)
        paginator.core.setState(SuccessPage(page = 3, data = mutableListOf("c")), silently = true)
        paginator.core.setState(SuccessPage(page = 5, data = mutableListOf("e")), silently = true)

        val result = paginator.core.scan(1..5)
        // scan uses continue (not break), so it collects all cached pages in range
        assertEquals(3, result.size)
        assertEquals(listOf(1, 3, 5), result.map { it.page })
    }

    @Test
    fun `asFlow enables cache flow`() {
        val paginator = createDeterministicPaginator()
        assertFalse(paginator.core.enableCacheFlow)

        paginator.core.asFlow()

        assertTrue(paginator.core.enableCacheFlow)
    }

    @Test
    fun `asFlow emits cache updates`() = runTest {
        val paginator = MutablePaginator<String> { emptyList() }
        paginator.core.resize(capacity = 1, resize = false, silently = true)
        val flow = paginator.core.asFlow()

        // Initial emission
        val initial = flow.first()
        assertTrue(initial.isEmpty())

        // Add state and trigger flow
        paginator.core.setState(SuccessPage(page = 1, data = mutableListOf("a")))
        paginator.core.repeatCacheFlow()
        val updated = flow.first()
        assertEquals(1, updated.size)
        assertEquals(1, updated.first().page)
    }
}
