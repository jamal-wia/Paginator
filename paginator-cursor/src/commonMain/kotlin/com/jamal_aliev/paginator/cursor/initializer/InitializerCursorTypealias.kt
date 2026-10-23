package com.jamal_aliev.paginator.cursor.initializer

import com.jamal_aliev.paginator.core.load.Metadata
import com.jamal_aliev.paginator.core.page.PageState
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.page.CursorPageState

typealias InitializerCursorErrorPage<K, T> =
            (e: Exception, bookmark: CursorBookmark<K>, data: List<T>, metadata: Metadata?) -> CursorPageState.Error<K, T>

typealias InitializerCursorProgressPage<K, T> =
            (bookmark: CursorBookmark<K>, data: List<T>, metadata: Metadata?) -> CursorPageState.Progress<K, T>

typealias InitializerCursorSuccessPage<K, T> =
            (bookmark: CursorBookmark<K>, data: List<T>, metadata: Metadata?) -> CursorPageState.Success<K, T>

/** Builds the Progress state shown while the anchorless first page loads (no cursor key yet). */
typealias InitializerCursorBootstrapProgressPage<T> =
            () -> PageState.ProgressState<T>

/** Builds the Error state published when the anchorless first-page load fails. */
typealias InitializerCursorBootstrapErrorPage<T> =
            (e: Exception) -> PageState.ErrorState<T>
