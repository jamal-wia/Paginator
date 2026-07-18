package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.core.extension.isProgressState
import com.jamal_aliev.paginator.core.extension.isSuccessState
import com.jamal_aliev.paginator.core.logger.LogComponent
import com.jamal_aliev.paginator.core.logger.debug
import com.jamal_aliev.paginator.offset.cache.persistent.PersistentPagingCache
import com.jamal_aliev.paginator.offset.extension.smartForEach
import com.jamal_aliev.paginator.offset.extension.walkBackwardWhile
import com.jamal_aliev.paginator.offset.extension.walkForwardWhile
import com.jamal_aliev.paginator.offset.load.LoadResult
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic

/**
 * The default overflow-page factory shared by the whole CRUD insert family
 * ([MutablePaginator.addAllElements], [com.jamal_aliev.paginator.offset.extension.addElement],
 * [com.jamal_aliev.paginator.offset.extension.prependElement]) and the reactive
 * [com.jamal_aliev.paginator.offset.extension.observe] bridge.
 *
 * Produces a fresh page of the **same class** as the page that overflowed (the one immediately
 * preceding [page]) via the configured `core.initializer*Page` factories, so custom subclasses and
 * fresh ids are preserved. CRUD on an Error/Progress page is legal, so a non-filled source yields the
 * matching class; an absent or Success source yields Success.
 *
 * Reads `page - 1` (the preceding page) rather than a captured coordinate so the same factory is
 * correct for every insert overload regardless of where the overflow originated. Pass `null` in place
 * of this factory to **opt out** of overflow page creation (overflow is then dropped).
 *
 * `@PublishedApi internal` (not plain `internal`) so the public **inline** CRUD helpers
 * ([com.jamal_aliev.paginator.offset.extension.insertBefore] /
 * [com.jamal_aliev.paginator.offset.extension.insertAfter]) can reference it as a default argument.
 */
@PublishedApi
internal fun <T> MutablePaginator<T>.defaultOverflowPageFactory(): (page: Int, data: List<T>) -> OffsetPageState<T> =
    { page, data ->
        when (val source = cache.getStateOf(page - 1)) {
            is OffsetPageState.Error<*> -> core.initializerErrorPage(source.exception, page, data, null)
            is OffsetPageState.Progress<*> -> core.initializerProgressPage(page, data, null)
            else -> core.initializerSuccessPage(page, data, null)
        }
    }

/**
 * A full-featured, mutable pagination manager for Kotlin/Android.
 *
 * Extends [Paginator] with element-level CRUD operations
 * and lifecycle control ([release]).
 *
 * Cache management (capacity, resize, state access) is handled by [PagingCore]
 * accessible via [core].
 *
 * Use [Paginator] when you only need read-only access and navigation.
 *
 * **Data mutability contract:** element-level operations ([setElement], [removeElement],
 * [addAllElements]) cast [PageState.data] to [MutableList] directly. This is safe as long as
 * `data` is always constructed as a `MutableList` (which is guaranteed by [Paginator]'s
 * internal [loadOrGetPageState] via `.toMutableList()`).
 * If you call [core].setState directly with a [OffsetPageState] whose `data` was created via `listOf()`
 * or another immutable factory, subsequent element-level mutations will throw
 * [UnsupportedOperationException]. Always use `mutableListOf()` or `.toMutableList()` when
 * constructing [OffsetPageState] instances passed to [MutablePaginator].
 *
 * @param T The type of elements contained in each page.
 * @param load A suspending lambda that loads data for a given page number.
 *   The receiver is the paginator itself, giving access to its properties during loading.
 *
 * @see Paginator
 * @see PagingCore
 * @see PageState
 */
