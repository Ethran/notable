package com.ethran.notable.editor.canvas.rectify

import com.onyx.android.sdk.data.note.TouchPoint
import kotlin.math.hypot

/**
 * A pen position in screen pixels. The engine takes these instead of the SDK's `TouchPoint`, which
 * the SDK recycles mid-gesture, so a JVM test can build one and it survives to pen-up.
 *
 * [pressure] is the raw digitizer value, carried untouched; the engine never reads it.
 */
data class Point2D(
    val x: Float,
    val y: Float,
    val pressure: Float = 0f,
    val tiltX: Int = 0,
    val tiltY: Int = 0,
) {

    fun distanceTo(other: Point2D): Float = hypot(x - other.x, y - other.y)
}

/** Copies the position out of an SDK [TouchPoint] before the SDK recycles it. */
fun TouchPoint.toPoint2D(): Point2D = Point2D(
    x = x,
    y = y,
    pressure = pressure,
    tiltX = tiltX,
    tiltY = tiltY,
)
