package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.BookmarkInt
import com.jamal_aliev.paginator.exception.LockedException.GoNextPageWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.GoPreviousPageWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.JumpWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.RefreshWasLockedException
import com.jamal_aliev.paginator.exception.LockedException.RestartWasLockedException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull

class LockMechanismTest {

    @Test
    fun `lockJump blocks jump`() = runTest {
        val paginator = createDeterministicPaginator()
        paginator.lockJump = true
        var caught: JumpWasLockedException? = null
        try {
            paginator.jump(BookmarkInt(1))
        } catch (e: JumpWasLockedException) {
            caught = e
        }
        assertNotNull(caught)
    }

    @Test
    fun `lockJump blocks jumpForward`() = runTest {
        val paginator = createDeterministicPaginator()
        paginator.lockJump = true
        var caught: JumpWasLockedException? = null
        try {
            paginator.jumpForward()
        } catch (e: JumpWasLockedException) {
            caught = e
        }
        assertNotNull(caught)
    }

    @Test
    fun `lockJump blocks jumpBack`() = runTest {
        val paginator = createDeterministicPaginator()
        paginator.lockJump = true
        var caught: JumpWasLockedException? = null
        try {
            paginator.jumpBack()
        } catch (e: JumpWasLockedException) {
            caught = e
        }
        assertNotNull(caught)
    }

    @Test
    fun `lockGoNextPage blocks goNextPage`() = runTest {
        val paginator = createDeterministicPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.lockGoNextPage = true
        var caught: GoNextPageWasLockedException? = null
        try {
            paginator.goNextPage()
        } catch (e: GoNextPageWasLockedException) {
            caught = e
        }
        assertNotNull(caught)
    }

    @Test
    fun `lockGoPreviousPage blocks goPreviousPage`() = runTest {
        val paginator = createDeterministicPaginator()
        paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = true)
        paginator.lockGoPreviousPage = true
        var caught: GoPreviousPageWasLockedException? = null
        try {
            paginator.goPreviousPage()
        } catch (e: GoPreviousPageWasLockedException) {
            caught = e
        }
        assertNotNull(caught)
    }

    @Test
    fun `lockRestart blocks restart`() = runTest {
        val paginator = createDeterministicPaginator()
        paginator.lockRestart = true
        var caught: RestartWasLockedException? = null
        try {
            paginator.restart()
        } catch (e: RestartWasLockedException) {
            caught = e
        }
        assertNotNull(caught)
    }

    @Test
    fun `lockRefresh blocks refresh`() = runTest {
        val paginator = createDeterministicPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.lockRefresh = true
        var caught: RefreshWasLockedException? = null
        try {
            paginator.refresh(pages = listOf(1))
        } catch (e: RefreshWasLockedException) {
            caught = e
        }
        assertNotNull(caught)
    }
}
