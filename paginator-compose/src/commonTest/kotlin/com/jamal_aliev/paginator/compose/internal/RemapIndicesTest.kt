package com.jamal_aliev.paginator.compose.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemapIndicesTest {

    @Test
    fun `returns null when data item count is zero`() {
        assertNull(
            remapIndices(
                firstVisibleIndex = 0,
                lastVisibleIndex = 5,
                totalItemCount = 6,
                dataItemCount = 0,
                headerCount = 0,
            )
        )
    }

    @Test
    fun `returns null when total item count is zero`() {
        assertNull(
            remapIndices(
                firstVisibleIndex = 0,
                lastVisibleIndex = 0,
                totalItemCount = 0,
                dataItemCount = 10,
                headerCount = 0,
            )
        )
    }

    @Test
    fun `returns null when first visible is negative`() {
        assertNull(
            remapIndices(
                firstVisibleIndex = -1,
                lastVisibleIndex = 5,
                totalItemCount = 10,
                dataItemCount = 10,
                headerCount = 0,
            )
        )
    }

    @Test
    fun `returns null when last visible is negative empty viewport`() {
        assertNull(
            remapIndices(
                firstVisibleIndex = 0,
                lastVisibleIndex = -1,
                totalItemCount = 10,
                dataItemCount = 10,
                headerCount = 0,
            )
        )
    }

    @Test
    fun `returns null when first is greater than last`() {
        assertNull(
            remapIndices(
                firstVisibleIndex = 7,
                lastVisibleIndex = 3,
                totalItemCount = 10,
                dataItemCount = 10,
                headerCount = 0,
            )
        )
    }

    @Test
    fun `identity remap when no headers and no footers`() {
        val r = remapIndices(
            firstVisibleIndex = 3,
            lastVisibleIndex = 8,
            totalItemCount = 10,
            dataItemCount = 10,
            headerCount = 0,
        )
        assertEquals(
            RemappedScroll(firstVisibleIndex = 3, lastVisibleIndex = 8, totalItemCount = 10),
            r,
        )
    }

    @Test
    fun `shifts indices down by header count`() {
        val r = remapIndices(
            firstVisibleIndex = 5,
            lastVisibleIndex = 9,
            totalItemCount = 13,
            dataItemCount = 10,
            headerCount = 2,
        )
        assertEquals(
            RemappedScroll(firstVisibleIndex = 3, lastVisibleIndex = 7, totalItemCount = 10),
            r,
        )
    }

    @Test
    fun `clamps first to zero when viewport spans header into data`() {
        val r = remapIndices(
            firstVisibleIndex = 0,
            lastVisibleIndex = 4,
            totalItemCount = 13,
            dataItemCount = 10,
            headerCount = 2,
        )
        assertEquals(
            RemappedScroll(firstVisibleIndex = 0, lastVisibleIndex = 2, totalItemCount = 10),
            r,
        )
    }

    @Test
    fun `clamps last to data end when viewport spans data into footer`() {
        val r = remapIndices(
            firstVisibleIndex = 8,
            lastVisibleIndex = 12,
            totalItemCount = 13,
            dataItemCount = 10,
            headerCount = 0,
        )
        assertEquals(
            RemappedScroll(firstVisibleIndex = 8, lastVisibleIndex = 9, totalItemCount = 10),
            r,
        )
    }

    @Test
    fun `clamps both ends when viewport contains everything`() {
        val r = remapIndices(
            firstVisibleIndex = 0,
            lastVisibleIndex = 12,
            totalItemCount = 13,
            dataItemCount = 10,
            headerCount = 2,
        )
        assertEquals(
            RemappedScroll(firstVisibleIndex = 0, lastVisibleIndex = 9, totalItemCount = 10),
            r,
        )
    }

    @Test
    fun `returns null when viewport is entirely inside headers`() {
        assertNull(
            remapIndices(
                firstVisibleIndex = 0,
                lastVisibleIndex = 1,
                totalItemCount = 13,
                dataItemCount = 10,
                headerCount = 2,
            )
        )
    }

    @Test
    fun `returns null when viewport is entirely inside footers`() {
        assertNull(
            remapIndices(
                firstVisibleIndex = 10,
                lastVisibleIndex = 12,
                totalItemCount = 13,
                dataItemCount = 10,
                headerCount = 0,
            )
        )
    }

    @Test
    fun `last visible exactly at last header is treated as headers-only`() {
        assertNull(
            remapIndices(
                firstVisibleIndex = 0,
                lastVisibleIndex = 1,
                totalItemCount = 13,
                dataItemCount = 10,
                headerCount = 2,
            )
        )
    }

    @Test
    fun `first visible exactly at first footer is treated as footers-only`() {
        assertNull(
            remapIndices(
                firstVisibleIndex = 10,
                lastVisibleIndex = 10,
                totalItemCount = 11,
                dataItemCount = 10,
                headerCount = 0,
            )
        )
    }

    @Test
    fun `single visible data item with headers`() {
        val r = remapIndices(
            firstVisibleIndex = 5,
            lastVisibleIndex = 5,
            totalItemCount = 13,
            dataItemCount = 10,
            headerCount = 2,
        )
        assertEquals(
            RemappedScroll(firstVisibleIndex = 3, lastVisibleIndex = 3, totalItemCount = 10),
            r,
        )
    }
}
