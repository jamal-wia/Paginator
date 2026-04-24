package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cache.CursorPersistentPagingCache
import com.jamal_aliev.paginator.extension.isSuccessState
import com.jamal_aliev.paginator.load.CursorLoadResult
import com.jamal_aliev.paginator.logger.LogComponent
import com.jamal_aliev.paginator.logger.debug
import com.jamal_aliev.paginator.page.PageState
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic

/**
 * Cursor-based counterpart of [MutablePaginator]. Adds element-level CRUD on
 * top of the navigation/state machinery of [CursorPaginator].
 *
 * ## Removal is simple
 *
 * Unlike offset-based CRUD (which must collapse downstream page numbers),
 * cursor-based removal only re-links the neighbours of the removed page:
 *   `prevNeighbor.next = removed.next` and `nextNeighbor.prev = removed.prev`.
 *
 * ## Overflow cascade on `addAllElements`
 *
 * When an insert pushes elements past the page capacity, the "extras" cascade
 * along `cursor.next`. If the next cursor is missing from the cache the cascade
 * is **blocked by default** and the excess is dropped (with a log line). Pass a
 * [CursorBookmarkFactory] via [addAllElements] to instead synthesise a fresh
 * tail page for the overflow — the factory receives the overflow index and the
 * preceding bookmark so you can mint a new `self` key of your choosing.
 *
 * @param T The type of elements contained in each page.
 */
