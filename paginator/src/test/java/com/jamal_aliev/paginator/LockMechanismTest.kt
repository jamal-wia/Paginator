package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import com.jamal_aliev.paginator.exception.LockedException.GoNextPageWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.GoPreviousPageWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.JumpWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.RefreshWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.RestartWasLockedException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class LockMechanismTest {

    @Test
    fun `lockJump blocks jump`() {
        val paginator = createDeterministicPaginator()
        paginator.lockJump = true
        assertThrows(JumpWasLockedException::class.java) {
            runTest { paginator.jump(BookmarkInt(1)) }
        }
    }

    @Test
    fun `lockJump blocks jumpForward`() {
        val paginator = createDeterministicPaginator()
        paginator.lockJump = true
        assertThrows(JumpWasLockedException::class.java) {
            runTest { paginator.jumpForward() }
        }
    }

    @Test
    fun `lockJump blocks jumpBack`() {
        val paginator = createDeterministicPaginator()
        paginator.lockJump = true
        assertThrows(JumpWasLockedException::class.java) {
            runTest { paginator.jumpBack() }
        }
    }

    @Test
    fun `lockGoNextPage blocks goNextPage`() {
        val paginator = createDeterministicPaginator()
        runTest {
            paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        }
        paginator.lockGoNextPage = true
        assertThrows(GoNextPageWasLockedException::class.java) {
            runTest { paginator.goNextPage() }
        }
    }

    @Test
    fun `lockGoPreviousPage blocks goPreviousPage`() {
        val paginator = createDeterministicPaginator()
        runTest {
            paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = true)
        }
        paginator.lockGoPreviousPage = true
        assertThrows(GoPreviousPageWasLockedException::class.java) {
            runTest { paginator.goPreviousPage() }
        }
    }

    @Test
    fun `lockRestart blocks restart`() {
        val paginator = createDeterministicPaginator()
        paginator.lockRestart = true
        assertThrows(RestartWasLockedException::class.java) {
            runTest { paginator.restart() }
        }
    }

    @Test
    fun `lockRefresh blocks refresh`() {
        val paginator = createDeterministicPaginator()
        runTest {
            paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        }
        paginator.lockRefresh = true
        assertThrows(RefreshWasLockedException::class.java) {
            runTest { paginator.refresh(pages = listOf(1)) }
        }
    }
}
