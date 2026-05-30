package com.jamal_aliev.paginator.cursor.page

import com.jamal_aliev.paginator.core.load.Metadata
import com.jamal_aliev.paginator.core.page.PageState
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark

/**
 * Cursor page-state tree.
 *
 * Each state is keyed by a typed [bookmark] ([CursorBookmark] of key type [K]) rather than a
 * numeric page — the natural identity for cursor pagination, where pages are linked via
 * `prev`/`next` and ordered by walking those links (not by numeric comparison). The three
 * concrete subclasses implement the strategy-agnostic status markers from [PageState]
 * ([PageState.ErrorState], [PageState.ProgressState], [PageState.SuccessState]) so that shared
 * code (UI mapping, predicates) keeps working without knowing the strategy.
 *
 * @param K the cursor key type.
 * @param E the element type of the page payload.
 */
sealed class CursorPageState<K : Any, E>(
    open val bookmark: CursorBookmark<K>,
    override val data: List<E>,
    override val metadata: Metadata? = null,
    override val id: Long = PageState.nextId(),
) : PageState<E> {

    abstract override fun <R> withData(data: List<R>): CursorPageState<K, R>

    /** Returns a copy of this page state with the supplied fields replaced. */
    abstract fun copy(
        bookmark: CursorBookmark<K> = this.bookmark,
        data: List<E> = this.data,
        metadata: Metadata? = this.metadata,
        id: Long = this.id,
    ): CursorPageState<K, E>

    override fun toString() = "${this::class.simpleName}(self=${bookmark.self} id=$id data=$data)"
    override fun hashCode(): Int = bookmark.self.hashCode()
    override fun equals(other: Any?): Boolean = (other as? PageState<*>)?.id == id

    open class Error<K : Any, T>(
        override val exception: Exception,
        override val bookmark: CursorBookmark<K>,
        override val data: List<T>,
        override val metadata: Metadata? = null,
        override val id: Long = PageState.nextId(),
    ) : CursorPageState<K, T>(bookmark, data, metadata, id), PageState.ErrorState<T> {

        override fun <R> withData(data: List<R>): Error<K, R> =
            Error(exception, bookmark, data, metadata, id)

        open fun copy(
            exception: Exception = this.exception,
            bookmark: CursorBookmark<K> = this.bookmark,
            data: List<T> = this.data,
            metadata: Metadata? = this.metadata,
            id: Long = this.id,
        ): Error<K, T> = Error(exception, bookmark, data, metadata, id)

        override fun copy(
            bookmark: CursorBookmark<K>,
            data: List<T>,
            metadata: Metadata?,
            id: Long
        ): Error<K, T> =
            copy(this.exception, bookmark, data, metadata, id)

        override fun toString(): String =
            "${this::class.simpleName}(exception=$exception, self=${bookmark.self}, data=$data)"
    }

    open class Progress<K : Any, T>(
        override val bookmark: CursorBookmark<K>,
        override val data: List<T>,
        override val metadata: Metadata? = null,
        override val id: Long = PageState.nextId(),
    ) : CursorPageState<K, T>(bookmark, data, metadata, id), PageState.ProgressState<T> {

        override fun <R> withData(data: List<R>): Progress<K, R> =
            Progress(bookmark, data, metadata, id)

        override fun copy(
            bookmark: CursorBookmark<K>,
            data: List<T>,
            metadata: Metadata?,
            id: Long
        ): Progress<K, T> =
            Progress(bookmark, data, metadata, id)
    }

    open class Success<K : Any, T>(
        override val bookmark: CursorBookmark<K>,
        override val data: List<T>,
        override val metadata: Metadata? = null,
        override val id: Long = PageState.nextId(),
    ) : CursorPageState<K, T>(bookmark, data, metadata, id), PageState.SuccessState<T> {

        override fun <R> withData(data: List<R>): Success<K, R> =
            Success(bookmark, data, metadata, id)

        override fun copy(
            bookmark: CursorBookmark<K>,
            data: List<T>,
            metadata: Metadata?,
            id: Long
        ): Success<K, T> =
            Success(bookmark, data, metadata, id)
    }
}
