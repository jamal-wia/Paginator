package com.jamal_aliev.paginator.initializer

import com.jamal_aliev.paginator.page.PageState.EmptyPage
import com.jamal_aliev.paginator.page.PageState.ErrorPage
import com.jamal_aliev.paginator.page.PageState.ProgressPage
import com.jamal_aliev.paginator.page.PageState.SuccessPage

internal typealias InitializerEmptyPage<T> = (page: UInt, data: List<T>) -> EmptyPage<T>

internal typealias InitializerErrorPage<T> = (e: Exception, page: UInt, data: List<T>) -> ErrorPage<T>

internal typealias InitializerProgressPage<T> = (page: UInt, data: List<T>) -> ProgressPage<T>

internal typealias InitializerSuccessPage<T> = (page: UInt, data: List<T>) -> SuccessPage<T>
