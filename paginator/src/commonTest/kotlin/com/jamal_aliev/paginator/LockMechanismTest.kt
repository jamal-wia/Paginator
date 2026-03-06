package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import com.jamal_aliev.paginator.exception.LockedException.GoNextPageWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.GoPreviousPageWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.JumpWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.RefreshWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.RestartWasLockedException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class LockMechanismTest {

    @Test
    fun `lockJump blocks jump`() {
        val paginator = createDeterministicPaginator()
        paginator.lockJump = true
        assertFailsWith<JumpWasLockedException> {
            runTest { paginator.jump(BookmarkInt(1)) }
        }
    }

    @Test
    fun `lockJump blocks jumpForward`() {
        val paginator = createDeterministicPaginator()
        paginator.lockJump = true
        assertFailsWith<JumpWasLockedException> {
            runTest { paginator.jumpForward() }
        }
    }

    @Test
    fun `lockJump blocks jumpBack`() {
        val paginator = createDeterministicPaginator()
        paginator.lockJump = true
        assertFailsWith<JumpWasLockedException> {
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
        assertFailsWith<GoNextPageWasLockedException> {
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
        assertFailsWith<GoPreviousPageWasLockedException> {
            runTest { paginator.goPreviousPage() }
        }
    }

    @Test
    fun `lockRestart blocks restart`() {
        val paginator = createDeterministicPaginator()
        paginator.lockRestart = true
        assertFailsWith<RestartWasLockedException> {
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
        assertFailsWith<RefreshWasLockedException> {
            runTest { paginator.refresh(pages = listOf(1)) }
        }
    }
}
