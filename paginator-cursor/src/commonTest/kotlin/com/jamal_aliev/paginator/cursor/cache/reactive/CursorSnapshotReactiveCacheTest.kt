package com.jamal_aliev.paginator.cursor.cache.reactive

import com.jamal_aliev.paginator.core.cache.reactive.ReorderPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Cursor-paginator counterpart of the core `SnapshotReactiveCacheTest`. Asserts
 * the exact [CursorReactiveEvent]s produced when diffing a finite sequence of
 * snapshots, without involving a real paginator. End-to-end CRUD application is
 * covered by `CursorSnapshotReactiveObserveTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CursorSnapshotReactiveCacheTest {

    private data class Item(val id: Int, val label: String)

    /** Collects every event the diff emits for the given snapshot [sequence]. */
    private suspend fun events(
        vararg sequence: List<Item>,
        areContentsTheSame: (Item, Item) -> Boolean = { a, b -> a == b },
        reorderPolicy: ReorderPolicy = ReorderPolicy.Invalidate,
        resolveInsert: (Item, Int?) -> CursorInsertPosition<Int>? = { _, left ->
            if (left == null) CursorInsertPosition.Head else CursorInsertPosition.AfterIdentity(left)
        },
    ): List<CursorReactiveEvent<Item, Int>> {
        val source = cursorSnapshotReactiveCache(
            snapshots = flowOf(*sequence),
            identity = { it.id },
            areContentsTheSame = areContentsTheSame,
            reorderPolicy = reorderPolicy,
            resolveInsert = resolveInsert,
        )
        return source.changes().toList()
    }

    private val a = Item(1, "a")
    private val b = Item(2, "b")
    private val c = Item(3, "c")
    private val d = Item(4, "d")

    @Test
    fun `first snapshot is a baseline and emits nothing`() = runTest {
        assertEquals(emptyList(), events(listOf(a, b, c)))
    }

    @Test
    fun `identical snapshots emit nothing`() = runTest {
        assertEquals(emptyList(), events(listOf(a, b, c), listOf(a, b, c)))
    }

    @Test
    fun `insert at head anchors to Head`() = runTest {
        assertEquals(
            listOf(CursorReactiveEvent.Inserted(a, CursorInsertPosition.Head)),
            events(listOf(b, c), listOf(a, b, c)),
        )
    }

    @Test
    fun `insert in the middle anchors after the left neighbour`() = runTest {
        assertEquals(
            listOf(CursorReactiveEvent.Inserted(b, CursorInsertPosition.AfterIdentity(a.id))),
            events(listOf(a, c), listOf(a, b, c)),
        )
    }

    @Test
    fun `insert at tail anchors after the last item`() = runTest {
        assertEquals(
            listOf(CursorReactiveEvent.Inserted(c, CursorInsertPosition.AfterIdentity(b.id))),
            events(listOf(a, b), listOf(a, b, c)),
        )
    }

    @Test
    fun `removal emits a single Removed and is not a reorder`() = runTest {
        assertEquals(
            listOf(CursorReactiveEvent.Removed(b.id)),
            events(listOf(a, b, c), listOf(a, c)),
        )
    }

    @Test
    fun `content change emits Updated`() = runTest {
        val edited = b.copy(label = "patched")
        assertEquals(
            listOf(CursorReactiveEvent.Updated(edited)),
            events(listOf(a, b, c), listOf(a, edited, c)),
        )
    }

    @Test
    fun `areContentsTheSame can suppress a no-op update`() = runTest {
        val edited = b.copy(label = "patched")
        assertEquals(
            emptyList(),
            events(
                listOf(a, b, c),
                listOf(a, edited, c),
                areContentsTheSame = { _, _ -> true },
            ),
        )
    }

    @Test
    fun `multiple changes are wrapped in a single Batch`() = runTest {
        // b removed, d appended.
        assertEquals(
            listOf(
                CursorReactiveEvent.Batch(
                    listOf(
                        CursorReactiveEvent.Removed(b.id),
                        CursorReactiveEvent.Inserted(d, CursorInsertPosition.AfterIdentity(c.id)),
                    ),
                ),
            ),
            events(listOf(a, b, c), listOf(a, c, d)),
        )
    }

    @Test
    fun `reorder defaults to a single Invalidate`() = runTest {
        assertEquals(
            listOf(CursorReactiveEvent.Invalidate),
            events(listOf(a, b, c), listOf(c, b, a)),
        )
    }

    @Test
    fun `reorder with concurrent edits still collapses to Invalidate`() = runTest {
        val edited = b.copy(label = "patched")
        assertEquals(
            listOf(CursorReactiveEvent.Invalidate),
            events(listOf(a, b, c), listOf(c, edited, a)),
        )
    }

    @Test
    fun `reorder under Moves policy moves only the minimal set`() = runTest {
        // [a, b, c] -> [a, c, b] : the stable subsequence is [a, c], only b moves.
        assertEquals(
            listOf(CursorReactiveEvent.Moved(b, CursorInsertPosition.AfterIdentity(c.id))),
            events(
                listOf(a, b, c),
                listOf(a, c, b),
                reorderPolicy = ReorderPolicy.Moves,
            ),
        )
    }

    @Test
    fun `full reversal under Moves policy moves all but the anchor`() = runTest {
        val result = events(
            listOf(a, b, c, d),
            listOf(d, c, b, a),
            reorderPolicy = ReorderPolicy.Moves,
        )
        val batch = assertIs<CursorReactiveEvent.Batch<Item, Int>>(result.single())
        val moves = batch.events
        assertTrue(moves.all { it is CursorReactiveEvent.Moved })
        val movedIds = moves.map { (it as CursorReactiveEvent.Moved).item.id }.toSet()
        assertEquals(3, movedIds.size)
        assertTrue(movedIds.all { it in setOf(a.id, b.id, c.id, d.id) })
    }

    @Test
    fun `resolveInsert can override the insert position`() = runTest {
        assertEquals(
            listOf(CursorReactiveEvent.Inserted(b, CursorInsertPosition.Head)),
            events(
                listOf(a, c),
                listOf(a, b, c),
                resolveInsert = { _, _ -> CursorInsertPosition.Head },
            ),
        )
    }

    @Test
    fun `resolveInsert returning null vetoes the insert`() = runTest {
        assertEquals(
            emptyList(),
            events(
                listOf(a, c),
                listOf(a, b, c),
                resolveInsert = { _, _ -> null },
            ),
        )
    }

    @Test
    fun `a vetoed insert does not become the anchor for the next insert`() = runTest {
        // b is vetoed; c must anchor after a (id 1), not after the absent b.
        assertEquals(
            listOf(CursorReactiveEvent.Inserted(c, CursorInsertPosition.AfterIdentity(a.id))),
            events(
                listOf(a),
                listOf(a, b, c),
                resolveInsert = { item, left ->
                    when {
                        item.id == b.id -> null
                        left == null -> CursorInsertPosition.Head
                        else -> CursorInsertPosition.AfterIdentity(left)
                    }
                },
            ),
        )
    }

    @Test
    fun `cold flow re-establishes its own baseline on re-collection`() = runTest {
        val source: CursorPaginatorReactiveCache<Item, Int> = cursorSnapshotReactiveCache(
            snapshots = flowOf(listOf(a, b), listOf(a, b, c)),
            identity = { it.id },
        )
        val changes: Flow<CursorReactiveEvent<Item, Int>> = source.changes()
        val expected =
            listOf(CursorReactiveEvent.Inserted(c, CursorInsertPosition.AfterIdentity(b.id)))
        assertEquals(expected, changes.toList())
        assertEquals(expected, changes.toList())
    }
}
