package com.jamal_aliev.paginator.exception

/**
 * Exception thrown when a [loadGuard][com.jamal_aliev.paginator.MutablePaginator.goNextPage]
 * callback returns `false`, indicating that the page load was rejected by the guard condition.
 *
 * The `loadGuard` lambda is invoked before a page is actually loaded from the source.
 * If it returns `false`, the paginator aborts the operation and throws this exception,
 * allowing the caller to handle the rejection (e.g., show a message, throttle requests, etc.).
 *
 * @param attemptedPage The page number that the paginator attempted to load.
 */
open class LoadGuardedException(
    val attemptedPage: UInt
) : Exception(
    "Load guard rejected loading of page $attemptedPage"
)