open class MutablePaginator<T>(
    core: PagingCore<T> = PagingCore(),
    load: suspend Paginator<T>.(page: Int) -> LoadResult<T>
) : Paginator<T>(core, load) {

    /**
     * Pages modified by CRUD operations that have not yet been flushed to L2.
     *
     * Populated automatically by [setElement], [removeElement], [addAllElements],
     * [removeState], and [plusAssign]. Flushed by [flush] or
     * automatically when [transaction] completes successfully.
     *
     * Thread-safe via [AtomicRef] with copy-on-write semantics.
     */
    private val _affectedPages: AtomicRef<Set<Int>> = atomic(emptySet())

    /**
     * Read-only snapshot of pages modified by CRUD operations that have not yet been
     * flushed to the [persistent cache][PagingCore.persistentCache] (L2).
     *
     * Useful for diagnostics, tests, and UI "unsaved changes" indicators. The returned
     * set is a copy — mutating it has no effect on the paginator.
     *
     * Note: the snapshot reflects the state at the moment of the read. Concurrent CRUD
     * or [flush] calls may change the underlying set immediately after.
     */
    val affectedPages: Set<Int>
        get() = _affectedPages.value

    /**
     * `true` when there are CRUD changes tracked for the next [flush] call.
     *
     * Equivalent to `affectedPages.isNotEmpty()` but avoids materialising the set.
     * Always `false` when [PagingCore.persistentCache] is `null`, since unpersisted
     * changes are meaningless without an L2 backend.
     */
    val hasPendingFlush: Boolean
        get() = core.persistentCache != null && _affectedPages.value.isNotEmpty()

    private fun markAffected(page: Int) {
        while (true) {
            val current = _affectedPages.value
            if (_affectedPages.compareAndSet(current, current + page)) return
        }
    }

    private fun markAffectedAll(pages: Set<Int>) {
        if (pages.isEmpty()) return
        while (true) {
            val current = _affectedPages.value
            if (_affectedPages.compareAndSet(current, current + pages)) return
        }
    }

    private fun markAffectedAll(pages: IntRange) {
        markAffectedAll(pages.toSet())
    }

    private fun drainAffectedPages(): Set<Int> {
        return _affectedPages.getAndSet(emptySet())
    }

    /**
     * Removes the state of the specified page from the cache and re-labels the pages above it.
     *
     * Removing a page means one page worth of elements disappeared from the feed, so on the
     * server every page after [pageToRemove] shifted down by one. This function mirrors that:
     * the page is dropped and **every** cached page above it is re-labelled to `page - 1`,
     * keeping the local page numbers aligned with the server's.
     *
     * The shift crosses gaps. With pages `1,2` and `5,6` cached, removing page `2` yields
     * `1` and `4,5` — the far island moves down as a whole, into page numbers that were never
     * loaded. That is deliberate: the *data* is real and already cached, only its label changes.
     * Leaving the island at `5,6` would desynchronise it from the server and produce duplicated
     * elements as soon as the (now shifted) neighbouring page is loaded.
     *
     * The context window follows the same shift, so the pages the UI is looking at stay the
     * pages it is looking at. If the window collapsed onto the removed page and nothing slid
     * into its slot, the window is re-anchored via [PagingCore.findNearContextPage].
     *
     * Removal is skipped entirely while the cache is not started ([PagingCache.isStarted]):
     * without a context window there is nothing to keep aligned, and the cache is typically
     * being seeded manually at that point.
     *
     * @param pageToRemove The page number whose state should be removed.
     * @param silently If true, removal will not trigger a snapshot update.
     * @return The removed page state ([OffsetPageState<T>]), or null if the page was not found.
     */
    fun removeState(
        pageToRemove: Int,
        silently: Boolean = false,
    ): OffsetPageState<T>? {
        logger.debug(LogComponent.MUTATION) { "removeState: page=$pageToRemove" }

        val pagesBefore = cache.pages.filter { it >= pageToRemove }.toSet()

        val removedPageState: OffsetPageState<T>?
        if (!cache.isStarted) {
            removedPageState = cache.removeFromCache(pageToRemove)
        } else {
            removedPageState = cache.getStateOf(pageToRemove) ?: return null
            shiftPagesDown(removedPage = pageToRemove)
            recalculateContext(removedPage = pageToRemove)
        }
        markAffectedAll(pagesBefore)

        if (!silently && removedPageState != null) {
            core.snapshot()
        }
        return removedPageState
    }

    /**
     * Drops [removedPage] and re-labels every cached page above it to `page - 1`.
     *
     * Pages are walked in ascending order, so the slot each state moves into has already been
     * vacated by the previous iteration (or by the removal itself) — no state is ever
     * overwritten. Gaps are crossed rather than treated as boundaries; see [removeState].
     */
    private fun shiftPagesDown(removedPage: Int) {
        cache.removeFromCache(removedPage)
        // `cache.pages` is sorted ascending; `filter` snapshots it before the loop mutates it.
        val pagesAbove: List<Int> = cache.pages.filter { it > removedPage }
        for (page: Int in pagesAbove) {
            val state: OffsetPageState<T> = cache.removeFromCache(page) ?: continue
            cache.setState(state = state.copy(page = page - 1), silently = true)
        }
    }

    /** Slides the context window along with the pages that [shiftPagesDown] re-labelled. */
    private fun recalculateContext(removedPage: Int) {
        val start: Int = cache.startContextPage
        val end: Int = cache.endContextPage

        if (removedPage > end) return // the window sits below the removal — nothing moved

        if (removedPage < start) { // the whole window slid down with its pages
            core.startContextPage = start - 1
            core.endContextPage = end - 1
            return
        }

        // The removed page was inside the window.
        if (end > start) {
            // Pages above it slid in, so the window loses exactly one page from the right.
            core.endContextPage = end - 1
            return
        }

        // The window was exactly the removed page. If a page slid into its slot the window
        // still points at real data; otherwise it has to be re-anchored.
        if (removedPage in cache.pages) return
        if (removedPage == 1) core.findNearContextPage()
        else core.findNearContextPage(removedPage - 1, removedPage + 1)
    }

    /**
     * Replaces an element at a specific position within a cached page.
     *
     * **L2 note:** this operation only modifies L1. Call [flush] afterward
     * to flush the change to the persistent cache, or use [transaction] which flushes
     * automatically on success.
     *
     * @param element The new element to place at the given position.
     * @param page The page number containing the element.
     * @param index The zero-based index of the element to replace within the page's data list.
     * @param silently If `true`, the change will **not** trigger a snapshot emission.
     * @param isDirty If `true`, marks the page as dirty.
     * @throws NoSuchElementException If [page] is not found in the cache.
     * @throws IndexOutOfBoundsException If [index] is out of range for the page's data.
     */
    fun setElement(
        element: T,
        page: Int,
        index: Int,
        silently: Boolean = false,
        isDirty: Boolean = false
    ) {
        logger.debug(LogComponent.MUTATION) { "setElement: page=$page index=$index isDirty=$isDirty" }
        val pageState = cache.getStateOf(page)
            ?: throw NoSuchElementException("page-$page was not found in cache")
        cache.setState(
            state = pageState.copy(
                data = pageState.data
                    .let { it as MutableList }
                    .also { it[index] = element }
            ),
            silently = true
        )

        markAffected(page)

        if (isDirty) core.markDirty(page)

        if (!silently) snapshotIfPageVisible(page)
    }

    /**
     * Removes an element at a specific position within a cached page and rebalances if needed.
     *
     * When removing an element causes the page to have fewer items than capacity, elements
     * are pulled from the **next** page (if it exists and is the same type) to fill the gap.
     *
     * **L2 note:** this operation only modifies L1. Call [flush] afterward
     * to flush the change to the persistent cache, or use [transaction] which flushes
     * automatically on success.
     *
     * @param page The page number containing the element.
     * @param index The zero-based index of the element to remove within the page's data list.
     * @param silently If `true`, the change will **not** trigger a snapshot emission.
     * @param isDirty If `true`, marks the page as dirty.
     * @return The removed element.
     * @throws IllegalArgumentException If [page] is not found in the cache.
     * @throws IndexOutOfBoundsException If [index] is out of range for the page's data.
     */
    fun removeElement(
        page: Int,
        index: Int,
        silently: Boolean = false,
        isDirty: Boolean = false,
    ): T {
        logger.debug(LogComponent.MUTATION) { "removeElement: page=$page index=$index isDirty=$isDirty" }
        val pageState: OffsetPageState<T> = requireNotNull(
            value = cache.getStateOf(page)
        ) { "page-$page was not created" }
        markAffected(page)
        val removed: T

        // Snapshot the context window before mutating: emptying the page may drop it and
        // re-anchor the window (via removeState), which we must detect to emit correctly.
        val startBefore: Int = cache.startContextPage
        val endBefore: Int = cache.endContextPage

        val updatedData = pageState.data
            .let { it as MutableList }
            .also { removed = it.removeAt(index) }

        if (updatedData.size < core.capacity && !core.isCapacityUnlimited) {
            val nextPageState = cache.getStateOf(page + 1)
            if (nextPageState != null
                &&
                nextPageState::class == pageState::class
            ) {
                while (updatedData.size < core.capacity
                    &&
                    nextPageState.data.isNotEmpty()
                ) {
                    updatedData.add(
                        removeElement(
                            page = page + 1,
                            index = 0,
                            silently = true
                        )
                    )
                }
            }
        }

        if (updatedData.isEmpty()) {
            removeState(
                pageToRemove = page,
                silently = true
            )
        } else {
            cache.setState(
                state = pageState.copy(data = updatedData),
                silently = true
            )
        }

        if (isDirty) core.markDirty(page)

        if (!silently) {
            if (cache.startContextPage != startBefore || cache.endContextPage != endBefore) {
                // The removal moved/shrank the context window — e.g. the currently viewed
                // page was emptied and dropped, re-anchoring to the nearest filled group.
                // Emit the whole new window: snapshotIfPageVisible(page) would suppress the
                // emission because the old page is no longer inside the visible range, leaving
                // the UI stuck on a stale/blank window while live data sits in the cache.
                core.snapshot()
            } else {
                snapshotIfPageVisible(page)
            }
        }

        return removed
    }

    /**
     * Inserts elements at a specific position within a cached page, with overflow cascading.
     *
     * If inserting the elements causes the page to exceed capacity, the excess elements
     * are cascaded to the **next** page (recursively).
     *
     * **L2 note:** this operation only modifies L1. Call [flush] afterward
     * to flush the change to the persistent cache, or use [transaction] which flushes
     * automatically on success.
     *
     * @param elements The elements to insert.
     * @param targetPage The page number to insert into.
     * @param index The zero-based position within the page's data list where elements are inserted.
     * @param silently If `true`, the change will **not** trigger a snapshot emission.
     * @param isDirty If `true`, marks the page as dirty.
     * @param initPageState Factory for pages created during overflow. **Defaults** to a factory that
     *   creates a new page of the **same class** as the preceding (source) page via the configured
     *   `core.initializer*Page` factories (custom subclasses and fresh ids preserved), so inserted
     *   elements are not silently dropped. Pass a custom factory to override page creation, or pass
     *   `null` to **opt out** of overflow page creation (overflow is then dropped / a missing target
     *   throws — the pre-overflow-algorithm behavior).
     * @throws IndexOutOfBoundsException If [targetPage] is not in the cache and [initPageState] is `null`.
     */
    fun addAllElements(
        elements: List<T>,
        targetPage: Int,
        index: Int,
        silently: Boolean = false,
        isDirty: Boolean = false,
        initPageState: ((page: Int, data: List<T>) -> OffsetPageState<T>)? = defaultOverflowPageFactory()
    ) {
        logger.debug(LogComponent.MUTATION) {
            "addAllElements: targetPage=$targetPage index=$index count=${elements.size} isDirty=$isDirty"
        }
        markAffected(targetPage)
        val targetState: OffsetPageState<T> =
            (cache.getStateOf(targetPage)
                ?: initPageState?.invoke(targetPage, mutableListOf())
                    ?.also { cache.setState(state = it, silently = true) })
                ?: throw IndexOutOfBoundsException(
                    "page-$targetPage was not created"
                )

        val dataOfTargetState: MutableList<T> = requireNotNull(
            value = targetState.data as? MutableList
        ) { "data of target page state is not mutable" }
        dataOfTargetState.addAll(index, elements)
        val extraElements: MutableList<T>? =
            if (dataOfTargetState.size > core.capacity && !core.isCapacityUnlimited) {
                MutableList(size = dataOfTargetState.size - core.capacity) {
                    dataOfTargetState.removeAt(dataOfTargetState.lastIndex)
                }.apply(MutableList<T>::reverse)
            } else {
                null
            }

        if (dataOfTargetState is ArrayList) {
            dataOfTargetState.trimToSize()
        }

        if (!extraElements.isNullOrEmpty()) {
            val nextPageState: OffsetPageState<T>? = cache.getStateOf(targetPage + 1)
            if (nextPageState != null && nextPageState::class == targetState::class) {
                addAllElements(
                    elements = extraElements,
                    targetPage = targetPage + 1,
                    index = 0,
                    silently = true,
                    initPageState = initPageState
                )
            } else {
                // Cascade blocked at targetPage+1 (a gap, the true end, or a foreign-class page).
                // Two independent things happen here:
                //   (1) Rebalance: the insert shifts every later position forward by
                //       N = extraElements.size. If a cached chunk exists beyond the gap, peel N
                //       items off its first page and cascade them through it (its first page becomes
                //       partial, to be refilled later) so it stays consistent with that shift.
                //   (2) Preserve the overflow: if targetPage+1 is empty (gap or true end) and a
                //       factory is available (initPageState, default = same-class), create a new
                //       page to hold extraElements instead of dropping them — covers "addElement
                //       appends to a full last page" and "fill toward the gap". A null initPageState
                //       opts out (overflow dropped).
                // nextChunkStart is captured BEFORE (2) so the peel targets the far chunk, not a
                // page that (2) may have just created.
                //
                // Example: `1=[a,b,c] 2=[d,e,f] 3=[g,h,i] gap 10=[p,q,r] 11=[s,t,u]`,
                // insert ["X","Y"] at page 1 ->
                //   1=[X,Y,a] 2=[b,c,d] 3=[e,f,g] 4=[h,i] gap 10=[p] 11=[q,r,s] 12=[t,u]
                //   (page 4 created with the overflow — (2); far chunk rebalanced by N=2 — (1)).
                val shiftSize = extraElements.size
                val nextChunkStart: Int? = cache.pages
                    .asSequence()
                    .filter { it > targetPage + 1 }
                    .firstOrNull { p ->
                        cache.getStateOf(p)?.let { it::class == targetState::class } == true
                    }

                if (nextChunkStart != null) {
                    val nextChunkState: OffsetPageState<T> =
                        checkNotNull(cache.getStateOf(nextChunkStart))
                    val nextChunkData: MutableList<T> = requireNotNull(
                        value = nextChunkState.data as? MutableList
                    ) { "data of next-chunk page state is not mutable" }

                    val peel = minOf(shiftSize, nextChunkData.size)
                    if (peel > 0) {
                        val displaced = MutableList(peel) {
                            nextChunkData.removeAt(nextChunkData.lastIndex)
                        }.apply(MutableList<T>::reverse)

                        markAffected(nextChunkStart)
                        if (nextChunkData.isEmpty()) {
                            // The shift emptied the first page of the chunk entirely —
                            // drop it from the cache so we don't keep a zero-size state
                            // around (a large batch can legitimately wipe a whole page).
                            cache.removeFromCache(nextChunkStart)
                        } else {
                            if (nextChunkData is ArrayList) nextChunkData.trimToSize()
                            cache.setState(
                                state = nextChunkState.copy(data = nextChunkData),
                                silently = true,
                            )
                        }

                        addAllElements(
                            elements = displaced,
                            targetPage = nextChunkStart + 1,
                            index = 0,
                            silently = true,
                            initPageState = { pageNum, pageData ->
                                // Synthesize a page of the same class as targetState.
                                // addAllElements's prologue registers it in the cache and
                                // then mutates its data list in place.
                                targetState.copy(page = pageNum, data = pageData.toMutableList())
                            },
                        )
                    }
                }

                // (2) Preserve the front overflow: when targetPage+1 is empty (gap or true end) and
                // a factory is available (the default same-class factory, or a custom one), create a
                // new page to hold it instead of dropping. Passing initPageState = null opts out, so
                // the overflow is dropped (the pre-overflow-algorithm behavior).
                // This runs ALONGSIDE the rebalance above, so a cross-gap insert both fills toward
                // the gap and keeps the far chunk consistent.
                if (nextPageState == null && initPageState != null) {
                    addAllElements(
                        elements = extraElements,
                        targetPage = targetPage + 1,
                        index = 0,
                        silently = true,
                        initPageState = initPageState,
                    )
                }
                // (If a foreign-class page occupies targetPage+1 and there is no far chunk to
                //  rebalance, the overflow has no valid home and is dropped — unchanged edge case.)
            }
        }

        if (isDirty) core.markDirty(targetPage)

        if (!silently) snapshotAfterMutation(targetPage)
    }

    /**
     * Iterates over **all** elements across all cached pages and conditionally replaces or removes them.
     *
     * For each element where [predicate] returns `true`, the [providerElement] factory is called:
     * - If it returns a **non-null** value, the element is replaced via [setElement].
     * - If it returns `null`, the element is **removed** via [removeElement].
     *
     * All modifications are performed silently (no individual snapshots), and a single
     * snapshot is emitted at the end (unless [silently] is `true`).
     *
     * @param providerElement Factory that produces the replacement element.
     * @param silently If `true`, no snapshot is emitted after the operation completes.
     * @param predicate Determines whether an element should be processed.
     */
    inline fun replaceAllElements(
        providerElement: (current: T, pageState: OffsetPageState<T>, index: Int) -> T?,
        silently: Boolean = false,
        predicate: (current: T, pageState: OffsetPageState<T>, index: Int) -> Boolean
    ) {
        smartForEach { _, _, pageState ->
            var index = 0
            while (index < pageState.data.size) {
                val current = pageState.data[index]
                if (predicate(current, pageState, index)) {
                    val newElement = providerElement(current, pageState, index)
                    if (newElement != null) {
                        setElement(
                            element = newElement,
                            page = pageState.page,
                            index = index,
                            silently = true
                        )
                        index++
                    } else {
                        removeElement(
                            page = pageState.page,
                            index = index,
                            silently = true
                        )
                        // Don't increment index: elements shifted left after removal
                    }
                } else {
                    index++
                }
            }
            return@smartForEach true
        }
        if (!silently) {
            core.snapshot()
        }
    }

    /**
     * Emits a snapshot if [affectedPage] falls within the current visible range.
     * Extracts the repeated pattern used across CRUD operations.
     */
    private fun snapshotIfPageVisible(affectedPage: Int) {
        val startState = walkBackwardWhile(core[cache.startContextPage]) ?: return
        val endState = walkForwardWhile(core[cache.endContextPage]) ?: return
        val rangeSnapshot = startState.page..endState.page
        if (affectedPage in rangeSnapshot) {
            core.snapshot(rangeSnapshot)
        }
    }

    /**
     * Emits a snapshot after a mutation that may have removed pages directly from the cache
     * (without going through [removeState]'s context re-anchoring).
     *
     * If the context window now points at a non-filled or missing pivot, the window is
     * re-anchored to the nearest filled-success group and the whole new window is emitted.
     * Otherwise this behaves like [snapshotIfPageVisible], emitting only when [affectedPage]
     * is currently visible.
     */
    private fun snapshotAfterMutation(affectedPage: Int) {
        if (cache.isStarted
            && (!core.isFilledSuccessState(cache.getStateOf(cache.startContextPage))
                    || !core.isFilledSuccessState(cache.getStateOf(cache.endContextPage)))
        ) {
            core.findNearContextPage(
                startPoint = cache.startContextPage,
                endPoint = cache.endContextPage
            )
            core.snapshot()
        } else {
            snapshotIfPageVisible(affectedPage)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Persistent cache (L2) flush
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Flushes CRUD changes to the [persistent cache][PagingCore.persistentCache] (L2).
     *
     * Pages still present in L1 as [OffsetPageState.Success] are saved; pages that no longer
     * exist in L1 are removed from L2. Transient states ([OffsetPageState.Error],
     * [OffsetPageState.Progress]) are skipped — their existing L2 entry (if any) is
     * preserved.
     *
     * This method is called **automatically** when [transaction] completes
     * successfully. Outside a transaction, call it explicitly after CRUD
     * operations to persist changes.
     *
     * No-op when [PagingCore.persistentCache] is `null` or when no CRUD
     * operations have been performed since the last flush.
     */
    suspend fun flush() {
        val pagingCache: PersistentPagingCache<T> = core.persistentCache ?: return
        val pagesToFlush: Set<Int> = drainAffectedPages()
        if (pagesToFlush.isEmpty()) return

        val toSave = mutableListOf<OffsetPageState<T>>()
        val toRemove = mutableListOf<Int>()

        for (page in pagesToFlush) {
            val state = cache.getStateOf(page)
            if (state.isSuccessState()) {
                toSave.add(state)
            } else if (state == null) {
                toRemove.add(page)
            }
        }

        pagingCache.transaction {
            if (toSave.isNotEmpty()) saveAll(toSave)
            if (toRemove.isNotEmpty()) removeAll(toRemove)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Transaction override (auto-persist on success)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Executes [block] atomically and auto-persists CRUD changes to L2 on success.
     *
     * On failure the L1 rollback is performed by the parent class; any CRUD
     * tracking accumulated inside the block is discarded so L2 remains
     * unchanged. Pre-transaction pending pages are restored for the caller to
     * flush later.
     */
    override suspend fun <R> transaction(block: suspend Paginator<T>.() -> R): R {
        val savedAffectedPages = drainAffectedPages()
        try {
            val result = super.transaction(block)
            flush()
            markAffectedAll(savedAffectedPages)
            return result
        } catch (e: Throwable) {
            _affectedPages.value = savedAffectedPages
            throw e
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Operators
    // ──────────────────────────────────────────────────────────────────────────

    operator fun minusAssign(page: Int) {
        removeState(page)
    }

    operator fun minusAssign(pageState: OffsetPageState<T>) {
        removeState(pageState.page)
    }

    /**
     * **L2 note:** this operation only modifies L1. Call [flush] afterward
     * to flush the change to the persistent cache, or use [transaction] which flushes
     * automatically on success.
     */
    operator fun plusAssign(pageState: OffsetPageState<T>) {
        core.setState(pageState)
        markAffected(pageState.page)
    }

    override fun toString(): String = "MutablePaginator(cache=$cache, bookmarks=$bookmarks)"
}
