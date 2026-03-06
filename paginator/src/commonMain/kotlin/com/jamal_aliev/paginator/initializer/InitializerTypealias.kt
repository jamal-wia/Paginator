package com.jamal_aliev.paginator.initializer

import com.jamal_aliev.paginator.page.PageState.EmptyPage
import com.jamal_aliev.paginator.page.PageState.ErrorPage
import com.jamal_aliev.paginator.page.PageState.ProgressPage
import com.jamal_aliev.paginator.page.PageState.SuccessPage

typealias InitializerEmptyPage<T> = (page: Int, data: List<T>) -> EmptyPage<T>

typealias InitializerErrorPage<T> = (e: Exception, page: Int, data: List<T>) -> ErrorPage<T>

typealias InitializerProgressPage<T> = (page: Int, data: List<T>) -> ProgressPage<T>

typealias InitializerSuccessPage<T> = (page: Int, data: List<T>) -> SuccessPage<T>
