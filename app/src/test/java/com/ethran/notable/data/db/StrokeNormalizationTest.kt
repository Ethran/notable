package com.ethran.notable.data.db

import com.ethran.notable.editor.utils.Pen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Date

class StrokeNormalizationTest {

    private fun stroke(maxPressure: Int, points: List<StrokePoint>) = Stroke(
        id = "s-1",
        size = 3f,
        pen = Pen.FOUNTAIN,
        color = 0xFF000000.toInt(),
        maxPressure = maxPressure,
        top = 0f,
        bottom = 1f,
        left = 0f,
        right = 1f,
        points = points,
        pageId = "p-1",
        createdAt = Date(0),
        updatedAt = Date(0),
    )

    @Test
    fun legacy_raw_pressure_is_divided_by_maxPressure() {
        val s = stroke(
            maxPressure = 4096,
            points = listOf(
                StrokePoint(x = 0f, y = 0f, pressure = 0f),
                StrokePoint(x = 1f, y = 1f, pressure = 2048f),
                StrokePoint(x = 2f, y = 2f, pressure = 4096f),
            )
        ).withNormalizedPressure()

        assertEquals(MAX_PRESSURE_NORMALIZED, s.maxPressure)
        assertEquals(0f, s.points[0].pressure!!, 1e-6f)
        assertEquals(0.5f, s.points[1].pressure!!, 1e-6f)
        assertEquals(1f, s.points[2].pressure!!, 1e-6f)
    }

    @Test
    fun already_normalized_stroke_is_returned_unchanged() {
        val original = stroke(
            maxPressure = MAX_PRESSURE_NORMALIZED,
            points = listOf(StrokePoint(x = 0f, y = 0f, pressure = 0.7f))
        )
        assertSame(original, original.withNormalizedPressure())
    }

    @Test
    fun null_pressure_stays_null() {
        val s = stroke(
            maxPressure = 4096,
            points = listOf(StrokePoint(x = 0f, y = 0f, pressure = null))
        ).withNormalizedPressure()

        assertEquals(MAX_PRESSURE_NORMALIZED, s.maxPressure)
        assertNull(s.points[0].pressure)
    }

    @Test
    fun out_of_range_legacy_values_are_clamped() {
        val s = stroke(
            maxPressure = 4096,
            points = listOf(StrokePoint(x = 0f, y = 0f, pressure = 9000f))
        ).withNormalizedPressure()

        assertEquals(1f, s.points[0].pressure!!, 1e-6f)
    }

    @Test
    fun non_positive_maxPressure_only_updates_the_marker() {
        val s = stroke(
            maxPressure = 0,
            points = listOf(StrokePoint(x = 0f, y = 0f, pressure = 0.4f))
        ).withNormalizedPressure()

        assertEquals(MAX_PRESSURE_NORMALIZED, s.maxPressure)
        assertEquals(0.4f, s.points[0].pressure!!, 1e-6f)
    }
}
