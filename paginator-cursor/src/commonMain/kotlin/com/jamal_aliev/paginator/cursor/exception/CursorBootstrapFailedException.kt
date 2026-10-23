package com.jamal_aliev.paginator.cursor.exception

import com.jamal_aliev.paginator.core.page.PageState

/**
 * Thrown by [goNextPage][com.jamal_aliev.paginator.cursor.CursorPaginator.goNextPage] when it had to
 * bootstrap a paginator that was not started yet and that first-page load failed.
 *
 * There is no cursor-keyed page to return in that case: the failure is published as [state]
 * through [bootstrapState][com.jamal_aliev.paginator.cursor.CursorPagingCore.bootstrapState] and
 * `uiState`, and the cache stays empty. The next `goNextPage()` or `restart()` retries with
 * `load(null)`. [cause] is the exception thrown by the source.
 */
class CursorBootstrapFailedException(
    val state: PageState.ErrorState<*>,
) : Exception("The first-page load failed: ${state.exception.message}", state.exception)
