package com.ethran.notable.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokePointMaskTest {

    @Test
    fun mask_bit_helpers_isolate_each_flag() {
        // Pressure only
        assertTrue(0b0001.hasPressure())
        assertFalse(0b0001.hasTiltX())
        assertFalse(0b0001.hasTiltY())
        assertFalse(0b0001.hasDeltaTime())

        // Tilt X only
        assertFalse(0b0010.hasPressure())
        assertTrue(0b0010.hasTiltX())
        assertFalse(0b0010.hasTiltY())

        // dt only
        assertTrue(0b1000.hasDeltaTime())
        assertFalse(0b1000.hasPressure())

        // All four
        assertTrue(0b1111.hasPressure())
        assertTrue(0b1111.hasTiltX())
        assertTrue(0b1111.hasTiltY())
        assertTrue(0b1111.hasDeltaTime())

        // None
        assertFalse(0.hasPressure())
        assertFalse(0.hasTiltX())
        assertFalse(0.hasTiltY())
        assertFalse(0.hasDeltaTime())
    }

    @Test
    fun computeStrokeMask_derives_flags_from_first_point() {
        val pointAll = StrokePoint(x = 1f, y = 2f, pressure = 100f, tiltX = 1, tiltY = 2, dt = 5.toUShort())
        val maskAll = computeStrokeMask(listOf(pointAll))
        assertTrue(maskAll.hasPressure())
        assertTrue(maskAll.hasTiltX())
        assertTrue(maskAll.hasTiltY())
        assertTrue(maskAll.hasDeltaTime())

        val pointBare = StrokePoint(x = 1f, y = 2f)
        val maskBare = computeStrokeMask(listOf(pointBare))
        assertEquals(0, maskBare)

        val pointPressureOnly = StrokePoint(x = 1f, y = 2f, pressure = 50f)
        val maskPressureOnly = computeStrokeMask(listOf(pointPressureOnly))
        assertTrue(maskPressureOnly.hasPressure())
        assertFalse(maskPressureOnly.hasTiltX())
    }

    @Test(expected = IllegalArgumentException::class)
    fun computeStrokeMask_rejects_empty_list() {
        computeStrokeMask(emptyList())
    }

    @Test
    fun computeStrokeMask_ignores_fields_on_subsequent_points() {
        // Mask is derived strictly from the first point — divergence in later
        // points is a separate (encoder-side) validation concern, not a mask
        // computation concern.
        val points = listOf(
            StrokePoint(x = 0f, y = 0f, pressure = null),
            StrokePoint(x = 1f, y = 1f, pressure = 200f), // ignored for mask
        )
        assertEquals(0, computeStrokeMask(points))
    }
}
