package com.jamal_aliev.paginator.core.cache.reactive

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * How [SnapshotReactiveCache] reacts when the *relative order* of items that
 * survive between two snapshots changes (a re-sort, a drag-to-reorder, a
 * server-driven re-ranking).
 *
 * Inserts, removals, and content edits are always expressed as precise point
 * events regardless of this policy — reordering of the surviving items is the
 * only thing this knob governs, because a windowed offset cache cannot always
 * reproduce an arbitrary permutation cheaply.
 */
enum class ReorderPolicy {

    /**
     * Emit a single [ReactiveEvent.Invalidate] when a reorder is detected, so
     * the paginator re-fetches every cached page via `load` and the
     * source-of-truth ordering wins.
     *
     * Simplest and always correct, at the cost of losing per-item animation
     * for the reordered rows. Matches the documented "when in doubt, emit
     * Invalidate" guidance. **Default.**
     */
    Invalidate,

    /**
     * Emit [ReactiveEvent.Moved] for the minimal set of items whose position
     * actually changed.
     *
     * The stable subset (the longest common subsequence of the survivors in
     * both snapshots) stays put; only the rest are moved, so the UI animates
     * the smallest possible number of rows. More precise than [Invalidate] but
     * relies on every moved anchor being inside the loaded window (see the
     * windowing note on [SnapshotReactiveCache]).
     */
    Moves,
}

/**
 * A ready-made [PaginatorReactiveCache] that turns a snapshot stream
 * (`Flow<List<T>>`) into [ReactiveEvent]s by diffing consecutive emissions —
 * the paginator analogue of RecyclerView's `ListAdapter` / `DiffUtil`.
 *
 * Instead of hand-writing inserts/updates/removes, the adapter author provides
 * only:
 * - a [snapshots] flow that emits the current list whenever the source changes
 *   (a Room `Flow<List<T>>`, a SQLDelight query, a `MutableStateFlow`, …), and
 * - an [identitySelector] that returns the stable identity of an item.
 *
 * Each new emission is diffed against the previous one and translated to the
 * matching [ReactiveEvent]s, wrapped in a single [ReactiveEvent.Batch] so the
 * change applies atomically.
 *
 * ## Relationship to `DiffUtil`
 *
 * | RecyclerView                          | This class                                     |
 * |---------------------------------------|------------------------------------------------|
 * | `DiffUtil.areItemsTheSame(a, b)`      | `identitySelector(a) == identitySelector(b)`   |
 * | `DiffUtil.areContentsTheSame(a, b)`   | [areContentsTheSame] (default `a == b`)        |
 * | `AsyncListDiffer.submitList(newList)` | a new emission on [snapshots]                  |
 * | RecyclerView animates the delta       | the UI observing the paginator animates the CRUD |
 *
 * ## First emission is a baseline
 *
 * The **first** snapshot only establishes the baseline and emits nothing.
 * Initial population of the cache is the job of the paginator's `load` lambda
 * (plus the `observe()` `InitialSyncPolicy`); the snapshot stream carries only
 * *deltas* from then on. Diffing the first snapshot against an empty list would
 * emit one insert per row and, racing an already-completed `load`, duplicate
 * every visible item — so the baseline emission is intentionally suppressed.
 *
 * ## Insert positions
 *
 * New items are anchored on their left neighbour in the new snapshot order:
 * the item at index 0 lands at [InsertPosition.Head], every other new item at
 * [InsertPosition.AfterIdentity] of the preceding item. Walking the snapshot
 * left-to-right and applying events in order reconstructs the target ordering.
 *
 * ## Windowing caveat
 *
 * Snapshot diffing assumes the snapshot fits inside the paginator's currently
 * loaded window. If the snapshot is larger than the window, an
 * `AfterIdentity(...)` anchor (for an insert or a [ReorderPolicy.Moves] move)
 * can reference a row outside the cache; the event then falls through to the
 * active `UnknownItemPolicy` (dropped under `Drop`, escalated to a full refresh
 * under `RefreshAll`). Have the source return only the visible slice
 * (`SELECT … LIMIT windowSize`) when this matters.
 *
 * ## Identity uniqueness
 *
 * [identitySelector] must return a value unique within any single snapshot and
 * stable for a logical entity across edits. Duplicate identities inside one
 * snapshot are not supported (the diff keys items by identity).
 *
 * @param T The item type produced by the source. Must match the paginator's item type.
 * @param ID The identity type. Must have meaningful `equals`/`hashCode` — typically
 *   `Long`, `String`, or a dedicated id `data class`.
 * @param snapshots The reactive source emitting the full current list on every change.
 * @param identitySelector Returns the stable identity of an item (its primary key).
 * @param areContentsTheSame Decides whether two items with the same identity carry the
 *   same content. When it returns `false`, an [ReactiveEvent.Updated] is emitted.
 *   Defaults to structural equality (`old == new`).
 * @param reorderPolicy How to handle a change in the relative order of surviving
 *   items. Defaults to [ReorderPolicy.Invalidate].
 * @param resolveInsert Decides **where** a brand-new item is inserted, or whether
 *   it is inserted at all. Receives the item and the identity of its left
 *   neighbour in the new snapshot (`null` when the item is first). Return an
 *   [InsertPosition], or `null` to **skip** the insert entirely (a deliberate
 *   veto, not an error — the active `UnknownItemPolicy` is not consulted). The
 *   default reproduces the snapshot order: [InsertPosition.Head] for the first
 *   item, [InsertPosition.AfterIdentity] of the left neighbour otherwise.
 *   Applies to inserts only — `Moved` (under [ReorderPolicy.Moves]) always
 *   anchors on the left neighbour. Two common overrides:
 *   - newest-first feed whose snapshot order is opposite to the page layout:
 *     `{ _, _ -> InsertPosition.Head }` (or simpler, reverse the snapshot list
 *     upstream so the default works for batch inserts too);
 *   - conditional insert: `{ item, _ -> if (canPlace(item)) InsertPosition.Head else null }`.
 *
 * @see snapshotReactiveCache for a factory with an ergonomic `identity =` parameter.
 */
