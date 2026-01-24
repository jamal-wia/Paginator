package com.jamal_aliev.paginator.exception

/**
 * Exception thrown when attempting to navigate to a page that exceeds the final allowed page.
 *
 * @param attemptedPage The page number that was attempted to be accessed.
 * @param finalPage The final page number allowed.
 */
class FinalPageExceededException(
    val attemptedPage: UInt,
    val finalPage: UInt
) : Exception(
    "Attempted to access page $attemptedPage, which exceeds the final allowed page $finalPage"
)
