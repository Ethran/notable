package com.ethran.notable.editor.utils

import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.model.SimplePointF
import com.onyx.android.sdk.data.note.TouchPoint


// Max delta time (ms) that fits in the uint16 dt channel. 0xFFFF (65535) is reserved as a
// null sentinel by the SB1 stroke encoder, so the largest storable delta is 65534 ms
// (~65 s). Strokes longer than that clamp their tail to this value (acceptable: such
// durations only occur for pathological strokes). Keep in sync with
// StrokePointConverter.DT_MAX_VALUE_INT.
private const val DT_MAX_VALUE_MS = 65534L

fun copyInput(touchPoints: List<TouchPoint>, scroll: Offset, scale: Float): List<StrokePoint> {
    if (touchPoints.isEmpty()) return emptyList()
    // Capture per-point delta time (ms relative to the first point) into StrokePoint.dt so
    // it can be persisted. The firmware stamps each TouchPoint with an absolute timestamp;
    // we store only the delta, which is far cheaper (uint16 vs a per-point absolute long)
    // and is all that velocity-dependent renderers need. See StrokePoint.dt and the SB1
    // DT channel in StrokePointConverter.
    val baseTime = touchPoints.first().timestamp
    return touchPoints.map {
        val deltaMs = (it.timestamp - baseTime).coerceIn(0L, DT_MAX_VALUE_MS)
        it.toStrokePoint(scroll, scale).copy(dt = deltaMs.toUShort())
    }
}


fun copyInputToSimplePointF(
    touchPoints: List<TouchPoint>,
    scroll: Offset,
    scale: Float
): List<SimplePointF> {
    val points = touchPoints.map {
        SimplePointF(
            x = it.x / scale + scroll.x,
            y = (it.y / scale + scroll.y),
        )
    }
    return points
}


/*
* Gets list of points, and return line from first point to last.
* The line consist of 100 points, I do not know how it works (for 20 it want draw correctly)
 */
fun transformToLine(
    startPoint: StrokePoint,
    endPoint: StrokePoint,
): List<StrokePoint> {
    // Helper function to interpolate between two values
    fun lerp(start: Float, end: Float, fraction: Float) = start + (end - start) * fraction

    val numberOfPoints = 100 // Define how many points should line have
    val points2 = List(numberOfPoints) { i ->
        val fraction = i.toFloat() / (numberOfPoints - 1)

        val x = lerp(startPoint.x, endPoint.x, fraction)
        val y = lerp(startPoint.y, endPoint.y, fraction)

        val pressure = when {
            startPoint.pressure == null && endPoint.pressure == null -> null
            startPoint.pressure != null && endPoint.pressure != null ->
                lerp(startPoint.pressure, endPoint.pressure, fraction)

            else -> throw IllegalArgumentException(
                "Inconsistent pressure values: " +
                        "startPoint.pressure=${startPoint.pressure}, " +
                        "endPoint.pressure=${endPoint.pressure}. " +
                        "Both must be null or both must be non-null."
            )
        }

        val tiltX = when {
            startPoint.tiltX == null && endPoint.tiltX == null -> null
            startPoint.tiltX != null && endPoint.tiltX != null ->
                lerp(startPoint.tiltX.toFloat(), endPoint.tiltX.toFloat(), fraction).toInt()

            else ->
                throw IllegalArgumentException("startPoint.tiltX and endPoint.tiltX must either both be null or both non-null")
        }

        val tiltY = when {
            startPoint.tiltY == null && endPoint.tiltY == null -> null
            startPoint.tiltY != null && endPoint.tiltY != null ->
                lerp(startPoint.tiltY.toFloat(), endPoint.tiltY.toFloat(), fraction).toInt()

            else ->
                throw IllegalArgumentException("startPoint.tiltY and endPoint.tiltY must either both be null or both non-null")
        }

        StrokePoint(x, y, pressure, tiltX, tiltY)
    }
    return (points2)
}