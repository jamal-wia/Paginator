package com.jamal_aliev.paginator.offset.page

import com.jamal_aliev.paginator.core.load.Metadata
import com.jamal_aliev.paginator.core.page.PageState

/**
 * Offset/page-number page-state tree.
 *
 * Each state is keyed by a numeric [page] — the natural identity for offset pagination, where
 * pages are random-accessible by index and ordered by [compareTo]. The three concrete subclasses
 * implement the strategy-agnostic status markers from [PageState] ([PageState.ErrorState],
 * [PageState.ProgressState], [PageState.SuccessState]) so that shared code (UI mapping, predicates)
 * keeps working without knowing the strategy.
 *
 * @param E the element type of the page payload.
 */
sealed class OffsetPageState<E>(
    open val page: Int,
    override val data: List<E>,
    override val metadata: Metadata? = null,
    override val id: Long = PageState.nextId(),
) : PageState<E>, Comparable<OffsetPageState<*>> {

    abstract override fun <R> withData(data: List<R>): OffsetPageState<R>

    /** Returns a copy of this page state with the supplied fields replaced. */
    abstract fun copy(
        page: Int = this.page,
        data: List<E> = this.data,
        metadata: Metadata? = this.metadata,
        id: Long = this.id,
    ): OffsetPageState<E>

    override fun toString() = "${this::class.simpleName}(page=$page id=$id data=$data)"
    override fun hashCode(): Int = page.hashCode()
    override fun equals(other: Any?): Boolean = (other as? PageState<*>)?.id == id
    override fun compareTo(other: OffsetPageState<*>): Int = page.compareTo(other.page)

    open class Error<T>(
        override val exception: Exception,
        override val page: Int,
        override val data: List<T>,
        override val metadata: Metadata? = null,
        override val id: Long = PageState.nextId(),
    ) : OffsetPageState<T>(page, data, metadata, id), PageState.ErrorState<T> {

        override fun <R> withData(data: List<R>): Error<R> =
            Error(exception, page, data, metadata, id)

        open fun copy(
            exception: Exception = this.exception,
            page: Int = this.page,
            data: List<T> = this.data,
            metadata: Metadata? = this.metadata,
            id: Long = this.id,
        ): Error<T> = Error(exception, page, data, metadata, id)

        override fun copy(page: Int, data: List<T>, metadata: Metadata?, id: Long): Error<T> =
            copy(this.exception, page, data, metadata, id)

        override fun toString(): String =
            "${this::class.simpleName}(exception=$exception, page=$page, data=$data)"
    }

    open class Progress<T>(
        override val page: Int,
        override val data: List<T>,
        override val metadata: Metadata? = null,
        override val id: Long = PageState.nextId(),
    ) : OffsetPageState<T>(page, data, metadata, id), PageState.ProgressState<T> {

        override fun <R> withData(data: List<R>): Progress<R> =
            Progress(page, data, metadata, id)

        override fun copy(page: Int, data: List<T>, metadata: Metadata?, id: Long): Progress<T> =
            Progress(page, data, metadata, id)
    }

    open class Success<T>(
        override val page: Int,
        override val data: List<T>,
        override val metadata: Metadata? = null,
        override val id: Long = PageState.nextId(),
    ) : OffsetPageState<T>(page, data, metadata, id), PageState.SuccessState<T> {

        override fun <R> withData(data: List<R>): Success<R> =
            Success(page, data, metadata, id)

        override fun copy(page: Int, data: List<T>, metadata: Metadata?, id: Long): Success<T> =
            Success(page, data, metadata, id)
    }
}
