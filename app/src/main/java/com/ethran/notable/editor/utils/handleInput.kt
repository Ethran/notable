package com.ethran.notable.editor.utils

import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.datastore.SimplePointF
import com.ethran.notable.data.db.StrokePoint
import com.onyx.android.sdk.data.note.TouchPoint


fun copyInput(touchPoints: List<TouchPoint>, scroll: Offset, scale: Float): List<StrokePoint> {
    val points = touchPoints.map {
        it.toStrokePoint(scroll, scale)
    }
    return points
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
        val pressure = lerp(startPoint.pressure, endPoint.pressure, fraction)
        val size = lerp(startPoint.size, endPoint.size, fraction)
        val tiltX = (lerp(startPoint.tiltX.toFloat(), endPoint.tiltX.toFloat(), fraction)).toInt()
        val tiltY = (lerp(startPoint.tiltY.toFloat(), endPoint.tiltY.toFloat(), fraction)).toInt()
        val timestamp = System.currentTimeMillis()

        StrokePoint(x, y, pressure, size, tiltX, tiltY, timestamp)
    }
    return (points2)
}