package com.jamal_aliev.paginator.cursor.page

import com.jamal_aliev.paginator.core.load.Metadata
import com.jamal_aliev.paginator.core.page.PageState
import com.jamal_aliev.paginator.core.page.PlaceholderPageState
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark

/**
 * Cursor placeholder page states — concrete [CursorPageState] subclasses that also carry a
 * parallel list of [PlaceholderPageState.placeholders] for skeleton/shimmer UIs.
 */

class CursorPlaceholderErrorPage<K : Any, T, R>(
    exception: Exception,
    bookmark: CursorBookmark<K>,
    data: List<T>,
    override val placeholders: List<R>,
    metadata: Metadata? = null,
    id: Long = PageState.nextId(),
) : CursorPageState.Error<K, T>(exception, bookmark, data, metadata, id), PlaceholderPageState<R> {

    override fun copy(
        bookmark: CursorBookmark<K>,
        data: List<T>,
        metadata: Metadata?,
        id: Long
    ): CursorPlaceholderErrorPage<K, T, R> {
        return CursorPlaceholderErrorPage(exception, bookmark, data, placeholders, metadata, id)
    }

    override fun copy(
        exception: Exception,
        bookmark: CursorBookmark<K>,
        data: List<T>,
        metadata: Metadata?,
        id: Long,
    ): CursorPlaceholderErrorPage<K, T, R> {
        return CursorPlaceholderErrorPage(exception, bookmark, data, placeholders, metadata, id)
    }

    fun copy(
        exception: Exception = this.exception,
        bookmark: CursorBookmark<K> = this.bookmark,
        data: List<T> = this.data,
        placeholders: List<R> = this.placeholders,
        metadata: Metadata? = this.metadata,
        id: Long = this.id,
    ): CursorPlaceholderErrorPage<K, T, R> {
        return CursorPlaceholderErrorPage(exception, bookmark, data, placeholders, metadata, id)
    }
}

class CursorPlaceholderProgressPage<K : Any, T, R>(
    bookmark: CursorBookmark<K>,
    data: List<T>,
    override val placeholders: List<R>,
    metadata: Metadata? = null,
    id: Long = PageState.nextId(),
) : CursorPageState.Progress<K, T>(bookmark, data, metadata, id), PlaceholderPageState<R> {

    override fun copy(
        bookmark: CursorBookmark<K>,
        data: List<T>,
        metadata: Metadata?,
        id: Long
    ): CursorPlaceholderProgressPage<K, T, R> {
        return CursorPlaceholderProgressPage(bookmark, data, placeholders, metadata, id)
    }

    fun copy(
        bookmark: CursorBookmark<K> = this.bookmark,
        data: List<T> = this.data,
        placeholders: List<R> = this.placeholders,
        metadata: Metadata? = this.metadata,
        id: Long = this.id,
    ): CursorPlaceholderProgressPage<K, T, R> {
        return CursorPlaceholderProgressPage(bookmark, data, placeholders, metadata, id)
    }
}

class CursorPlaceholderSuccessPage<K : Any, T, R>(
    bookmark: CursorBookmark<K>,
    data: List<T>,
    override val placeholders: List<R>,
    metadata: Metadata? = null,
    id: Long = PageState.nextId(),
) : CursorPageState.Success<K, T>(bookmark, data, metadata, id), PlaceholderPageState<R> {

    override fun copy(
        bookmark: CursorBookmark<K>,
        data: List<T>,
        metadata: Metadata?,
        id: Long
    ): CursorPlaceholderSuccessPage<K, T, R> {
        return CursorPlaceholderSuccessPage(bookmark, data, placeholders, metadata, id)
    }

    fun copy(
        bookmark: CursorBookmark<K> = this.bookmark,
        data: List<T> = this.data,
        placeholders: List<R> = this.placeholders,
        metadata: Metadata? = this.metadata,
        id: Long = this.id,
    ): CursorPlaceholderSuccessPage<K, T, R> {
        return CursorPlaceholderSuccessPage(bookmark, data, placeholders, metadata, id)
    }
}
