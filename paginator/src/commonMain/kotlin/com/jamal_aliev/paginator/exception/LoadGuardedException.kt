package com.jamal_aliev.paginator.exception

/**
 * Base class for "load guard rejected the load" exceptions.
 *
 * A `loadGuard` lambda is invoked by every navigation/refresh entry point **before**
 * a page is loaded from the source. When the guard returns `false` the paginator
 * aborts the operation and throws an instance of this class — either the
 * offset-based [LoadGuardedException] (carrying an `Int` page) or the cursor-based
 * [CursorLoadGuardedException] (carrying a [com.jamal_aliev.paginator.bookmark.CursorBookmark]).
 *
 * Catch this base class when a handler does not care which flavour of paginator
 * produced the rejection; otherwise catch the concrete subclass.
 *
 * @param attemptedKey The page key that the paginator attempted to load. For the
 *   offset paginator this is the page number boxed as [Int]. For the cursor
 *   paginator it is the [com.jamal_aliev.paginator.bookmark.CursorBookmark.self] key
 *   (or the full bookmark — see [CursorLoadGuardedException]).
 */
open class LoadGuardedBaseException(
    val attemptedKey: Any,
    message: String,
) : Exception(message)

/**
 * Exception thrown when a [loadGuard][com.jamal_aliev.paginator.Paginator.goNextPage]
 * callback returns `false`, indicating that the page load was rejected by the guard condition.
 *
 * The `loadGuard` lambda is invoked before a page is actually loaded from the source.
 * If it returns `false`, the paginator aborts the operation and throws this exception,
 * allowing the caller to handle the rejection (e.g., show a message, throttle requests, etc.).
 *
 * @param attemptedPage The page number that the paginator attempted to load.
 */
open class LoadGuardedException(
    val attemptedPage: Int
) : LoadGuardedBaseException(
    attemptedKey = attemptedPage,
    message = "Load guard rejected loading of page $attemptedPage",
)
