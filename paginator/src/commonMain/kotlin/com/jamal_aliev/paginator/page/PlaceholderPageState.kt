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

        /**
         * If you want to override this function, you should check the data because it can't be empty
         * */
        override fun copy(
            page: Int,
            data: List<T>,
            metadata: Metadata?,
            id: Long
        ): PageState.SuccessPage<T> {
            return if (data.isEmpty()) PlaceholderEmptyPage(page, data, placeholders, metadata, id)
            else PlaceholderSuccessPage(page, data, placeholders, metadata, id)
        }

        fun copy(
            page: Int = this.page,
            data: List<T> = this.data,
            placeholders: List<R> = this.placeholders,
            metadata: Metadata? = this.metadata,
            id: Long = this.id,
        ): PageState.SuccessPage<T> {
            return if (data.isEmpty()) PlaceholderEmptyPage(page, data, placeholders, metadata, id)
            else PlaceholderSuccessPage(page, data, placeholders, metadata, id)
        }
    }

    class PlaceholderEmptyPage<T, R>(
        page: Int,
        data: List<T>,
        override val placeholders: List<R>,
        metadata: Metadata? = null,
        id: Long = nextId(),
    ) : PageState.EmptyPage<T>(page, data, metadata, id), PlaceholderPageState<R> {

        override fun copy(
            page: Int,
            data: List<T>,
            metadata: Metadata?,
            id: Long
        ): PlaceholderEmptyPage<T, R> {
            return PlaceholderEmptyPage(page, data, placeholders, metadata, id)
        }

        fun copy(
            page: Int = this.page,
            data: List<T> = this.data,
            placeholders: List<R> = this.placeholders,
            metadata: Metadata? = this.metadata,
            id: Long = this.id,
        ): PlaceholderEmptyPage<T, R> {
            return PlaceholderEmptyPage(page, data, placeholders, metadata, id)
        }
    }

}
