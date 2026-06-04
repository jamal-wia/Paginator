package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.core.extension.isSuccessState
import com.jamal_aliev.paginator.core.logger.LogComponent
import com.jamal_aliev.paginator.core.logger.debug
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.cache.persistent.CursorPersistentPagingCache
import com.jamal_aliev.paginator.cursor.load.CursorLoadResult
import com.jamal_aliev.paginator.cursor.page.CursorPageState
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
open class MutableCursorPaginator<K : Any, T>(
    core: CursorPagingCore<K, T> = CursorPagingCore(),
    load: suspend CursorPaginator<K, T>.(cursor: CursorBookmark<K>?) -> CursorLoadResult<K, T>,
) : CursorPaginator<K, T>(core, load) {

    /**
     * Factory producing fresh [CursorBookmark<K>] instances for tail pages created
     * by overflow cascade in [addAllElements].
     *
     * @param overflowIndex Sequential counter for this cascade run (0-based).
     * @param previous The immediate predecessor bookmark of the synthesised page.
     */
    fun interface CursorBookmarkFactory<K : Any> {
        fun create(overflowIndex: Int, previous: CursorBookmark<K>): CursorBookmark<K>
    }

    private val _affectedSelves: AtomicRef<Set<K>> = atomic(emptySet())

    /**
     * Read-only snapshot of page `self` keys modified by CRUD operations that have not
     * yet been flushed to the [persistent cache][CursorPagingCore.persistentCache] (L2).
     *
     * Useful for diagnostics, tests, and UI "unsaved changes" indicators. The returned
     * set is a copy — mutating it has no effect on the paginator.
     *
     * Mirrors [MutablePaginator.affectedPages] but keyed by `self` instead of `page: Int`.
     */
    val affectedSelves: Set<K>
        get() = _affectedSelves.value

    /**
     * `true` when there are CRUD changes tracked for the next [flush] call.
     *
     * Equivalent to `affectedSelves.isNotEmpty()` but avoids materialising the set.
     * Always `false` when [CursorPagingCore.persistentCache] is `null`.
     */
    val hasPendingFlush: Boolean
        get() = core.persistentCache != null && _affectedSelves.value.isNotEmpty()

    private fun markAffected(self: K) {
        while (true) {
            val current = _affectedSelves.value
            if (_affectedSelves.compareAndSet(current, current + self)) return
        }
    }

    private fun markAffectedAll(selves: Set<K>) {
        if (selves.isEmpty()) return
        while (true) {
            val current = _affectedSelves.value
            if (_affectedSelves.compareAndSet(current, current + selves)) return
        }
    }

    private fun drainAffectedSelves(): Set<K> = _affectedSelves.getAndSet(emptySet())

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
    fun removeState(selfToRemove: K, silently: Boolean = false): CursorPageState<K, T>? {
        logger.debug(LogComponent.MUTATION) { "removeState: self=$selfToRemove" }

        val removedCursor: CursorBookmark<K> = cache.getCursorOf(selfToRemove) ?: return null
        val removedState: CursorPageState<K, T> = cache.getStateOf(selfToRemove) ?: return null

        val prevCursor: CursorBookmark<K>? = removedCursor.prev?.let { cache.getCursorOf(it) }
        val nextCursor: CursorBookmark<K>? = removedCursor.next?.let { cache.getCursorOf(it) }

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

        // The one-step neighbour shift above can land a boundary on a non-filled (or now
        // missing) page while filled pages remain elsewhere in the cache — leaving the user
        // on a blank/degraded window. Re-anchor to the nearest filled-success group instead.
        val startC: CursorBookmark<K>? = core.startContextCursor
        val endC: CursorBookmark<K>? = core.endContextCursor
        val windowBroken: Boolean = startC == null || endC == null
                || !core.isFilledSuccessState(core.getStateOf(startC.self))
                || !core.isFilledSuccessState(core.getStateOf(endC.self))
        if (windowBroken && core.size > 0) {
            core.findNearContextCursor(startC, endC)
        }

        markAffected(selfToRemove)
        if (!silently) core.snapshot()
        return removedState
    }

    fun setElement(
        element: T,
        self: K,
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
        self: K,
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

        // Snapshot the window before mutating: emptying the page may drop it and re-anchor
        // the window (via removeState), which we must detect to emit the new window.
        val startBeforeSelf: K? = core.startContextCursor?.self
        val endBeforeSelf: K? = core.endContextCursor?.self

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
        if (!silently) {
            if (core.startContextCursor?.self != startBeforeSelf
                || core.endContextCursor?.self != endBeforeSelf
            ) {
                // The removal moved/re-anchored the context window — emit the whole new
                // window. snapshotIfSelfVisible(self) would suppress it because the old
                // page is no longer visible, stranding the UI on a stale/blank window.
                core.snapshot()
            } else {
                snapshotIfSelfVisible(self)
            }
        }
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
        targetSelf: K,
        index: Int,
        silently: Boolean = false,
        isDirty: Boolean = false,
        bookmarkFactory: CursorBookmarkFactory<K>? = null,
        initPageState: ((previous: CursorBookmark<K>, data: List<T>) -> CursorPageState<K, T>)? = null,
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
        targetSelf: K,
        index: Int,
        overflowCounter: Int,
        bookmarkFactory: CursorBookmarkFactory<K>?,
        initPageState: ((previous: CursorBookmark<K>, data: List<T>) -> CursorPageState<K, T>)?,
    ) {
        markAffected(targetSelf)

        val targetCursor: CursorBookmark<K> = cache.getCursorOf(targetSelf)
            ?: throw IndexOutOfBoundsException("self=$targetSelf was not created")
        val targetState: CursorPageState<K, T> = cache.getStateOf(targetSelf)
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

        val nextCursor: CursorBookmark<K>? = cache.walkForward(targetCursor)
        val nextState: CursorPageState<K, T>? = nextCursor?.self?.let { cache.getStateOf(it) }

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
                val newCursor: CursorBookmark<K> = bookmarkFactory
                    .create(overflowCounter, targetCursor)
                    .copy(prev = targetCursor.self)
                // Update current target's `next` link to point at the new cursor.
                val updatedTarget = targetCursor.copy(next = newCursor.self)
                cache.setState(updatedTarget, targetState, silently = true)

                val initialData: MutableList<T> = extras.toMutableList()
                val newState: CursorPageState<K, T> =
                    initPageState?.invoke(updatedTarget, initialData)
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
        providerElement: (current: T, pageState: CursorPageState<K, T>, index: Int) -> T?,
        silently: Boolean = false,
        predicate: (current: T, pageState: CursorPageState<K, T>, index: Int) -> Boolean,
    ) {
        // Snapshot the cursors so we iterate over a stable list even if mutations
        // reshape the cache mid-flight (e.g. `removeElement` eliding an empty page).
        val cursorsSnapshot: List<CursorBookmark<K>> = core.cursors.toList()
        for (cursor in cursorsSnapshot) {
            val pageState: CursorPageState<K, T> = cache.getStateOf(cursor.self) ?: continue
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

    private fun snapshotIfSelfVisible(self: K) {
        val visible = core.snapshotSelves()
        if (self in visible) core.snapshot()
    }

    // ── Persistent (L2) flush ───────────────────────────────────────────────

    suspend fun flush() {
        val pc: CursorPersistentPagingCache<K, T> = core.persistentCache ?: return
        val selvesToFlush: Set<K> = drainAffectedSelves()
        if (selvesToFlush.isEmpty()) return

        val toSave = mutableListOf<Pair<CursorBookmark<K>, CursorPageState<K, T>>>()
        val toRemove = mutableListOf<K>()

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

    override suspend fun <R> transaction(block: suspend CursorPaginator<K, T>.() -> R): R {
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

    operator fun minusAssign(self: K) {
        removeState(self)
    }

    operator fun plusAssign(entry: Pair<CursorBookmark<K>, CursorPageState<K, T>>) {
        core.setState(entry.first, entry.second)
        markAffected(entry.first.self)
    }

    override fun toString(): String = "MutableCursorPaginator(cache=$cache, bookmarks=$bookmarks)"
}
