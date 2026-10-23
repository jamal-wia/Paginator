package com.jamal_aliev.paginator.cursor.cache

import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.page.CursorPageState

/**
 * A view of [delegate] whose reads and replacing writes do not count as a use of a page:
 * [getStateOf] / [getElement] go through [CursorPagingCache.peekStateOf], and [setState] of a page
 * that is already cached goes through [CursorPagingCache.setStateUntracked]. A write that **adds** a
 * page stays a plain [setState], so eviction strategies still register the new page.
 *
 * [com.jamal_aliev.paginator.cursor.CursorPaginator.cache] returns this view while the core runs in
 * untracked mode ([com.jamal_aliev.paginator.cursor.CursorPagingCore.withUntrackedReads]), so
 * element-level CRUD done as bookkeeping — applying a reactive source's events — does not reshape an
 * LRU order or restart TTL timers.
 */
internal class UntrackedCursorPagingCache<K : Any, T>(
    private val delegate: CursorPagingCache<K, T>,
) : CursorPagingCache<K, T> by delegate {

    override fun getStateOf(self: K): CursorPageState<K, T>? = delegate.peekStateOf(self)

    override fun getElement(self: K, index: Int): T? = delegate.peekStateOf(self)?.data?.get(index)

    override fun setState(cursor: CursorBookmark<K>, state: CursorPageState<K, T>, silently: Boolean) {
        if (delegate.peekStateOf(cursor.self) != null) delegate.setStateUntracked(cursor, state, silently)
        else delegate.setState(cursor, state, silently)
    }
}
