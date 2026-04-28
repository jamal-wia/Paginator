package com.jamal_aliev.paginator.page

import com.jamal_aliev.paginator.load.Metadata

sealed interface PlaceholderPageState<R> {

    val placeholders: List<R>

    class PlaceholderErrorPage<T, R>(
        exception: Exception,
        page: Int,
        data: List<T>,
        override val placeholders: List<R>,
        metadata: Metadata? = null,
        id: Long = nextId(),
    ) : PageState.ErrorPage<T>(exception, page, data, metadata, id), PlaceholderPageState<R> {

        override fun copy(
            page: Int,
            data: List<T>,
            metadata: Metadata?,
            id: Long
        ): PlaceholderErrorPage<T, R> {
            return PlaceholderErrorPage(exception, page, data, placeholders, metadata, id)
        }

        override fun copy(
            exception: Exception,
            page: Int,
            data: List<T>,
            metadata: Metadata?,
            id: Long,
        ): PlaceholderErrorPage<T, R> {
            return PlaceholderErrorPage(exception, page, data, placeholders, metadata, id)
        }

        fun copy(
            exception: Exception = this.exception,
            page: Int = this.page,
            data: List<T> = this.data,
            placeholders: List<R> = this.placeholders,
            metadata: Metadata? = this.metadata,
            id: Long = this.id,
        ): PlaceholderErrorPage<T, R> {
            return PlaceholderErrorPage(exception, page, data, placeholders, metadata, id)
        }
    }

    class PlaceholderProgressPage<T, R>(
        page: Int,
        data: List<T>,
        override val placeholders: List<R>,
        metadata: Metadata? = null,
        id: Long = nextId(),
    ) : PageState.ProgressPage<T>(page, data, metadata, id), PlaceholderPageState<R> {

        override fun copy(
            page: Int,
            data: List<T>,
            metadata: Metadata?,
            id: Long
        ): PlaceholderProgressPage<T, R> {
            return PlaceholderProgressPage(page, data, placeholders, metadata, id)
        }

        fun copy(
            page: Int = this.page,
            data: List<T> = this.data,
            placeholders: List<R> = this.placeholders,
            metadata: Metadata? = this.metadata,
            id: Long = this.id,
        ): PlaceholderProgressPage<T, R> {
            return PlaceholderProgressPage(page, data, placeholders, metadata, id)
        }
    }

    class PlaceholderSuccessPage<T, R>(
        page: Int,
        data: List<T>,
        override val placeholders: List<R>,
        metadata: Metadata? = null,
        id: Long = nextId(),
    ) : PageState.SuccessPage<T>(page, data, metadata, id), PlaceholderPageState<R> {

        override fun copy(
            page: Int,
            data: List<T>,
            metadata: Metadata?,
            id: Long
        ): PlaceholderSuccessPage<T, R> {
            return PlaceholderSuccessPage(page, data, placeholders, metadata, id)
        }

        fun copy(
            page: Int = this.page,
            data: List<T> = this.data,
            placeholders: List<R> = this.placeholders,
            metadata: Metadata? = this.metadata,
            id: Long = this.id,
        ): PlaceholderSuccessPage<T, R> {
            return PlaceholderSuccessPage(page, data, placeholders, metadata, id)
        }
    }

}
