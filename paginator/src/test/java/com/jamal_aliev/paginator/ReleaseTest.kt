package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTest {

    @Test
    fun `release clears cache and resets state`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        assertTrue(paginator.core.size > 0)
        assertTrue(paginator.core.isStarted)

        paginator.release()

        assertEquals(0, paginator.core.size)
        assertFalse(paginator.core.isStarted)
        assertEquals(0, paginator.core.startContextPage)
        assertEquals(0, paginator.core.endContextPage)
        assertEquals(Int.MAX_VALUE, paginator.finalPage)
        assertEquals(PagingCore.DEFAULT_CAPACITY, paginator.core.capacity)
        assertFalse(paginator.lockJump)
        assertFalse(paginator.lockGoNextPage)
        assertFalse(paginator.lockGoPreviousPage)
        assertFalse(paginator.lockRestart)
        assertFalse(paginator.lockRefresh)
        assertTrue(paginator.core.dirtyPages.isEmpty())
    }

    @Test
    fun `release with custom capacity`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 5)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        paginator.release(capacity = 10)

        assertEquals(10, paginator.core.capacity)
        assertEquals(0, paginator.core.size)
    }

    @Test
    fun `release resets bookmarks to page 1`() = runTest {
        val paginator = createDeterministicPaginator()
        paginator.bookmarks.clear()
        paginator.bookmarks.add(BookmarkInt(5))
        paginator.bookmarks.add(BookmarkInt(10))

        paginator.release()

        assertEquals(1, paginator.bookmarks.size)
        assertEquals(1, paginator.bookmarks[0].page)
    }

    @Test
    fun `release resets lock flags`() = runTest {
        val paginator = createDeterministicPaginator()
        paginator.lockJump = true
        paginator.lockGoNextPage = true
        paginator.lockGoPreviousPage = true
        paginator.lockRestart = true
        paginator.lockRefresh = true

        paginator.release()

        assertFalse(paginator.lockJump)
        assertFalse(paginator.lockGoNextPage)
        assertFalse(paginator.lockGoPreviousPage)
        assertFalse(paginator.lockRestart)
        assertFalse(paginator.lockRefresh)
    }

    @Test
    fun `release clears dirty pages`() = runTest {
        val paginator = createDeterministicPaginator()
        paginator.core.markDirty(1)
        paginator.core.markDirty(2)
        paginator.core.markDirty(3)

        assertEquals(3, paginator.core.dirtyPages.size)

        paginator.release()

        assertTrue(paginator.core.dirtyPages.isEmpty())
    }
}
