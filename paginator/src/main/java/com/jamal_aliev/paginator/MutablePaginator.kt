package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.extension.far
import com.jamal_aliev.paginator.extension.isSuccessState
import com.jamal_aliev.paginator.extension.smartForEach
import com.jamal_aliev.paginator.extension.walkBackwardWhile
import com.jamal_aliev.paginator.extension.walkForwardWhile
import com.jamal_aliev.paginator.initializer.InitializerSuccessPage
import com.jamal_aliev.paginator.page.PageState

/**
 * A full-featured, mutable pagination manager for Kotlin/Android.
 *
 * Extends [Paginator] with element-level CRUD operations,
 * capacity management ([resize]), and lifecycle control ([release]).
 *
 * Use [Paginator] when you only need read-only access and navigation.
 *
 * @param T The type of elements contained in each page.
 * @param source A suspending lambda that loads data for a given page number.
 *   The receiver is the paginator itself, giving access to its properties during loading.
 *
 * @see Paginator
 * @see PageState
 */
open class MutablePaginator<T>(
    source: suspend Paginator<T>.(page: UInt) -> List<T>
) : Paginator<T>(source) {

    /**
     * Stores a [PageState] in the cache, replacing any existing state for that page number.
     *
     * This is the public version of the protected [Paginator.setState].
     *
     * @param state The page state to store.
     * @param silently If `true`, the change will **not** trigger a [snapshot] emission.
     */
    public override fun setState(
        state: PageState<T>,
        silently: Boolean
    ) {
        super.setState(state, silently)
    }

    /**
     * Removes the state of the specified page from the cache and adjusts surrounding pages and context.
     *
     * This function handles both simple removals and complex cases where pages are non-contiguous:
     * - Finds the state of the page [pageToRemove] in the cache.
     * - If the page exists, removes it and, if necessary, collapses consecutive pages to maintain
     *   correct page numbering.
     * - Detects gaps in the page sequence and ensures context boundaries ([startContextPage] and [endContextPage])
     *   are updated correctly.
     * - Handles edge cases such as removing the first page, last page, or pages in the middle of a gap.
     * - If [silently] is false, takes a snapshot of the current paginator state via [snapshot].
     *
     * The function works in both started and non-started states of the paginator:
     * - In a non-started state, the page is simply removed from the cache.
     * - In a started state, it iterates over all page states using [smartForEach], collapsing pages and
     *   recalculating context boundaries as needed.
     *
     * @param pageToRemove The page number whose state should be removed.
     * @param silently If true, removal will not trigger a snapshot update.
     * @return The removed page state ([PageState<T>]), or null if the page was not found.
     *
     * see inner fun collapse
     * see inner fun recalculateContext
     */
    fun removeState(
        pageToRemove: UInt,
        silently: Boolean = false,
    ): PageState<T>? {
        logger.log(TAG, "removeState: page=$pageToRemove")

        fun collapse(startPage: UInt, compression: Int) {
            var currentState: PageState<T> = checkNotNull(
                value = cache.remove(startPage)
            ) { "it's impossible to start collapse from this page" }
            var remaining: Int = compression
            while (remaining > 0) {
                val collapsedState: PageState<T> = currentState.copy(page = currentState.page - 1u)
                val pageState: PageState<T> = getStateOf(currentState.page - 1u) ?: break
                setState(state = collapsedState, silently = true)
                currentState = pageState
                remaining--
            }
        }

        fun recalculateContext(removedPage: UInt) {
            // Using explicit comparison for performance: avoid creating a UIntRange object
            if (startContextPage <= removedPage && removedPage <= endContextPage) {
                if (endContextPage - startContextPage > 0u) {
                    // Just shrink the context by one page
                    endContextPage--
                } else if (removedPage == 1u) {
                    // If the first page was removed, find the nearest page
                    findNearContextPage()
                } else {
                    // Otherwise, find the nearest pages around the removed page
                    findNearContextPage(removedPage - 1u, removedPage + 1u)
                }
            }
        }

        var pageStateWillRemove: PageState<T>?
        if (!isStarted) {
            pageStateWillRemove = cache.remove(pageToRemove)
        } else {
            pageStateWillRemove = getStateOf(pageToRemove) ?: return null
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
                    // A contextual gap is detected, we need to handle it
                    if (!haveRemoved) {
                        if (index - 1 == indexOfPageWillRemove) {
                            // For example, Just remove the 3 page and recalculate the context
                            cache.remove(pageStateWillRemove.page)
                            recalculateContext(pageStateWillRemove.page)
                        } else {
                            // Remove page (2) by collapse method and recalculate the context
                            collapse(previousPageState.page, index - 1 - indexOfPageWillRemove)
                            recalculateContext(previousPageState.page)
                        }
                        if (index == states.lastIndex) {
                            // pages example: 1,2 gap 11
                            // We need to delete the 11 page because we removed 3
                            // And we need to recalculate the context
                            cache.remove(currentState.page)
                            recalculateContext(currentState.page)
                        }
                        haveRemoved = true
                    } else {
                        // The pageStateWillRemove have already removed
                        // And we need to collapse others page contexts
                        collapse(previousPageState.page, index - 1 - indexOfStartContext)
                        recalculateContext(previousPageState.page)
                    }
                    indexOfStartContext = index
                } else if (index == states.lastIndex) {
                    // pages example: 1,2,3 gap 11,12,13
                    // The final page context is founded, and we need to handle it
                    if (!haveRemoved) {
                        if (index == indexOfPageWillRemove) {
                            // For example, Just remove the 13 page and recalculate the context
                            cache.remove(pageStateWillRemove.page)
                            recalculateContext(pageStateWillRemove.page)
                        } else {
                            // Remove page (12) by collapse method and recalculate the context
                            collapse(currentState.page, index - indexOfPageWillRemove)
                            recalculateContext(currentState.page)
                        }
                        haveRemoved = true
                    } else {
                        // The pageStateWillRemove have already removed
                        // And we need to collapse the final page context
                        collapse(currentState.page, index - indexOfStartContext)
                        recalculateContext(currentState.page)
                    }
                }
                previousPageState = currentState
                return@smartForEach true
            }
        }
        if (!silently && pageStateWillRemove != null) {
            snapshot()
        }
        return pageStateWillRemove
    }

    /**
     * Replaces an element at a specific position within a cached page.
     *
     * Creates a new [PageState] with the updated data and stores it in the cache.
     * If [silently] is `false` and the page is within the current snapshot range,
     * a snapshot is emitted to notify the UI.
     *
     * @param element The new element to place at the given position.
     * @param page The page number containing the element.
     * @param index The zero-based index of the element to replace within the page's data list.
     * @param silently If `true`, the change will **not** trigger a [snapshot] emission.
     * @throws NoSuchElementException If [page] is not found in the cache.
     * @throws IndexOutOfBoundsException If [index] is out of range for the page's data.
     */
    fun setElement(
        element: T,
        page: UInt,
        index: Int,
        silently: Boolean = false,
        isDirty: Boolean = false
    ) {
        logger.log(TAG, "setElement: page=$page index=$index isDirty=$isDirty")
        val pageState = cache.getValue(page)
        setState(
            state = pageState.copy(
                data = pageState.data
                    .let { it as MutableList }
                    .also { it[index] = element }
            ),
            silently = true
        )

        if (isDirty) markDirty(page)

        if (!silently) {
            val pageBefore = walkBackwardWhile(cache[startContextPage])!!
            val pageAfter = walkForwardWhile(cache[endContextPage])!!
            val rangeSnapshot = pageBefore.page..pageAfter.page
            if (page in rangeSnapshot) {
                snapshot(rangeSnapshot)
            }
        }
    }

    /**
     * Removes an element at a specific position within a cached page and rebalances if needed.
     *
     * When removing an element causes the page to have fewer items than [capacity], elements
     * are pulled from the **next** page (if it exists and is the same type) to fill the gap.
     * This cascading rebalance continues until pages are full or no more adjacent pages exist.
     *
     * If the page becomes empty after removal, it is removed from the cache entirely
     * via [removeState].
     *
     * @param page The page number containing the element.
     * @param index The zero-based index of the element to remove within the page's data list.
     * @param silently If `true`, the change will **not** trigger a [snapshot] emission.
     * @return The removed element.
     * @throws IllegalArgumentException If [page] is not found in the cache.
     * @throws IndexOutOfBoundsException If [index] is out of range for the page's data.
     */
    fun removeElement(
        page: UInt,
        index: Int,
        silently: Boolean = false,
        isDirty: Boolean = false,
    ): T {
        logger.log(TAG, "removeElement: page=$page index=$index isDirty=$isDirty")
        val pageState: PageState<T> = requireNotNull(
            value = getStateOf(page)
        ) { "page-$page was not created" }
        val removed: T

        val updatedData = pageState.data
            .let { it as MutableList }
            .also { removed = it.removeAt(index) }

        if (updatedData.size < capacity && !isCapacityUnlimited) {
            val nextPageState = getStateOf(page + 1u)
            if (nextPageState != null
                &&
                nextPageState::class == pageState::class
            ) {
                while (updatedData.size < capacity
                    &&
                    nextPageState.data.isNotEmpty()
                ) {
                    updatedData.add(
                        removeElement(
                            page = page + 1u,
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
            setState(
                state = pageState.copy(data = updatedData),
                silently = true
            )
        }

        if (isDirty) markDirty(page)

        if (!silently) {
            val pageBefore = walkBackwardWhile(cache[startContextPage])!!
            val pageAfter = walkForwardWhile(cache[endContextPage])!!
            val rangeSnapshot = pageBefore.page..pageAfter.page
            if (page in rangeSnapshot) {
                snapshot(rangeSnapshot)
            }
        }

        return removed
    }

    /**
     * Inserts elements at a specific position within a cached page, with overflow cascading.
     *
     * If inserting the elements causes the page to exceed [capacity], the excess elements
     * are cascaded to the **next** page (recursively). This overflow only occurs when:
     * - The next page exists and is the same [PageState] subclass, **or**
     * - [initPageState] is provided (allowing creation of new pages for overflow).
     *
     * If overflow cannot be cascaded (no compatible next page and no [initPageState]),
     * all pages after [targetPage] are removed from the cache.
     *
     * @param elements The elements to insert.
     * @param targetPage The page number to insert into.
     * @param index The zero-based position within the page's data list where elements are inserted.
     * @param silently If `true`, the change will **not** trigger a [snapshot] emission.
     * @param initPageState Optional factory to create a new [PageState] for overflow pages that
     *   don't exist yet. If `null` and overflow occurs with no next page, subsequent pages are removed.
     * @throws IndexOutOfBoundsException If [targetPage] is not in the cache and [initPageState] is `null`.
     */
    fun addAllElements(
        elements: List<T>,
        targetPage: UInt,
        index: Int,
        silently: Boolean = false,
        isDirty: Boolean = false,
        initPageState: ((page: UInt, data: List<T>) -> PageState<T>)? = null
    ) {
        logger.log(TAG, "addAllElements: targetPage=$targetPage index=$index count=${elements.size} isDirty=$isDirty")
        val targetState: PageState<T> =
            (getStateOf(targetPage) ?: initPageState?.invoke(targetPage, mutableListOf()))
                ?: throw IndexOutOfBoundsException(
                    "page-$targetPage was not created"
                )

        val dataOfTargetState: MutableList<T> = requireNotNull(
            value = targetState.data as? MutableList
        ) { "data of target page state is not mutable" }
        dataOfTargetState.addAll(index, elements)
        val extraElements: MutableList<T>? =
            if (dataOfTargetState.size > capacity && !isCapacityUnlimited) {
                MutableList(size = dataOfTargetState.size - capacity) {
                    dataOfTargetState.removeAt(dataOfTargetState.lastIndex)
                }.apply(MutableList<T>::reverse)
            } else {
                null
            }

        if (dataOfTargetState is ArrayList) {
            dataOfTargetState.trimToSize()
        }

        if (!extraElements.isNullOrEmpty()) {
            val nextPageState: PageState<T>? = getStateOf(targetPage + 1u)
            if ((nextPageState != null && nextPageState::class == targetState::class)
                ||
                (nextPageState == null && initPageState != null)
            ) {
                addAllElements(
                    elements = extraElements,
                    targetPage = targetPage + 1u,
                    index = 0,
                    silently = true,
                    initPageState = initPageState
                )
            } else {
                val rangePageInvalidated: UIntRange = (targetPage + 1u)..cache.keys.last()
                rangePageInvalidated.forEach(cache::remove)
            }
        }

        if (isDirty) markDirty(targetPage)

        if (!silently) {
            val startState: PageState<T> = checkNotNull(
                walkBackwardWhile(getStateOf(startContextPage))
            ) { "startContextPage is broken so snapshot is impossible" }
            val endState: PageState<T> = checkNotNull(
                walkForwardWhile(getStateOf(endContextPage))
            ) { "endContextPage is broken so snapshot is impossible" }
            val rangeSnapshot: UIntRange = startState.page..endState.page
            if (targetPage in rangeSnapshot) {
                snapshot(rangeSnapshot)
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
     * [snapshot] is emitted at the end (unless [silently] is `true`).
     *
     * @param providerElement Factory that produces the replacement element given the current element,
     *   its [PageState], and its index within the page. Return `null` to remove the element.
     * @param silently If `true`, no [snapshot] is emitted after the operation completes.
     * @param predicate Determines whether an element should be processed. Receives the current element,
     *   its [PageState], and its index within the page.
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
                    } else {
                        removeElement(
                            page = pageState.page,
                            index = index,
                            silently = true
                        )
                    }
                }
                index++
            }
            return@smartForEach true
        }
        if (!silently) {
            snapshot()
        }
    }

    /**
     * Changes the expected number of items per page and optionally redistributes existing data.
     *
     * When [resize] is `true`, all success pages within the context window are collected,
     * their items are flattened into a single list, and then re-distributed into new pages
     * of size [capacity]. The cache is rebuilt from scratch with the redistributed data.
     *
     * If the new [capacity] equals the current one, this is a no-op.
     *
     * @param capacity The new capacity for each page. Must be >= 0.
     *   Use [UNLIMITED_CAPACITY] (0) to disable capacity checks.
     * @param resize If `true`, existing cached data is redistributed into pages of the new capacity.
     *   If `false`, only the capacity value is updated without touching cached data.
     * @param silently If `true`, no [snapshot] is emitted after the resize.
     * @param initSuccessState Factory for creating new [PageState.SuccessPage] instances during redistribution.
     * @throws IllegalArgumentException If [capacity] is negative.
     */
    fun resize(
        capacity: Int,
        resize: Boolean = true,
        silently: Boolean = false,
        initSuccessState: InitializerSuccessPage<T> = initializerSuccessPage
    ) {
        if (this.capacity == capacity) return
        require(capacity >= 0) { "capacity must be greater or equal than zero" }
        logger.log(TAG, "resize: capacity=$capacity resize=$resize")
        this.capacity = capacity

        if (resize && capacity > 0) {
            val firstSuccessPageState: PageState<T>? =
                walkForwardWhile(
                    pivotState = cache[startContextPage],
                    predicate = { pageState: PageState<T> ->
                        pageState.isSuccessState()
                    }
                )
            val lastSuccessPageState: PageState<T>? =
                walkBackwardWhile(
                    pivotState = cache[endContextPage],
                    predicate = { pageState: PageState<T> ->
                        pageState.isSuccessState()
                    }
                )
            firstSuccessPageState!!; lastSuccessPageState!!
            val items: MutableList<T> =
                (firstSuccessPageState.page..lastSuccessPageState.page)
                    .map { page: UInt -> cache.getValue(page) }
                    .flatMap { pageState: PageState<T> -> pageState.data }
                    .toMutableList()

            cache.clear()

            var pageIndex: UInt = firstSuccessPageState.page
            while (items.isNotEmpty()) {
                val successData = mutableListOf<T>()
                while (items.isNotEmpty() && successData.size < capacity) {
                    successData.add(items.removeAt(0))
                }

                setState(
                    state = initSuccessState.invoke(pageIndex++, successData),
                    silently = true
                )
            }
        }

        if (!silently && capacity > 0) {
            snapshot()
        }
    }


    operator fun minusAssign(page: UInt) {
        removeState(page)
    }

    operator fun minusAssign(pageState: PageState<T>) {
        removeState(pageState.page)
    }

    operator fun plusAssign(pageState: PageState<T>): Unit = setState(pageState)

    override fun toString(): String = "MutablePaginator(pages=$cache, bookmarks=$bookmarks)"
}
