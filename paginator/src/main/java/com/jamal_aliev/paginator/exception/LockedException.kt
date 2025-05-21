package com.jamal_aliev.paginator.exception

sealed class LockedException(
    override val message: String?
) : Exception(message) {

    open class JumpWasLockedException : LockedException(
        message = "Jump was locked. Please try set false to field lockJump"
    )

    open class GoNextPageWasLockedException : LockedException(
        message = "NextPage was locked. Please try set false to field lockGoNextPage"
    )

    open class GoPreviousPageWasLockedException : LockedException(
        message = "PreviousPage was locked. Please try set false to field lockGoPreviousPage"
    )

    open class RestartWasLockedException : LockedException(
        message = "Restart was locked. Please try set false to field lockRestart"
    )

    open class RefreshWasLockedException : LockedException(
        message = "Refresh was locked. Please try set false to field lockRefresh"
    )

}
