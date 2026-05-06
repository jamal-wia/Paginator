package com.jamal_aliev.paginator.prefetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisibleDataRangeTest {

    @Test
    fun `returns NONE when data item count is zero`() {
        assertTrue(
            VisibleDataRange.from(
                firstVisibleIndex = 0,
                lastVisibleIndex = 5,
                dataItemCount = 0,
                dataOffset = 0,
            ).isNone
        )
    }

    @Test
    fun `returns NONE when first visible is negative`() {
        assertTrue(
            VisibleDataRange.from(
                firstVisibleIndex = -1,
                lastVisibleIndex = 5,
                dataItemCount = 10,
                dataOffset = 0,
            ).isNone
        )
    }

    @Test
    fun `returns NONE when last visible is negative empty viewport`() {
        assertTrue(
            VisibleDataRange.from(
                firstVisibleIndex = 0,
                lastVisibleIndex = -1,
                dataItemCount = 10,
                dataOffset = 0,
            ).isNone
        )
    }

    @Test
    fun `returns NONE when first is greater than last`() {
        assertTrue(
            VisibleDataRange.from(
                firstVisibleIndex = 7,
                lastVisibleIndex = 3,
                dataItemCount = 10,
                dataOffset = 0,
            ).isNone
        )
    }

    @Test
    fun `identity remap when no prefix or suffix offset`() {
        val r = VisibleDataRange.from(
            firstVisibleIndex = 3,
            lastVisibleIndex = 8,
            dataItemCount = 10,
            dataOffset = 0,
        )
        assertEquals(VisibleDataRange(firstVisibleIndex = 3, lastVisibleIndex = 8), r)
    }

    @Test
    fun `shifts indices down by data offset`() {
        val r = VisibleDataRange.from(
            firstVisibleIndex = 5,
            lastVisibleIndex = 9,
            dataItemCount = 10,
            dataOffset = 2,
        )
        assertEquals(VisibleDataRange(firstVisibleIndex = 3, lastVisibleIndex = 7), r)
    }

    @Test
    fun `clamps first to zero when viewport spans prefix into data`() {
        val r = VisibleDataRange.from(
            firstVisibleIndex = 0,
            lastVisibleIndex = 4,
            dataItemCount = 10,
            dataOffset = 2,
        )
        assertEquals(VisibleDataRange(firstVisibleIndex = 0, lastVisibleIndex = 2), r)
    }

    @Test
    fun `clamps last to data end when viewport spans data into suffix`() {
        val r = VisibleDataRange.from(
            firstVisibleIndex = 8,
            lastVisibleIndex = 12,
            dataItemCount = 10,
            dataOffset = 0,
        )
        assertEquals(VisibleDataRange(firstVisibleIndex = 8, lastVisibleIndex = 9), r)
    }

    @Test
    fun `clamps both ends when viewport contains everything`() {
        val r = VisibleDataRange.from(
            firstVisibleIndex = 0,
            lastVisibleIndex = 12,
            dataItemCount = 10,
            dataOffset = 2,
        )
        assertEquals(VisibleDataRange(firstVisibleIndex = 0, lastVisibleIndex = 9), r)
    }

    @Test
    fun `returns NONE when viewport is entirely inside prefix`() {
        assertTrue(
            VisibleDataRange.from(
                firstVisibleIndex = 0,
                lastVisibleIndex = 1,
                dataItemCount = 10,
                dataOffset = 2,
            ).isNone
        )
    }

    @Test
    fun `returns NONE when viewport is entirely inside suffix`() {
        assertTrue(
            VisibleDataRange.from(
                firstVisibleIndex = 10,
                lastVisibleIndex = 12,
                dataItemCount = 10,
                dataOffset = 0,
            ).isNone
        )
    }

    @Test
    fun `last visible exactly at last prefix item is treated as prefix-only`() {
        assertTrue(
            VisibleDataRange.from(
                firstVisibleIndex = 0,
                lastVisibleIndex = 1,
                dataItemCount = 10,
                dataOffset = 2,
            ).isNone
        )
    }

    @Test
    fun `first visible exactly at first suffix item is treated as suffix-only`() {
        assertTrue(
            VisibleDataRange.from(
                firstVisibleIndex = 10,
                lastVisibleIndex = 10,
                dataItemCount = 10,
                dataOffset = 0,
            ).isNone
        )
    }

    @Test
    fun `single visible data item with prefix offset`() {
        val r = VisibleDataRange.from(
            firstVisibleIndex = 5,
            lastVisibleIndex = 5,
            dataItemCount = 10,
            dataOffset = 2,
        )
        assertEquals(VisibleDataRange(firstVisibleIndex = 3, lastVisibleIndex = 3), r)
    }
}
