package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.PagingCore.Companion.DEFAULT_CAPACITY
import com.jamal_aliev.paginator.extension.far
import com.jamal_aliev.paginator.extension.smartForEach
import com.jamal_aliev.paginator.extension.walkBackwardWhile
import com.jamal_aliev.paginator.extension.walkForwardWhile
import com.jamal_aliev.paginator.page.PageState

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
 * If you call [core].setState directly with a [PageState] whose `data` was created via `listOf()`
 * or another immutable factory, subsequent element-level mutations will throw
 * [UnsupportedOperationException]. Always use `mutableListOf()` or `.toMutableList()` when
 * constructing [PageState] instances passed to [MutablePaginator].
 *
 * @param T The type of elements contained in each page.
 * @param source A suspending lambda that loads data for a given page number.
 *   The receiver is the paginator itself, giving access to its properties during loading.
 *
 * @see Paginator
 * @see PagingCore
 * @see PageState
 */
open class MutablePaginator<T>(
    core: PagingCore<T> = PagingCore<T>(DEFAULT_CAPACITY),
    source: suspend Paginator<T>.(page: Int) -> List<T>
) : Paginator<T>(core, source) {

    /**
     * Removes the state of the specified page from the cache and adjusts surrounding pages and context.
     *
     * This function handles both simple removals and complex cases where pages are non-contiguous:
     * - Finds the state of the page [pageToRemove] in the cache.
     * - If the page exists, removes it and, if necessary, collapses consecutive pages to maintain
     *   correct page numbering.
     * - Detects gaps in the page sequence and ensures context boundaries are updated correctly.
     * - Handles edge cases such as removing the first page, last page, or pages in the middle of a gap.
     * - If [silently] is false, takes a snapshot of the current paginator state via [core].snapshot().
     *
     * @param pageToRemove The page number whose state should be removed.
     * @param silently If true, removal will not trigger a snapshot update.
     * @return The removed page state ([PageState<T>]), or null if the page was not found.
     */
    fun removeState(
        pageToRemove: Int,
        silently: Boolean = false,
    ): PageState<T>? {
        logger?.log(TAG, "removeState: page=$pageToRemove")

        fun collapse(startPage: Int, compression: Int) {
            var currentState: PageState<T> = checkNotNull(
                value = core.removeFromCache(startPage)
            ) { "it's impossible to start collapse from this page" }
            var remaining: Int = compression
            while (remaining > 0) {
                val collapsedState: PageState<T> = currentState.copy(page = currentState.page - 1)
                val pageState: PageState<T> = core.getStateOf(currentState.page - 1) ?: break
                core.setState(state = collapsedState, silently = true)
                currentState = pageState
                remaining--
            }
        }

        fun recalculateContext(removedPage: Int) {
            // Using explicit comparison for performance: avoid creating a IntRange object
            if (core.startContextPage <= removedPage && removedPage <= core.endContextPage) {
                if (core.endContextPage - core.startContextPage > 0) {
                    // Just shrink the context by one page
                    core.endContextPage--
                } else if (removedPage == 1) {
                    // If the first page was removed, find the nearest page
                    core.findNearContextPage()
                } else {
                    // Otherwise, find the nearest pages around the removed page
                    core.findNearContextPage(removedPage - 1, removedPage + 1)
                }
            }
        }

        var pageStateWillRemove: PageState<T>?
        if (!core.isStarted) {
            pageStateWillRemove = core.removeFromCache(pageToRemove)
        } else {
            pageStateWillRemove = core.getStateOf(pageToRemove) ?: return null
            var indexOfPageWillRemove = -1
            var indexOfStartContext = -1
            var haveRemoved = false
            var previousPageState: PageState<T>? = null
            smartForEach(
                initialIndex = { states: List<PageState<T>> ->
                    indexOfPageWillRemove =
                        states.binarySearch { state: PageState<T> ->
                            state.compareTo(pageStateWillRemove)
                        }
                    indexOfStartContext = indexOfPageWillRemove
                    return@smartForEach indexOfPageWillRemove
                }
            ) { states: List<PageState<T>>, index: Int, currentState: PageState<T> ->
                previousPageState = previousPageState ?: currentState
                if (previousPageState far currentState) {
                    // pages example: 1,2,3 gap 11,12,13
                    if (!haveRemoved) {
                        if (index - 1 == indexOfPageWillRemove) {
                            core.removeFromCache(pageStateWillRemove.page)
                            recalculateContext(pageStateWillRemove.page)
                        } else {
                            collapse(previousPageState.page, index - 1 - indexOfPageWillRemove)
                            recalculateContext(previousPageState.page)
                        }
                        if (index == states.lastIndex) {
                            core.removeFromCache(currentState.page)
                            recalculateContext(currentState.page)
                        }
                        haveRemoved = true
                    } else {
                        collapse(previousPageState.page, index - 1 - indexOfStartContext)
                        recalculateContext(previousPageState.page)
                    }
                    indexOfStartContext = index
                } else if (index == states.lastIndex) {
                    if (!haveRemoved) {
                        if (index == indexOfPageWillRemove) {
                            core.removeFromCache(pageStateWillRemove.page)
                            recalculateContext(pageStateWillRemove.page)
                        } else {
                            collapse(currentState.page, index - indexOfPageWillRemove)
                            recalculateContext(currentState.page)
                        }
                        haveRemoved = true
                    } else {
                        collapse(currentState.page, index - indexOfStartContext)
                        recalculateContext(currentState.page)
                    }
                }
                previousPageState = currentState
                return@smartForEach true
            }
        }
        if (!silently && pageStateWillRemove != null) {
            core.snapshot()
        }
        return pageStateWillRemove
    }

    /**
     * Replaces an element at a specific position within a cached page.
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
        logger?.log(TAG, "setElement: page=$page index=$index isDirty=$isDirty")
        val pageState = core.getStateOf(page)
            ?: throw NoSuchElementException("page-$page was not found in cache")
        core.setState(
            state = pageState.copy(
                data = pageState.data
                    .let { it as MutableList }
                    .also { it[index] = element }
            ),
            silently = true
        )

        if (isDirty) core.markDirty(page)

        if (!silently) {
            val pageBefore = walkBackwardWhile(core[core.startContextPage])!!
            val pageAfter = walkForwardWhile(core[core.endContextPage])!!
            val rangeSnapshot = pageBefore.page..pageAfter.page
            if (page in rangeSnapshot) {
                core.snapshot(rangeSnapshot)
            }
        }
    }

    /**
     * Removes an element at a specific position within a cached page and rebalances if needed.
     *
     * When removing an element causes the page to have fewer items than capacity, elements
     * are pulled from the **next** page (if it exists and is the same type) to fill the gap.
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
        logger?.log(TAG, "removeElement: page=$page index=$index isDirty=$isDirty")
        val pageState: PageState<T> = requireNotNull(
            value = core.getStateOf(page)
        ) { "page-$page was not created" }
        val removed: T

        val updatedData = pageState.data
            .let { it as MutableList }
            .also { removed = it.removeAt(index) }

        if (updatedData.size < core.capacity && !core.isCapacityUnlimited) {
            val nextPageState = core.getStateOf(page + 1)
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
            core.setState(
                state = pageState.copy(data = updatedData),
                silently = true
            )
        }

        if (isDirty) core.markDirty(page)

        if (!silently) {
            val pageBefore = walkBackwardWhile(core[core.startContextPage])!!
            val pageAfter = walkForwardWhile(core[core.endContextPage])!!
            val rangeSnapshot = pageBefore.page..pageAfter.page
            if (page in rangeSnapshot) {
                core.snapshot(rangeSnapshot)
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
     * @param elements The elements to insert.
     * @param targetPage The page number to insert into.
     * @param index The zero-based position within the page's data list where elements are inserted.
     * @param silently If `true`, the change will **not** trigger a snapshot emission.
     * @param isDirty If `true`, marks the page as dirty.
     * @param initPageState Optional factory to create a new [PageState] for overflow pages.
     * @throws IndexOutOfBoundsException If [targetPage] is not in the cache and [initPageState] is `null`.
     */
    fun addAllElements(
        elements: List<T>,
        targetPage: Int,
        index: Int,
        silently: Boolean = false,
        isDirty: Boolean = false,
        initPageState: ((page: Int, data: List<T>) -> PageState<T>)? = null
    ) {
        logger?.log(
            TAG,
            "addAllElements: targetPage=$targetPage index=$index count=${elements.size} isDirty=$isDirty"
        )
        val targetState: PageState<T> =
            (core.getStateOf(targetPage) ?: initPageState?.invoke(targetPage, mutableListOf()))
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
            val nextPageState: PageState<T>? = core.getStateOf(targetPage + 1)
            if ((nextPageState != null && nextPageState::class == targetState::class)
                ||
                (nextPageState == null && initPageState != null)
            ) {
                addAllElements(
                    elements = extraElements,
                    targetPage = targetPage + 1,
                    index = 0,
                    silently = true,
                    initPageState = initPageState
                )
            } else {
                val lastPage = core.lastPage() ?: return
                val rangePageInvalidated: IntRange = (targetPage + 1)..lastPage
                rangePageInvalidated.forEach { core.removeFromCache(it) }
            }
        }

        if (isDirty) core.markDirty(targetPage)

        if (!silently) {
            val startState: PageState<T> = checkNotNull(
                walkBackwardWhile(core.getStateOf(core.startContextPage))
            ) { "startContextPage is broken so snapshot is impossible" }
            val endState: PageState<T> = checkNotNull(
                walkForwardWhile(core.getStateOf(core.endContextPage))
            ) { "endContextPage is broken so snapshot is impossible" }
            val rangeSnapshot: IntRange = startState.page..endState.page
            if (targetPage in rangeSnapshot) {
                core.snapshot(rangeSnapshot)
            }
        }
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
    inline fun replaceAllElement(
        providerElement: (current: T, pageState: PageState<T>, index: Int) -> T?,
        silently: Boolean = false,
        predicate: (current: T, pageState: PageState<T>, index: Int) -> Boolean
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


    operator fun minusAssign(page: Int) {
        removeState(page)
    }

    operator fun minusAssign(pageState: PageState<T>) {
        removeState(pageState.page)
    }

    operator fun plusAssign(pageState: PageState<T>): Unit = core.setState(pageState)

    override fun toString(): String = "MutablePaginator(cache=$core, bookmarks=$bookmarks)"
}
