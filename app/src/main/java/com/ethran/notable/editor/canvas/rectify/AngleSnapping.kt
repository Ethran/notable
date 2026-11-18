package com.ethran.notable.editor.canvas.rectify

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rotates [b] about [a] onto the nearest axis, keeping its distance from [a] so the band's dirty
 * rect is unchanged by snapping. Returns [b] itself when no axis is within tolerance.
 */
internal fun snapBand(
    a: Point2D,
    b: Point2D,
    thresholds: RectifyThresholds,
): Point2D {
    if (!thresholds.snapEnabled) return b

    val dx = b.x - a.x
    val dy = b.y - a.y
    val length = sqrt(dx * dx + dy * dy)
    // No direction to snap, and normalising it would divide by zero.
    if (length <= 0f) return b

    val angle = atan2(dy, dx)
    val deviation = nearestAxisDeviation(angle, degreesToRadians(thresholds.snapStepDeg))
    if (abs(deviation) > degreesToRadians(thresholds.snapToleranceDeg)) return b

    val corrected = angle + deviation * thresholds.snapStrength
    return Point2D(
        x = a.x + cos(corrected) * length,
        y = a.y + sin(corrected) * length,
        pressure = b.pressure,
        tiltX = b.tiltX,
        tiltY = b.tiltY,
    )
}

/**
 * The signed pull from [angle] toward the nearest multiple of [stepRad]. Positive when the guide
 * lies above [angle], so adding it to [angle] reaches the guide.
 */
private fun nearestAxisDeviation(angle: Float, stepRad: Float): Float {
    if (stepRad <= 0f) return 0f
    return wrapToPi((angle / stepRad).roundToInt() * stepRad - angle)
}

/** Wraps [angle] into (-pi, pi], so a miss from 350° to 10° measures 20° rather than 340°. */
private fun wrapToPi(angle: Float): Float {
    var wrapped = angle
    while (wrapped > PI) wrapped -= TWO_PI
    while (wrapped <= -PI) wrapped += TWO_PI
    return wrapped
}

internal fun degreesToRadians(degrees: Int): Float = degrees * PI / 180f

private const val PI = Math.PI.toFloat()
private const val TWO_PI = 2f * PI