open class MutableCursorPaginator<T>(
    core: CursorPagingCore<T> = CursorPagingCore(),
    load: suspend CursorPaginator<T>.(cursor: CursorBookmark?) -> CursorLoadResult<T>,
) : CursorPaginator<T>(core, load) {

    /**
     * Factory producing fresh [CursorBookmark] instances for tail pages created
     * by overflow cascade in [addAllElements].
     *
     * @param overflowIndex Sequential counter for this cascade run (0-based).
     * @param previous The immediate predecessor bookmark of the synthesised page.
     */
    fun interface CursorBookmarkFactory {
        fun create(overflowIndex: Int, previous: CursorBookmark): CursorBookmark
    }

    private val _affectedSelves: AtomicRef<Set<Any>> = atomic(emptySet())

    private fun markAffected(self: Any) {
        while (true) {
            val current = _affectedSelves.value
            if (_affectedSelves.compareAndSet(current, current + self)) return
        }
    }

    private fun markAffectedAll(selves: Set<Any>) {
        if (selves.isEmpty()) return
        while (true) {
            val current = _affectedSelves.value
            if (_affectedSelves.compareAndSet(current, current + selves)) return
        }
    }

    private fun drainAffectedSelves(): Set<Any> = _affectedSelves.getAndSet(emptySet())

    /**
     * Removes the page identified by [selfToRemove] from the cache and re-links
     * its immediate neighbours so the doubly-linked chain stays consistent.
     *
     * If the removed page was the current [CursorPagingCore.startContextCursor]
     * or [CursorPagingCore.endContextCursor], the context boundary is shifted to
     * the appropriate neighbour (or set to `null` when no neighbour is cached).
     *
     * @return The removed [PageState], or `null` if [selfToRemove] was not in the cache.
     */
    fun removeState(selfToRemove: Any, silently: Boolean = false): PageState<T>? {
        logger.debug(LogComponent.MUTATION) { "removeState: self=$selfToRemove" }

        val removedCursor: CursorBookmark = cache.getCursorOf(selfToRemove) ?: return null
        val removedState: PageState<T> = cache.getStateOf(selfToRemove) ?: return null

        val prevCursor: CursorBookmark? = removedCursor.prev?.let { cache.getCursorOf(it) }
        val nextCursor: CursorBookmark? = removedCursor.next?.let { cache.getCursorOf(it) }

        // Drop the page itself.
        cache.removeFromCache(selfToRemove)

        // Relink neighbours.
        if (prevCursor != null) {
            val updated = prevCursor.copy(next = removedCursor.next)
            cache.getStateOf(prevCursor.self)?.let { cache.setState(updated, it, silently = true) }
        }
        if (nextCursor != null) {
            val updated = nextCursor.copy(prev = removedCursor.prev)
            cache.getStateOf(nextCursor.self)?.let { cache.setState(updated, it, silently = true) }
        }

        // Shift the context window if the removed cursor was a boundary.
        if (core.startContextCursor?.self == selfToRemove) {
            core.startContextCursor = nextCursor?.copy(prev = removedCursor.prev)
        }
        if (core.endContextCursor?.self == selfToRemove) {
            core.endContextCursor = prevCursor?.copy(next = removedCursor.next)
        }

        markAffected(selfToRemove)
        if (!silently) core.snapshot()
        return removedState
    }

    fun setElement(
        element: T,
        self: Any,
        index: Int,
        silently: Boolean = false,
        isDirty: Boolean = false,
    ) {
        logger.debug(LogComponent.MUTATION) {
            "setElement: self=$self index=$index isDirty=$isDirty"
        }
        val cursor = cache.getCursorOf(self)
            ?: throw NoSuchElementException("self=$self was not found in cache")
        val pageState = cache.getStateOf(self)
            ?: throw NoSuchElementException("self=$self was not found in cache")
        cache.setState(
            cursor = cursor,
            state = pageState.copy(
                data = pageState.data
                    .let { it as MutableList }
                    .also { it[index] = element }
            ),
            silently = true,
        )
        markAffected(self)
        if (isDirty) core.markDirty(self)
        if (!silently) snapshotIfSelfVisible(self)
    }

    fun removeElement(
        self: Any,
        index: Int,
        silently: Boolean = false,
        isDirty: Boolean = false,
    ): T {
        logger.debug(LogComponent.MUTATION) {
            "removeElement: self=$self index=$index isDirty=$isDirty"
        }
        val cursor = requireNotNull(cache.getCursorOf(self)) { "self=$self was not created" }
        val pageState = requireNotNull(cache.getStateOf(self)) { "self=$self was not created" }
        markAffected(self)
        val removed: T

        val updatedData = pageState.data
            .let { it as MutableList }
            .also { removed = it.removeAt(index) }

        if (updatedData.size < core.capacity && !core.isCapacityUnlimited) {
            val nextCursor = cache.walkForward(cursor)
            val nextState = nextCursor?.self?.let { cache.getStateOf(it) }
            if (nextCursor != null && nextState != null
                && nextState::class == pageState::class
            ) {
                while (updatedData.size < core.capacity && nextState.data.isNotEmpty()) {
                    updatedData.add(
                        removeElement(
                            self = nextCursor.self,
                            index = 0,
                            silently = true,
                        )
                    )
                }
            }
        }

        if (updatedData.isEmpty()) {
            removeState(selfToRemove = self, silently = true)
        } else {
            cache.setState(cursor, pageState.copy(data = updatedData), silently = true)
        }

        if (isDirty) core.markDirty(self)
        if (!silently) snapshotIfSelfVisible(self)
        return removed
    }

    /**
     * Inserts [elements] at [index] on the page identified by [targetSelf], cascading
     * any overflow into `targetCursor.next`.
     *
     * @param bookmarkFactory Optional factory producing new cursors for tail pages
     *   when the cascade needs to spawn one. When `null` the cascade stops at the
     *   first missing link and excess elements are dropped.
     */
    fun addAllElements(
        elements: List<T>,
        targetSelf: Any,
        index: Int,
        silently: Boolean = false,
        isDirty: Boolean = false,
        bookmarkFactory: CursorBookmarkFactory? = null,
        initPageState: ((previous: CursorBookmark, data: List<T>) -> PageState<T>)? = null,
    ) {
        logger.debug(LogComponent.MUTATION) {
            "addAllElements: targetSelf=$targetSelf index=$index count=${elements.size} isDirty=$isDirty"
        }
        cascadeAddAll(
            elements = elements,
            targetSelf = targetSelf,
            index = index,
            overflowCounter = 0,
            bookmarkFactory = bookmarkFactory,
            initPageState = initPageState,
        )
        if (isDirty) core.markDirty(targetSelf)
        if (!silently) snapshotIfSelfVisible(targetSelf)
    }

    private fun cascadeAddAll(
        elements: List<T>,
        targetSelf: Any,
        index: Int,
        overflowCounter: Int,
        bookmarkFactory: CursorBookmarkFactory?,
        initPageState: ((previous: CursorBookmark, data: List<T>) -> PageState<T>)?,
    ) {
        markAffected(targetSelf)

        val targetCursor: CursorBookmark = cache.getCursorOf(targetSelf)
            ?: throw IndexOutOfBoundsException("self=$targetSelf was not created")
        val targetState: PageState<T> = cache.getStateOf(targetSelf)
            ?: throw IndexOutOfBoundsException("self=$targetSelf was not created")

        val dataOfTarget: MutableList<T> = requireNotNull(
            value = targetState.data as? MutableList
        ) { "data of target page state is not mutable" }

        dataOfTarget.addAll(index, elements)

        val extras: MutableList<T>? =
            if (dataOfTarget.size > core.capacity && !core.isCapacityUnlimited) {
                MutableList(size = dataOfTarget.size - core.capacity) {
                    dataOfTarget.removeAt(dataOfTarget.lastIndex)
                }.apply(MutableList<T>::reverse)
            } else null

        if (dataOfTarget is ArrayList) dataOfTarget.trimToSize()

        if (extras.isNullOrEmpty()) return

        val nextCursor: CursorBookmark? = cache.walkForward(targetCursor)
        val nextState: PageState<T>? = nextCursor?.self?.let { cache.getStateOf(it) }

        when {
            nextCursor != null && nextState != null && nextState::class == targetState::class -> {
                cascadeAddAll(
                    elements = extras,
                    targetSelf = nextCursor.self,
                    index = 0,
                    overflowCounter = overflowCounter + 1,
                    bookmarkFactory = bookmarkFactory,
                    initPageState = initPageState,
                )
            }

            nextCursor == null && bookmarkFactory != null -> {
                // Option (b): synthesise a brand-new tail page for the overflow.
                val newCursor: CursorBookmark = bookmarkFactory
                    .create(overflowCounter, targetCursor)
                    .copy(prev = targetCursor.self)
                // Update current target's `next` link to point at the new cursor.
                val updatedTarget = targetCursor.copy(next = newCursor.self)
                cache.setState(updatedTarget, targetState, silently = true)

                val initialData: MutableList<T> = extras.toMutableList()
                val newState: PageState<T> = initPageState?.invoke(updatedTarget, initialData)
                    ?: targetState.copy(data = initialData)
                cache.setState(newCursor, newState, silently = true)

                // No further cascade: the new page has at most `extras.size` items which
                // already fit the capacity check here (they came from trimming the target).
            }

            else -> {
                // Option (a): cascade blocked — extras are dropped.
                logger.debug(LogComponent.MUTATION) {
                    "addAllElements: cascade blocked at self=$targetSelf, dropping ${extras.size} extras"
                }
            }
        }
    }

    /**
     * Iterates over all loaded elements and conditionally replaces or removes them.
     *
     * The flow mirrors [MutablePaginator.replaceAllElements] — a `null` returned
     * from [providerElement] removes the element; otherwise it replaces it.
     */
    inline fun replaceAllElements(
        providerElement: (current: T, pageState: PageState<T>, index: Int) -> T?,
        silently: Boolean = false,
        predicate: (current: T, pageState: PageState<T>, index: Int) -> Boolean,
    ) {
        // Snapshot the cursors so we iterate over a stable list even if mutations
        // reshape the cache mid-flight (e.g. `removeElement` eliding an empty page).
        val cursorsSnapshot: List<CursorBookmark> = core.cursors.toList()
        for (cursor in cursorsSnapshot) {
            val pageState: PageState<T> = cache.getStateOf(cursor.self) ?: continue
            var index = 0
            while (index < pageState.data.size) {
                val current = pageState.data[index]
                if (predicate(current, pageState, index)) {
                    val newElement = providerElement(current, pageState, index)
                    if (newElement != null) {
                        setElement(
                            element = newElement,
                            self = cursor.self,
                            index = index,
                            silently = true,
                        )
                        index++
                    } else {
                        removeElement(
                            self = cursor.self,
                            index = index,
                            silently = true,
                        )
                        // Don't increment: subsequent elements shifted left.
                    }
                } else {
                    index++
                }
            }
        }
        if (!silently) core.snapshot()
    }

    private fun snapshotIfSelfVisible(self: Any) {
        val visible = core.snapshotSelves()
        if (self in visible) core.snapshot()
    }

    // ── Persistent (L2) flush ───────────────────────────────────────────────

    suspend fun flush() {
        val pc: CursorPersistentPagingCache<T> = core.persistentCache ?: return
        val selvesToFlush: Set<Any> = drainAffectedSelves()
        if (selvesToFlush.isEmpty()) return

        val toSave = mutableListOf<Pair<CursorBookmark, PageState<T>>>()
        val toRemove = mutableListOf<Any>()

        for (self in selvesToFlush) {
            val state = cache.getStateOf(self)
            val cursor = cache.getCursorOf(self)
            if (state.isSuccessState() && cursor != null) {
                toSave.add(cursor to state)
            } else if (state == null) {
                toRemove.add(self)
            }
        }

        pc.transaction {
            if (toSave.isNotEmpty()) saveAll(toSave)
            if (toRemove.isNotEmpty()) removeAll(toRemove)
        }
    }

    // ── Transaction override (auto-persist on success) ──────────────────────

    override suspend fun <R> transaction(block: suspend CursorPaginator<T>.() -> R): R {
        val savedAffected = drainAffectedSelves()
        try {
            val result = super.transaction(block)
            flush()
            markAffectedAll(savedAffected)
            return result
        } catch (e: Throwable) {
            _affectedSelves.value = savedAffected
            throw e
        }
    }

    // ── Operators ───────────────────────────────────────────────────────────

    operator fun minusAssign(self: Any) {
        removeState(self)
    }

    operator fun plusAssign(entry: Pair<CursorBookmark, PageState<T>>) {
        core.setState(entry.first, entry.second)
        markAffected(entry.first.self)
    }

    override fun toString(): String = "MutableCursorPaginator(cache=$cache, bookmarks=$bookmarks)"
}
