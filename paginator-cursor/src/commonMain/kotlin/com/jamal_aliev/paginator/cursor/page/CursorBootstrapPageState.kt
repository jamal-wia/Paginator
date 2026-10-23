package com.jamal_aliev.paginator.cursor.page

import com.jamal_aliev.paginator.core.load.Metadata
import com.jamal_aliev.paginator.core.page.PageState

/**
 * State of the **anchorless first load** of a [com.jamal_aliev.paginator.cursor.CursorPaginator] —
 * a `restart()` without
 * [initialCursor][com.jamal_aliev.paginator.cursor.CursorPaginator.initialCursor], or the implicit
 * bootstrap of `goNextPage()` on a paginator that has not been started.
 *
 * Until the source answers there is no cursor key, so this state is deliberately **not** a
 * [CursorPageState]: it has no bookmark and never enters the key-addressed cache. It is published
 * through [bootstrapState][com.jamal_aliev.paginator.cursor.CursorPagingCore.bootstrapState],
 * [visibleSnapshot][com.jamal_aliev.paginator.cursor.CursorPagingCore.visibleSnapshot] and
 * `uiState` instead, and disappears as soon as the first real page is cached.
 *
 * Build your own subclasses (e.g. a skeleton with placeholder items) through
 * [initializerBootstrapProgressPage][com.jamal_aliev.paginator.cursor.CursorPagingCore.initializerBootstrapProgressPage]
 * / [initializerBootstrapErrorPage][com.jamal_aliev.paginator.cursor.CursorPagingCore.initializerBootstrapErrorPage].
 *
 * @param E The element type of the page payload.
 */
sealed class CursorBootstrapPageState<E>(
    override val data: List<E>,
    override val metadata: Metadata? = null,
    override val id: Long = PageState.nextId(),
) : PageState<E> {

    override fun toString() = "${this::class.simpleName}(bootstrap id=$id data=$data)"
    override fun hashCode(): Int = id.hashCode()
    override fun equals(other: Any?): Boolean = (other as? PageState<*>)?.id == id

    /** The first page is being requested (`load(null)` is in flight). */
    open class Progress<E>(
        override val data: List<E> = emptyList(),
        override val metadata: Metadata? = null,
        override val id: Long = PageState.nextId(),
    ) : CursorBootstrapPageState<E>(data, metadata, id), PageState.ProgressState<E> {

        override fun <R> withData(data: List<R>): Progress<R> = Progress(data, metadata, id)
    }

    /** The first-page request failed; the cache stays empty until a retry succeeds. */
    open class Error<E>(
        override val exception: Exception,
        override val data: List<E> = emptyList(),
        override val metadata: Metadata? = null,
        override val id: Long = PageState.nextId(),
    ) : CursorBootstrapPageState<E>(data, metadata, id), PageState.ErrorState<E> {

        override fun <R> withData(data: List<R>): Error<R> = Error(exception, data, metadata, id)

        override fun toString() = "Error(bootstrap id=$id exception=$exception data=$data)"
    }
}