class SnapshotReactiveCache<T, ID : Any>(
    private val snapshots: Flow<List<T>>,
    private val identitySelector: (item: T) -> ID,
    private val areContentsTheSame: (old: T, new: T) -> Boolean = { old, new -> old == new },
    private val reorderPolicy: ReorderPolicy = ReorderPolicy.Invalidate,
    private val resolveInsert: (item: T, leftNeighborId: ID?) -> InsertPosition<ID>? =
        { _, leftNeighborId ->
            if (leftNeighborId == null) InsertPosition.Head
            else InsertPosition.AfterIdentity(leftNeighborId)
        },
) : PaginatorReactiveCache<T, ID> {

    override fun identity(item: T): ID = identitySelector(item)

    override fun changes(): Flow<ReactiveEvent<T, ID>> = flow {
        // `previous` is local to each collection so a re-subscribe starts clean
        // and re-establishes its own baseline.
        var previous: List<T>? = null
        snapshots.collect { current ->
            val baseline = previous
            previous = current
            // The very first snapshot has nothing to diff against, so we only
            // remember it as the baseline and emit nothing. The cache's initial
            // contents come from `load`, not from these events (see class KDoc):
            // diffing the first snapshot against an empty list would re-insert —
            // and likely duplicate — every row `load` has already shown.
            if (baseline == null) return@collect
            val event = diff(baseline, current)
            if (event != null) emit(event)
        }
    }

    /**
     * Turns the difference between two consecutive snapshots into the event(s)
     * that update the cache.
     *
     * [previous] is what the cache currently shows; [current] is the new truth.
     * The goal is to emit the *smallest* set of point events (Removed / Inserted
     * / Updated / Moved) that, applied in order, turn [previous] into [current].
     * We deliberately never rebuild the whole list — that would throw away item
     * identity and the per-row animations the UI gets from a granular change.
     *
     * Returns `null` when nothing changed, the single event when exactly one
     * thing changed, or a [ReactiveEvent.Batch] (applied in one transaction)
     * when several things changed.
     *
     * Worked example — [previous] `[A, B, C]`, [current] `[A, C, D]`:
     * - `B` is gone         → `Removed(B)`
     * - `D` is new, after C → `Inserted(D, after C)`
     * - `A`, `C` unchanged  → nothing
     * ⇒ `Batch[Removed(B), Inserted(D, after C)]`.
     */
    private fun diff(previous: List<T>, current: List<T>): ReactiveEvent<T, ID>? {
        // Index both snapshots by identity up front. `previousById` maps
        // id -> old item, so we can both ask "was this id here before?" and pull
        // the old value out to compare contents. `currentIds` is only a
        // membership set. Without these, every lookup in the loops below would
        // re-scan a whole list and the diff would become O(n²).
        val previousById = LinkedHashMap<ID, T>(previous.size)
        for (item in previous) previousById[identity(item)] = item
        val currentIds = HashSet<ID>(current.size)
        for (item in current) currentIds.add(identity(item))

        val events = ArrayList<ReactiveEvent<T, ID>>()

        // 1) REMOVED — every id that was in `previous` but is gone from `current`.
        //    Emitted first because a removal never changes where the inserts and
        //    moves below anchor, so it is always safe to list them up front.
        for (id in previousById.keys) {
            if (id !in currentIds) events.add(ReactiveEvent.Removed(id))
        }

        // 2) DID THE SURVIVING ITEMS CHANGE ORDER?
        //    "Survivors" = items present in BOTH snapshots. Plain inserts and
        //    removals never change the survivors' order relative to each other,
        //    so a genuine re-sort / drag-to-reorder is the ONLY case that needs
        //    special handling. We detect it by listing the survivor ids in
        //    `previous` order and in `current` order and comparing the two: if
        //    they differ, some existing row moved.
        //    (We restrict to survivors because a brand-new row has no "before"
        //    position and a removed row has no "after" position — only an item
        //    present in both snapshots can be said to have moved.)
        val survivorsBefore = ArrayList<ID>()
        for (item in previous) {
            val id = identity(item)
            if (id in currentIds) survivorsBefore.add(id)
        }
        val survivorsAfter = ArrayList<ID>()
        for (item in current) {
            val id = identity(item)
            if (previousById.containsKey(id)) survivorsAfter.add(id)
        }
        val reordered = survivorsBefore != survivorsAfter

        if (reordered && reorderPolicy == ReorderPolicy.Invalidate) {
            // Default policy: don't bother expressing the reorder as point
            // events — just ask the paginator to refetch every page. That full
            // refresh ALSO re-applies any inserts/removals/updates contained in
            // this same snapshot, so there is nothing else to compute here.
            return ReactiveEvent.Invalidate
        }

        // Moves policy: express the reorder precisely while moving as FEW rows
        // as possible. Find the largest group of survivors that are already in
        // the right relative order — their longest common subsequence (LCS)
        // across the before/after orders — and leave those untouched. Every
        // other survivor is a row that genuinely has to move.
        //   before [A, B, C], after [A, C, B]: A and C are already in order
        //   (LCS = [A, C]), so only B is marked as moved.
        val movedIds: Set<ID> =
            if (reordered) {
                val stable = longestCommonSubsequence(survivorsBefore, survivorsAfter).toHashSet()
                survivorsAfter.filterTo(HashSet()) { it !in stable }
            } else {
                emptySet()
            }

        // 3) WALK `current` LEFT TO RIGHT and emit one event per changed row.
        //    Every insert/move is anchored on its LEFT NEIGHBOUR — the row right
        //    before it in `current`. Why this works: we process rows in order,
        //    so by the time we reach a row its left neighbour is already sitting
        //    in its final place in the cache. "Insert after the left neighbour"
        //    therefore rebuilds the exact target order one step at a time — this
        //    is what lets even a full reversal come out correct.
        //    `leftId` holds that left neighbour. It MUST be a row that actually
        //    exists in the cache, so we only advance it past rows we really
        //    placed: a survivor (already there) or an insert we just emitted. A
        //    vetoed insert (resolver returned null) never lands, so we leave
        //    `leftId` as-is — otherwise the next insert would anchor on a row
        //    that isn't there and silently fail.
        var leftId: ID? = null
        for (item in current) {
            val id = identity(item)
            val before = previousById[id]
            if (before == null) {
                // Brand-new row. Ask the resolver where it goes; `null` means
                // "skip this insert on purpose" (e.g. its target page isn't
                // loaded), so we emit nothing and keep `leftId` unchanged.
                val position = resolveInsert(item, leftId)
                if (position != null) {
                    events.add(ReactiveEvent.Inserted(item, position))
                    leftId = id
                }
                continue
            }
            // Survivor — it is already in the cache, so it always becomes the
            // next anchor, whether or not it changed.
            if (id in movedIds) {
                // Marked as moved in step 2. `Moved` carries the new value, so a
                // single event repositions the row AND refreshes its contents.
                events.add(ReactiveEvent.Moved(item, positionFor(leftId)))
            } else if (!areContentsTheSame(before, item)) {
                // Same position, but the fields changed → in-place update.
                events.add(ReactiveEvent.Updated(item))
            }
            // else: same position, same contents → nothing to emit.
            leftId = id
        }

        // Package the result. Wrapping several events in one Batch makes the
        // paginator apply them inside a single transaction, so the screen never
        // shows a half-applied snapshot (e.g. a remove without its matching
        // insert). One event needs no wrapper; zero means "nothing changed".
        return when (events.size) {
            0 -> null
            1 -> events[0]
            else -> ReactiveEvent.Batch(events)
        }
    }

    /** Head when there is no left neighbour (the first row), else right after it. */
    private fun positionFor(leftId: ID?): InsertPosition<ID> =
        if (leftId == null) InsertPosition.Head else InsertPosition.AfterIdentity(leftId)

    /**
     * Returns one **longest common subsequence** of [a] and [b]: the longest
     * list of ids that appears in both, in the same relative order (the ids need
     * not be adjacent). For example LCS of `[A, B, C, D]` and `[A, C, B, D]` is
     * `[A, B, D]` (or `[A, C, D]`) — length 3.
     *
     * Why we need it: those ids are exactly the survivors that are already in
     * the correct order, so under [ReorderPolicy.Moves] they can stay put while
     * everyone else moves around them. Maximising this stable set minimises the
     * number of `Moved` events — and therefore the amount of UI animation.
     *
     * It is the classic dynamic-programming LCS, O(a·b) time and memory. The
     * inputs are only the survivor ids (bounded by the loaded window), so this
     * stays cheap in practice.
     */
    private fun longestCommonSubsequence(a: List<ID>, b: List<ID>): List<ID> {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return emptyList()

        // Step 1 — fill a table where lengths[i][j] is the LCS length of the
        // SUFFIXES a[i..] and b[j..]. We compute it from the bottom-right corner
        // backwards (high indices first) so every cell only reads cells that are
        // already filled — the one diagonally after it and the two just after it.
        // The recurrence:
        //   - a[i] == b[j]: this id can extend the LCS → 1 + the diagonal cell.
        //   - a[i] != b[j]: we must drop one side; keep the better of "skip a[i]"
        //     (cell below) and "skip b[j]" (cell to the right).
        val lengths = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lengths[i][j] =
                    if (a[i] == b[j]) lengths[i + 1][j + 1] + 1
                    else maxOf(lengths[i + 1][j], lengths[i][j + 1])
            }
        }

        // Step 2 — the table gives lengths, not the ids, so walk it forward from
        // the top-left (0,0) to recover the actual subsequence:
        //   - ids match → that id is part of the LCS: record it, step diagonally.
        //   - ids differ → move toward the larger neighbouring length, i.e. the
        //     side that still has the longer LCS ahead of it (ties advance `a`).
        val result = ArrayList<ID>(minOf(n, m))
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    result.add(a[i]); i++; j++
                }

                lengths[i + 1][j] >= lengths[i][j + 1] -> i++
                else -> j++
            }
        }
        return result
    }
}

