package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import com.jamal_aliev.paginator.exception.LoadGuardedException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LoadGuardTest {

    @Test
    fun `jump throws LoadGuardedException when guard rejects`(): Unit = runTest {
        val paginator = createDeterministicPaginator()
        assertThrows(LoadGuardedException::class.java) {
            runBlocking {
                paginator.jump(
                    bookmark = BookmarkInt(1),
                    loadGuard = { _, _ -> false }
                )
            }
        }
        Unit
    }

    @Test
    fun `LoadGuardedException has correct attempted page`(): Unit = runTest {
        val paginator = createDeterministicPaginator()
        try {
            paginator.jump(
                bookmark = BookmarkInt(3),
                loadGuard = { _, _ -> false }
            )
        } catch (e: LoadGuardedException) {
            assertEquals(3, e.attemptedPage)
        }
    }

    @Test
    fun `goNextPage throws LoadGuardedException when guard rejects`(): Unit = runTest {
        val paginator = createDeterministicPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        assertThrows(LoadGuardedException::class.java) {
            runBlocking {
                paginator.goNextPage(loadGuard = { _, _ -> false })
            }
        }
        Unit
    }

    @Test
    fun `goPreviousPage throws LoadGuardedException when guard rejects`(): Unit = runTest {
        val paginator = createDeterministicPaginator()
        paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = true)
        assertThrows(LoadGuardedException::class.java) {
            runBlocking {
                paginator.goPreviousPage(loadGuard = { _, _ -> false })
            }
        }
        Unit
    }

    @Test
    fun `restart throws LoadGuardedException when guard rejects`(): Unit = runTest {
        val paginator = createDeterministicPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        assertThrows(LoadGuardedException::class.java) {
            runBlocking {
                paginator.restart(loadGuard = { _, _ -> false })
            }
        }
        Unit
    }

    @Test
    fun `refresh throws LoadGuardedException when any page guard rejects`(): Unit = runTest {
        val paginator = createDeterministicPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        assertThrows(LoadGuardedException::class.java) {
            runBlocking {
                paginator.refresh(
                    pages = listOf(1, 2),
                    loadGuard = { page, _ -> page != 2 }
                )
            }
        }
        Unit
    }

    @Test
    fun `loadGuard allows selective page blocking`(): Unit = runTest {
        val paginator = createDeterministicPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(
            silentlyLoading = true,
            silentlyResult = true,
            loadGuard = { page, _ -> page <= 2 }
        )
        assertEquals(2, paginator.endContextPage)

        assertThrows(LoadGuardedException::class.java) {
            runBlocking {
                paginator.goNextPage(loadGuard = { page, _ -> page <= 2 })
            }
        }
        Unit
    }
}
