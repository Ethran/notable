package com.ethran.notable.editor.canvas.rectify

import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.utils.rawInputMaxPressure

/**
 * Maps a rectify endpoint from screen pixels into the page's coordinates, the same way a captured
 * pen point is mapped — see `TouchPoint.toStrokePoint`.
 *
 * Kept here rather than reusing that extension because the engine holds [Point2D], not the SDK's
 * `TouchPoint`, whose copy the SDK recycles mid-gesture. Pressure is normalised exactly as capture
 * does, so a rectified line is as thick and as light as the pen that drew it.
 */
fun Point2D.toPageStrokePoint(scroll: Offset, scale: Float): StrokePoint = StrokePoint(
    x = x / scale + scroll.x,
    y = y / scale + scroll.y,
    pressure = (pressure / rawInputMaxPressure).coerceIn(0f, 1f),
    tiltX = tiltX,
    tiltY = tiltY,
)
