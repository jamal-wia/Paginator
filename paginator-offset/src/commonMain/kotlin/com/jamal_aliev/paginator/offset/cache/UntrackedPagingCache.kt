package com.jamal_aliev.paginator.offset.cache

import com.jamal_aliev.paginator.offset.page.OffsetPageState

/**
 * A view of [delegate] whose reads and replacing writes do not count as a use of a page:
 * [getStateOf] / [getElement] go through [PagingCache.peekStateOf], and [setState] of a page that
 * is already cached goes through [PagingCache.setStateUntracked]. A write that **adds** a page stays
 * a plain [setState], so eviction strategies still register the new page.
 *
 * [com.jamal_aliev.paginator.offset.Paginator.cache] returns this view while the core runs in
 * untracked mode ([com.jamal_aliev.paginator.offset.PagingCore.withUntrackedReads]), so element-level
 * CRUD done as bookkeeping — applying a reactive source's events — does not reshape an LRU order or
 * restart TTL timers.
 */
internal class UntrackedPagingCache<T>(
    private val delegate: PagingCache<T>,
) : PagingCache<T> by delegate {

    override fun getStateOf(page: Int): OffsetPageState<T>? = delegate.peekStateOf(page)

    override fun getElement(page: Int, index: Int): T? = delegate.peekStateOf(page)?.data?.get(index)

    override fun setState(state: OffsetPageState<T>, silently: Boolean) {
        if (delegate.peekStateOf(state.page) != null) delegate.setStateUntracked(state, silently)
        else delegate.setState(state, silently)
    }
}
