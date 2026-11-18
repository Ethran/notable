package com.ethran.notable.editor.canvas.rectify

import com.onyx.android.sdk.data.note.TouchPoint
import kotlin.math.hypot

/**
 * A pen position in screen pixels, with the float precision the pen SDK reports at.
 *
 * The engine takes these instead of the SDK's `TouchPoint` so a JVM test can build one; the adapter
 * converts at the boundary. Unlike `TouchPoint`, which the SDK recycles while a gesture is still
 * running, a [Point2D] is immutable and safe to hold across pen-up.
 *
 * [pressure] is the raw digitizer value the SDK reported, carried untouched so a rectified line
 * keeps the pressure of the pen that drew it. The engine never reads it — dwell and band geometry
 * are position only — so it stays 0 in tests.
 */
data class Point2D(
    val x: Float,
    val y: Float,
    val pressure: Float = 0f,
    val tiltX: Int = 0,
    val tiltY: Int = 0,
) {

    /** Distance from this to [other], the same measure the dwell detector uses. */
    fun distanceTo(other: Point2D): Float = hypot(x - other.x, y - other.y)
}

/**
 * Reads the pen position out of an SDK [TouchPoint].
 *
 * This copies the two coordinates rather than the point, which is the whole reason the engine does
 * not take [TouchPoint]: the SDK recycles its points while a gesture is still running, so a held
 * [Point2D] survives to pen-up and a held [TouchPoint] does not.
 */
fun TouchPoint.toPoint2D(): Point2D = Point2D(
    x = x,
    y = y,
    pressure = pressure,
    tiltX = tiltX,
    tiltY = tiltY,
)
