package com.jamal_aliev.paginator.interweave

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private data class Msg(val id: Int, val bucket: String)

private fun bucketWeaver(
    emitLeading: Boolean = false,
    emitTrailing: Boolean = false,
): Interweaver<Msg, String> = interweaver {
    this.emitLeading = emitLeading
    this.emitTrailing = emitTrailing
    itemKey { it.id }
    decorationKey { label, _, next -> "label-${next?.bucket ?: "tail"}-$label" }
    between { prev, next ->
        val p = prev?.bucket
        val n = next?.bucket
        when {
            prev == null && next != null -> n                       // leading edge
            next == null && prev != null -> "end-of-$p"             // trailing edge
            p != n && n != null -> n                                // bucket changed
            else -> null
        }
    }
}

class WeaveTest {

    @Test
    fun `empty list produces empty result`() {
        val result = emptyList<Msg>().weave(bucketWeaver())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single item with no edges produces a single Data entry`() {
        val result = listOf(Msg(1, "A")).weave(bucketWeaver())
        assertEquals(1, result.size)
        val only = result.single()
        assertIs<WovenEntry.Data<Msg>>(only)
        assertEquals(1, only.value.id)
        assertEquals(1, only.wovenKey)
    }

    @Test
    fun `all items in one bucket produce zero decorations`() {
        val result = listOf(Msg(1, "A"), Msg(2, "A"), Msg(3, "A")).weave(bucketWeaver())
        assertEquals(3, result.size)
        assertTrue(result.all { it is WovenEntry.Data<*> })
    }

    @Test
    fun `bucket change produces a between decoration`() {
        val result = listOf(Msg(1, "A"), Msg(2, "A"), Msg(3, "B")).weave(bucketWeaver())
        // Data(1), Data(2), Decoration(B), Data(3)
        assertEquals(4, result.size)
        assertIs<WovenEntry.Data<Msg>>(result[0])
        assertIs<WovenEntry.Data<Msg>>(result[1])
        val decoration = result[2]
        assertIs<WovenEntry.Decoration<String>>(decoration)
        assertEquals("B", decoration.value)
        assertIs<WovenEntry.Data<Msg>>(result[3])
    }

    @Test
    fun `multiple bucket changes produce multiple decorations`() {
        val result = listOf(Msg(1, "A"), Msg(2, "B"), Msg(3, "B"), Msg(4, "C"))
            .weave(bucketWeaver())
        // D(1), I(B), D(2), D(3), I(C), D(4)
        assertEquals(6, result.size)
        assertEquals(
            listOf("B", "C"), result.filterIsInstance<WovenEntry.Decoration<String>>()
                .map { it.value })
    }

    @Test
    fun `emitLeading emits an decoration before the first item`() {
        val result = listOf(Msg(1, "A"), Msg(2, "A"))
            .weave(bucketWeaver(emitLeading = true))
        assertIs<WovenEntry.Decoration<String>>(result[0])
        assertEquals("A", (result[0] as WovenEntry.Decoration<String>).value)
        assertIs<WovenEntry.Data<Msg>>(result[1])
        assertIs<WovenEntry.Data<Msg>>(result[2])
    }

    @Test
    fun `emitTrailing emits an decoration after the last item`() {
        val result = listOf(Msg(1, "A"), Msg(2, "A"))
            .weave(bucketWeaver(emitTrailing = true))
        val last = result.last()
        assertIs<WovenEntry.Decoration<String>>(last)
        assertEquals("end-of-A", last.value)
    }

    @Test
    fun `leading and trailing can coexist`() {
        val result = listOf(Msg(1, "A"))
            .weave(bucketWeaver(emitLeading = true, emitTrailing = true))
        assertEquals(3, result.size)
        assertIs<WovenEntry.Decoration<String>>(result[0])
        assertIs<WovenEntry.Data<Msg>>(result[1])
        assertIs<WovenEntry.Decoration<String>>(result[2])
    }

    @Test
    fun `leading is suppressed when between returns null for the leading edge`() {
        val weaver: Interweaver<Msg, String> = interweaver {
            emitLeading = true
            itemKey { it.id }
            decorationKey { s, _, _ -> s }
            between { prev, next ->
                if (prev == null) null                              // opt out of leading
                else if (prev.bucket != next?.bucket && next != null) next.bucket
                else null
            }
        }
        val result = listOf(Msg(1, "A"), Msg(2, "B")).weave(weaver)
        // No leading decoration, only the bucket-change one in the middle.
        assertEquals(3, result.size)
        assertIs<WovenEntry.Data<Msg>>(result[0])
        assertIs<WovenEntry.Decoration<String>>(result[1])
        assertIs<WovenEntry.Data<Msg>>(result[2])
    }

    @Test
    fun `stable keys are deterministic across two weaves of the same list`() {
        val items = listOf(Msg(1, "A"), Msg(2, "B"), Msg(3, "B"))
        val a = items.weave(bucketWeaver())
        val b = items.weave(bucketWeaver())
        assertEquals(a.map { it.wovenKey }, b.map { it.wovenKey })
    }

    @Test
    fun `stable keys are unique within a single emission`() {
        val items =
            (1..10).map { Msg(it, bucket = if (it <= 3) "A" else if (it <= 6) "B" else "C") }
        val result = items.weave(bucketWeaver(emitLeading = true, emitTrailing = true))
        val keys = result.map { it.wovenKey }
        assertEquals(keys.size, keys.toSet().size, "stable keys must be unique: $keys")
    }

    @Test
    fun `concatenating two page-sized chunks does not duplicate decorations at the seam`() {
        // Simulates page N ending on bucket A and page N+1 starting on bucket A:
        // a single flattened list must produce zero decorations at the seam (no "A|A" decoration).
        val pageN = listOf(Msg(1, "A"), Msg(2, "A"))
        val pageN1 = listOf(Msg(3, "A"), Msg(4, "B"))
        val result = (pageN + pageN1).weave(bucketWeaver())
        val decorations = result.filterIsInstance<WovenEntry.Decoration<String>>()
        assertEquals(1, decorations.size)
        assertEquals("B", decorations.single().value)
    }
}
