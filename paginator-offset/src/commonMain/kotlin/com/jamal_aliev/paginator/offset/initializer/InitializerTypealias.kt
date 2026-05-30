package com.jamal_aliev.paginator.offset.initializer

import com.jamal_aliev.paginator.core.load.Metadata
import com.jamal_aliev.paginator.offset.page.OffsetPageState

typealias InitializerErrorPage<T> = (e: Exception, page: Int, data: List<T>, metadata: Metadata?) -> OffsetPageState.Error<T>

typealias InitializerProgressPage<T> = (page: Int, data: List<T>, metadata: Metadata?) -> OffsetPageState.Progress<T>

typealias InitializerSuccessPage<T> = (page: Int, data: List<T>, metadata: Metadata?) -> OffsetPageState.Success<T>
