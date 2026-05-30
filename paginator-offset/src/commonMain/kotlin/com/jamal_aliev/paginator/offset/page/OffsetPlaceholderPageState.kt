package com.jamal_aliev.paginator.offset.page

import com.jamal_aliev.paginator.core.load.Metadata
import com.jamal_aliev.paginator.core.page.PageState
import com.jamal_aliev.paginator.core.page.PlaceholderPageState

/**
 * Offset placeholder page states — concrete [OffsetPageState] subclasses that also carry a
 * parallel list of [PlaceholderPageState.placeholders] for skeleton/shimmer UIs.
 */

class OffsetPlaceholderErrorPage<T, R>(
    exception: Exception,
    page: Int,
    data: List<T>,
    override val placeholders: List<R>,
    metadata: Metadata? = null,
    id: Long = PageState.nextId(),
) : OffsetPageState.Error<T>(exception, page, data, metadata, id), PlaceholderPageState<R> {

    override fun copy(
        page: Int,
        data: List<T>,
        metadata: Metadata?,
        id: Long
    ): OffsetPlaceholderErrorPage<T, R> {
        return OffsetPlaceholderErrorPage(exception, page, data, placeholders, metadata, id)
    }

    override fun copy(
        exception: Exception,
        page: Int,
        data: List<T>,
        metadata: Metadata?,
        id: Long,
    ): OffsetPlaceholderErrorPage<T, R> {
        return OffsetPlaceholderErrorPage(exception, page, data, placeholders, metadata, id)
    }

    fun copy(
        exception: Exception = this.exception,
        page: Int = this.page,
        data: List<T> = this.data,
        placeholders: List<R> = this.placeholders,
        metadata: Metadata? = this.metadata,
        id: Long = this.id,
    ): OffsetPlaceholderErrorPage<T, R> {
        return OffsetPlaceholderErrorPage(exception, page, data, placeholders, metadata, id)
    }
}

class OffsetPlaceholderProgressPage<T, R>(
    page: Int,
    data: List<T>,
    override val placeholders: List<R>,
    metadata: Metadata? = null,
    id: Long = PageState.nextId(),
) : OffsetPageState.Progress<T>(page, data, metadata, id), PlaceholderPageState<R> {

    override fun copy(
        page: Int,
        data: List<T>,
        metadata: Metadata?,
        id: Long
    ): OffsetPlaceholderProgressPage<T, R> {
        return OffsetPlaceholderProgressPage(page, data, placeholders, metadata, id)
    }

    fun copy(
        page: Int = this.page,
        data: List<T> = this.data,
        placeholders: List<R> = this.placeholders,
        metadata: Metadata? = this.metadata,
        id: Long = this.id,
    ): OffsetPlaceholderProgressPage<T, R> {
        return OffsetPlaceholderProgressPage(page, data, placeholders, metadata, id)
    }
}

class OffsetPlaceholderSuccessPage<T, R>(
    page: Int,
    data: List<T>,
    override val placeholders: List<R>,
    metadata: Metadata? = null,
    id: Long = PageState.nextId(),
) : OffsetPageState.Success<T>(page, data, metadata, id), PlaceholderPageState<R> {

    override fun copy(
        page: Int,
        data: List<T>,
        metadata: Metadata?,
        id: Long
    ): OffsetPlaceholderSuccessPage<T, R> {
        return OffsetPlaceholderSuccessPage(page, data, placeholders, metadata, id)
    }

    fun copy(
        page: Int = this.page,
        data: List<T> = this.data,
        placeholders: List<R> = this.placeholders,
        metadata: Metadata? = this.metadata,
        id: Long = this.id,
    ): OffsetPlaceholderSuccessPage<T, R> {
        return OffsetPlaceholderSuccessPage(page, data, placeholders, metadata, id)
    }
}