/**
 * Creates a [SnapshotReactiveCache] — the paginator analogue of `ListAdapter` /
 * `DiffUtil`. Diffs consecutive emissions of [snapshots] into [ReactiveEvent]s
 * so the adapter author writes only an [identity] selector and a snapshot
 * source.
 *
 * ```kotlin
 * val source = snapshotReactiveCache(
 *     snapshots = dao.observeAll(),   // Flow<List<User>>
 *     identity = { it.id },
 * )
 * paginator.observe(scope = viewModelScope, source = source)
 * ```
 *
 * See [SnapshotReactiveCache] for the diffing semantics, the first-emission
 * baseline, the windowing caveat, and [ReorderPolicy].
 *
 * @param snapshots The reactive source emitting the full current list on every change.
 * @param identity Returns the stable identity of an item (its primary key).
 * @param areContentsTheSame Decides whether two items with the same identity carry the
 *   same content; `false` triggers an [ReactiveEvent.Updated]. Defaults to `old == new`.
 * @param reorderPolicy How to handle a change in the relative order of surviving items.
 *   Defaults to [ReorderPolicy.Invalidate].
 * @param resolveInsert Decides where a new item is inserted, or `null` to skip the
 *   insert. See [SnapshotReactiveCache] for the semantics and common overrides.
 *   Defaults to reproducing the snapshot order.
 */
fun <T, ID : Any> snapshotReactiveCache(
    snapshots: Flow<List<T>>,
    identity: (item: T) -> ID,
    areContentsTheSame: (old: T, new: T) -> Boolean = { old, new -> old == new },
    reorderPolicy: ReorderPolicy = ReorderPolicy.Invalidate,
    resolveInsert: (item: T, leftNeighborId: ID?) -> InsertPosition<ID>? =
        { _, leftNeighborId ->
            if (leftNeighborId == null) InsertPosition.Head
            else InsertPosition.AfterIdentity(leftNeighborId)
        },
): PaginatorReactiveCache<T, ID> =
    SnapshotReactiveCache(
        snapshots = snapshots,
        identitySelector = identity,
        areContentsTheSame = areContentsTheSame,
        reorderPolicy = reorderPolicy,
        resolveInsert = resolveInsert,
    )
