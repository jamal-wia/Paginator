package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.BookmarkInt
import com.jamal_aliev.paginator.exception.LoadGuardedException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LoadGuardTest {

    @Test
    fun `jump throws LoadGuardedException when guard rejects`() = runTest {
        val paginator = createDeterministicPaginator()
        var caught: LoadGuardedException? = null
        try {
            paginator.jump(bookmark = BookmarkInt(1), loadGuard = { _, _ -> false })
        } catch (e: LoadGuardedException) {
            caught = e
        }
        assertNotNull(caught)
    }

    @Test
    fun `LoadGuardedException has correct attempted page`() = runTest {
        val paginator = createDeterministicPaginator()
        try {
            paginator.jump(bookmark = BookmarkInt(3), loadGuard = { _, _ -> false })
        } catch (e: LoadGuardedException) {
            assertEquals(3, e.attemptedPage)
        }
    }

    @Test
    fun `goNextPage throws LoadGuardedException when guard rejects`() = runTest {
        val paginator = createDeterministicPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        var caught: LoadGuardedException? = null
        try {
            paginator.goNextPage(loadGuard = { _, _ -> false })
        } catch (e: LoadGuardedException) {
            caught = e
        }
        assertNotNull(caught)
    }

    @Test
    fun `goPreviousPage throws LoadGuardedException when guard rejects`() = runTest {
        val paginator = createDeterministicPaginator()
        paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = true)
        var caught: LoadGuardedException? = null
        try {
            paginator.goPreviousPage(loadGuard = { _, _ -> false })
        } catch (e: LoadGuardedException) {
            caught = e
        }
        assertNotNull(caught)
    }

    @Test
    fun `restart throws LoadGuardedException when guard rejects`() = runTest {
        val paginator = createDeterministicPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        var caught: LoadGuardedException? = null
        try {
            paginator.restart(loadGuard = { _, _ -> false })
        } catch (e: LoadGuardedException) {
            caught = e
        }
        assertNotNull(caught)
    }

    @Test
    fun `refresh throws LoadGuardedException when any page guard rejects`() = runTest {
        val paginator = createDeterministicPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        var caught: LoadGuardedException? = null
        try {
            paginator.refresh(pages = listOf(1, 2), loadGuard = { page, _ -> page != 2 })
        } catch (e: LoadGuardedException) {
            caught = e
        }
        assertNotNull(caught)
    }

    @Test
    fun `loadGuard allows selective page blocking`() = runTest {
        val paginator = createDeterministicPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(
            silentlyLoading = true,
            silentlyResult = true,
            loadGuard = { page, _ -> page <= 2 }
        )
        assertEquals(2, paginator.cache.endContextPage)

        var caught: LoadGuardedException? = null
        try {
            paginator.goNextPage(loadGuard = { page, _ -> page <= 2 })
        } catch (e: LoadGuardedException) {
            caught = e
        }
        assertNotNull(caught)
    }
}
