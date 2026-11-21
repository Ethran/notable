package com.ethran.notable.editor.canvas.rectify

import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.utils.rawInputMaxPressure

/**
 * Maps a rectify endpoint from screen pixels into page coordinates, pressure normalised the same way
 * `TouchPoint.toStrokePoint` does. Exists because the engine holds [Point2D], not a `TouchPoint`.
 */
fun Point2D.toPageStrokePoint(scroll: Offset, scale: Float): StrokePoint = StrokePoint(
    x = x / scale + scroll.x,
    y = y / scale + scroll.y,
    pressure = (pressure / rawInputMaxPressure).coerceIn(0f, 1f),
    tiltX = tiltX,
    tiltY = tiltY,
)
