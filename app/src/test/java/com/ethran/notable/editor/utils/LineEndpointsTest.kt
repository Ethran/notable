package com.ethran.notable.editor.utils

import androidx.compose.ui.geometry.Offset
import com.onyx.android.sdk.data.note.TouchPoint
import org.junit.Assert.assertTrue
import org.junit.Test

class LineEndpointsTest {

    private fun touch(x: Float, pressure: Float) = TouchPoint().apply {
        this.x = x
        this.y = 0f
        this.pressure = pressure
    }

    @Test
    fun line_pressure_is_normalized() {
        val raw = List(20) { touch(it.toFloat(), 2000f + it) }
        val (start, end) = getModifiedStrokeEndpoints(raw, Offset.Zero, 1f)
        val line = transformToLine(start, end)
        assertTrue(line.all { it.pressure!! in 0f..1f })
    }
}
