package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirtyPagesTest {

    @Test
    fun `markDirty single page`() {
        val paginator = createDeterministicPaginator()
        paginator.cache.pagingCore.markDirty(1)
        assertTrue(paginator.cache.pagingCore.isDirty(1))
        assertFalse(paginator.cache.pagingCore.isDirty(2))
    }

    @Test
    fun `markDirty multiple pages`() {
        val paginator = createDeterministicPaginator()
        paginator.cache.pagingCore.markDirty(listOf(1, 3, 5))
        assertTrue(paginator.cache.pagingCore.isDirty(1))
        assertFalse(paginator.cache.pagingCore.isDirty(2))
        assertTrue(paginator.cache.pagingCore.isDirty(3))
        assertFalse(paginator.cache.pagingCore.isDirty(4))
        assertTrue(paginator.cache.pagingCore.isDirty(5))
    }

    @Test
    fun `clearDirty single page`() {
        val paginator = createDeterministicPaginator()
        paginator.cache.pagingCore.markDirty(listOf(1, 2, 3))
        paginator.cache.pagingCore.clearDirty(2)
        assertTrue(paginator.cache.pagingCore.isDirty(1))
        assertFalse(paginator.cache.pagingCore.isDirty(2))
        assertTrue(paginator.cache.pagingCore.isDirty(3))
    }

    @Test
    fun `clearDirty multiple pages`() {
        val paginator = createDeterministicPaginator()
        paginator.cache.pagingCore.markDirty(listOf(1, 2, 3, 4, 5))
        paginator.cache.pagingCore.clearDirty(listOf(2, 4))
        assertEquals(setOf(1, 3, 5), paginator.cache.pagingCore.dirtyPages)
    }

    @Test
    fun `clearAllDirty removes all`() {
        val paginator = createDeterministicPaginator()
        paginator.cache.pagingCore.markDirty(listOf(1, 2, 3))
        paginator.cache.pagingCore.clearAllDirty()
        assertTrue(paginator.cache.pagingCore.dirtyPages.isEmpty())
    }

    @Test
    fun `dirtyPages returns immutable copy`() {
        val paginator = createDeterministicPaginator()
        paginator.cache.pagingCore.markDirty(1)
        val snapshot = paginator.cache.pagingCore.dirtyPages
        paginator.cache.pagingCore.markDirty(2)
        // snapshot should not be affected by subsequent mutations
        assertEquals(setOf(1), snapshot)
        assertEquals(setOf(1, 2), paginator.cache.pagingCore.dirtyPages)
    }

    @Test
    fun `restart clears dirty pages`() = runTest {
        val paginator = createDeterministicPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.cache.pagingCore.markDirty(listOf(1, 2, 3))
        paginator.restart(silentlyLoading = true, silentlyResult = true)
        assertTrue(paginator.cache.pagingCore.dirtyPages.isEmpty())
    }

    @Test
    fun `refresh clears dirty flags for refreshed pages`() = runTest {
        val paginator = createDeterministicPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.cache.pagingCore.markDirty(listOf(1, 2, 3))
        paginator.refresh(
            pages = listOf(1, 2),
            loadingSilently = true,
            finalSilently = true
        )
        assertFalse(paginator.cache.pagingCore.isDirty(1))
        assertFalse(paginator.cache.pagingCore.isDirty(2))
        assertTrue(paginator.cache.pagingCore.isDirty(3))
    }
}
