package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirtyPagesTest {

    @Test
    fun `markDirty single page`() {
        val paginator = createDeterministicPaginator()
        paginator.core.markDirty(1)
        assertTrue(paginator.core.isDirty(1))
        assertFalse(paginator.core.isDirty(2))
    }

    @Test
    fun `markDirty multiple pages`() {
        val paginator = createDeterministicPaginator()
        paginator.core.markDirty(listOf(1, 3, 5))
        assertTrue(paginator.core.isDirty(1))
        assertFalse(paginator.core.isDirty(2))
        assertTrue(paginator.core.isDirty(3))
        assertFalse(paginator.core.isDirty(4))
        assertTrue(paginator.core.isDirty(5))
    }

    @Test
    fun `clearDirty single page`() {
        val paginator = createDeterministicPaginator()
        paginator.core.markDirty(listOf(1, 2, 3))
        paginator.core.clearDirty(2)
        assertTrue(paginator.core.isDirty(1))
        assertFalse(paginator.core.isDirty(2))
        assertTrue(paginator.core.isDirty(3))
    }

    @Test
    fun `clearDirty multiple pages`() {
        val paginator = createDeterministicPaginator()
        paginator.core.markDirty(listOf(1, 2, 3, 4, 5))
        paginator.core.clearDirty(listOf(2, 4))
        assertEquals(setOf(1, 3, 5), paginator.core.dirtyPages)
    }

    @Test
    fun `clearAllDirty removes all`() {
        val paginator = createDeterministicPaginator()
        paginator.core.markDirty(listOf(1, 2, 3))
        paginator.core.clearAllDirty()
        assertTrue(paginator.core.dirtyPages.isEmpty())
    }

    @Test
    fun `dirtyPages returns immutable copy`() {
        val paginator = createDeterministicPaginator()
        paginator.core.markDirty(1)
        val snapshot = paginator.core.dirtyPages
        paginator.core.markDirty(2)
        // snapshot should not be affected by subsequent mutations
        assertEquals(setOf(1), snapshot)
        assertEquals(setOf(1, 2), paginator.core.dirtyPages)
    }

    @Test
    fun `restart clears dirty pages`() = runTest {
        val paginator = createDeterministicPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.core.markDirty(listOf(1, 2, 3))
        paginator.restart(silentlyLoading = true, silentlyResult = true)
        assertTrue(paginator.core.dirtyPages.isEmpty())
    }

    @Test
    fun `refresh clears dirty flags for refreshed pages`() = runTest {
        val paginator = createDeterministicPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.core.markDirty(listOf(1, 2, 3))
        paginator.refresh(
            pages = listOf(1, 2),
            loadingSilently = true,
            finalSilently = true
        )
        assertFalse(paginator.core.isDirty(1))
        assertFalse(paginator.core.isDirty(2))
        assertTrue(paginator.core.isDirty(3))
    }
}
