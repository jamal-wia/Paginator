package com.jamal_aliev.paginator.core.cache.reactive

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
 * Unit tests for [SnapshotReactiveCache] / [snapshotReactiveCache]: they assert
 * the exact [ReactiveEvent]s produced when diffing a finite sequence of
 * snapshots, without involving a real paginator. End-to-end CRUD application is
 * covered by the offset module's `SnapshotReactiveObserveTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotReactiveCacheTest {

    private data class Item(val id: Int, val label: String)

    /** Collects every event the diff emits for the given snapshot [sequence]. */
    private suspend fun events(
        vararg sequence: List<Item>,
        areContentsTheSame: (Item, Item) -> Boolean = { a, b -> a == b },
        reorderPolicy: ReorderPolicy = ReorderPolicy.Invalidate,
        resolveInsert: (Item, Int?) -> InsertPosition<Int>? = { _, left ->
            if (left == null) InsertPosition.Head else InsertPosition.AfterIdentity(left)
        },
    ): List<ReactiveEvent<Item, Int>> {
        val source = snapshotReactiveCache(
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
            listOf(ReactiveEvent.Inserted(a, InsertPosition.Head)),
            events(listOf(b, c), listOf(a, b, c)),
        )
    }

    @Test
    fun `insert in the middle anchors after the left neighbour`() = runTest {
        assertEquals(
            listOf(ReactiveEvent.Inserted(b, InsertPosition.AfterIdentity(a.id))),
            events(listOf(a, c), listOf(a, b, c)),
        )
    }

    @Test
    fun `insert at tail anchors after the last item`() = runTest {
        assertEquals(
            listOf(ReactiveEvent.Inserted(c, InsertPosition.AfterIdentity(b.id))),
            events(listOf(a, b), listOf(a, b, c)),
        )
    }

    @Test
    fun `removal emits a single Removed and is not a reorder`() = runTest {
        val result = events(listOf(a, b, c), listOf(a, c))
        assertEquals(listOf(ReactiveEvent.Removed(b.id)), result)
    }

    @Test
    fun `content change emits Updated`() = runTest {
        val edited = b.copy(label = "patched")
        assertEquals(
            listOf(ReactiveEvent.Updated(edited)),
            events(listOf(a, b, c), listOf(a, edited, c)),
        )
    }

    @Test
    fun `areContentsTheSame can suppress a no-op update`() = runTest {
        val edited = b.copy(label = "patched")
        // Treat all same-identity items as equal → the content edit is ignored.
        val result = events(
            listOf(a, b, c),
            listOf(a, edited, c),
            areContentsTheSame = { _, _ -> true },
        )
        assertEquals(emptyList(), result)
    }

    @Test
    fun `multiple changes are wrapped in a single Batch`() = runTest {
        // b removed, d appended.
        val result = events(listOf(a, b, c), listOf(a, c, d))
        assertEquals(
            listOf(
                ReactiveEvent.Batch(
                    listOf(
                        ReactiveEvent.Removed(b.id),
                        ReactiveEvent.Inserted(d, InsertPosition.AfterIdentity(c.id)),
                    ),
                ),
            ),
            result,
        )
    }

    @Test
    fun `reorder defaults to a single Invalidate`() = runTest {
        val result = events(listOf(a, b, c), listOf(c, b, a))
        assertEquals(listOf(ReactiveEvent.Invalidate), result)
    }

    @Test
    fun `reorder with concurrent edits still collapses to Invalidate`() = runTest {
        val edited = b.copy(label = "patched")
        val result = events(listOf(a, b, c), listOf(c, edited, a))
        assertEquals(listOf(ReactiveEvent.Invalidate), result)
    }

    @Test
    fun `reorder under Moves policy moves only the minimal set`() = runTest {
        // [a, b, c] -> [a, c, b] : the stable subsequence is [a, c], only b moves.
        val result = events(
            listOf(a, b, c),
            listOf(a, c, b),
            reorderPolicy = ReorderPolicy.Moves,
        )
        assertEquals(
            listOf(ReactiveEvent.Moved(b, InsertPosition.AfterIdentity(c.id))),
            result,
        )
    }

    @Test
    fun `full reversal under Moves policy moves all but the anchor`() = runTest {
        // [a, b, c, d] -> [d, c, b, a]. The LCS of a reversal has length 1, so
        // exactly one survivor stays as the anchor and the other three move.
        val result = events(
            listOf(a, b, c, d),
            listOf(d, c, b, a),
            reorderPolicy = ReorderPolicy.Moves,
        )
        val batch = assertIs<ReactiveEvent.Batch<Item, Int>>(result.single())
        val moves = batch.events
        assertTrue(moves.all { it is ReactiveEvent.Moved })
        val movedIds = moves.map { (it as ReactiveEvent.Moved).item.id }.toSet()
        // Three of the four ids move; which one is the anchor depends on the LCS.
        assertEquals(3, movedIds.size)
        assertTrue(movedIds.all { it in setOf(a.id, b.id, c.id, d.id) })
    }

    @Test
    fun `resolveInsert can override the insert position`() = runTest {
        // Always Head, ignoring the snapshot neighbour.
        val result = events(
            listOf(a, c),
            listOf(a, b, c),
            resolveInsert = { _, _ -> InsertPosition.Head },
        )
        assertEquals(listOf(ReactiveEvent.Inserted(b, InsertPosition.Head)), result)
    }

    @Test
    fun `resolveInsert returning null vetoes the insert`() = runTest {
        val result = events(
            listOf(a, c),
            listOf(a, b, c),
            resolveInsert = { _, _ -> null },
        )
        assertEquals(emptyList(), result)
    }

    @Test
    fun `a vetoed insert does not become the anchor for the next insert`() = runTest {
        // b is vetoed; c must anchor after a (id 1), not after the absent b.
        val result = events(
            listOf(a),
            listOf(a, b, c),
            resolveInsert = { item, left ->
                when {
                    item.id == b.id -> null
                    left == null -> InsertPosition.Head
                    else -> InsertPosition.AfterIdentity(left)
                }
            },
        )
        assertEquals(
            listOf(ReactiveEvent.Inserted(c, InsertPosition.AfterIdentity(a.id))),
            result,
        )
    }

    @Test
    fun `cold flow re-establishes its own baseline on re-collection`() = runTest {
        val source: PaginatorReactiveCache<Item, Int> = snapshotReactiveCache(
            snapshots = flowOf(listOf(a, b), listOf(a, b, c)),
            identity = { it.id },
        )
        val changes: Flow<ReactiveEvent<Item, Int>> = source.changes()
        val first = changes.toList()
        val second = changes.toList()
        val expected = listOf(ReactiveEvent.Inserted(c, InsertPosition.AfterIdentity(b.id)))
        assertEquals(expected, first)
        assertEquals(expected, second)
    }
}
