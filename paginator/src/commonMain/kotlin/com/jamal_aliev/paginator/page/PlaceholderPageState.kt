package com.jamal_aliev.paginator.page

sealed interface PlaceholderPageState<R> {

    val placeholders: List<R>

    class PlaceholderErrorPage<T, R>(
        exception: Exception,
        page: Int,
        data: List<T>,
        override val placeholders: List<R>
    ) : PageState.ErrorPage<T>(exception, page, data), PlaceholderPageState<R>

    class PlaceholderProgressPage<T, R>(
        page: Int,
        data: List<T>,
        override val placeholders: List<R>
    ) : PageState.ProgressPage<T>(page, data), PlaceholderPageState<R>

    class PlaceholderSuccessPage<T, R>(
        page: Int,
        data: List<T>,
        override val placeholders: List<R>
    ) : PageState.SuccessPage<T>(page, data), PlaceholderPageState<R>

    class PlaceholderEmptyPage<T, R>(
        page: Int,
        data: List<T>,
        override val placeholders: List<R>
    ) : PageState.EmptyPage<T>(page, data), PlaceholderPageState<R>

}
