package com.ethran.notable.utils

import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import com.ethran.notable.db.Stroke
import com.onyx.android.sdk.data.note.TouchPoint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

fun RectF.expandBy(amount: Float): RectF {
    return RectF(
        left - amount, top - amount, right + amount, bottom + amount
    )
}

fun Rect.takeTopLeftCornel(): IntOffset {
    return IntOffset(left, top)
}

fun Offset.toIntOffset(): IntOffset = IntOffset(x.toInt(), y.toInt())

fun Offset.floorToIntOffset(): IntOffset = IntOffset(floor(x).toInt(), floor(y).toInt())
fun Offset.ceilToIntOffset(): IntOffset = IntOffset(ceil(x).toInt(), ceil(y).toInt())


// Rect - IntOffset
operator fun Rect.minus(offset: IntOffset): Rect =
    Rect(left - offset.x, top - offset.y, right - offset.x, bottom - offset.y)

// Rect - Offset (rounds to nearest Int)
operator fun Rect.minus(offset: Offset): Rect {
    val dx = offset.x.roundToInt()
    val dy = offset.y.roundToInt()
    return Rect(left - dx, top - dy, right - dx, bottom - dy)
}

// Rect - Offset (rounds to nearest Int)
operator fun Rect.plus(offset: Offset): Rect {
    val dx = offset.x.roundToInt()
    val dy = offset.y.roundToInt()
    return Rect(left + dx, top + dy, right + dx, bottom + dy)
}

// RectF - IntOffset
operator fun RectF.minus(offset: IntOffset): RectF =
    RectF(left - offset.x, top - offset.y, right - offset.x, bottom - offset.y)

// RectF - Offset
operator fun RectF.minus(offset: Offset): RectF =
    RectF(left - offset.x, top - offset.y, right - offset.x, bottom - offset.y)

operator fun Rect.div(arg: Float): Rect = scaleRect(this, arg)
operator fun Rect.times(arg: Float): Rect = scaleRect(this, 1 / arg)


fun <T> calculateBoundingBox(
    touchPoints: List<T>, getCoordinates: (T) -> Pair<Float, Float>
): RectF {
    require(touchPoints.isNotEmpty()) { "touchPoints cannot be empty" }

    val (startX, startY) = getCoordinates(touchPoints[0])
    val boundingBox = RectF(startX, startY, startX, startY)

    for (point in touchPoints) {
        val (x, y) = getCoordinates(point)
        boundingBox.union(x, y)
    }

    return boundingBox
}


fun strokeToTouchPoints(stroke: Stroke): List<TouchPoint> {
    return stroke.points.map {
        TouchPoint(
            it.x, it.y, it.pressure, stroke.size, it.tiltX, it.tiltY, it.timestamp
        )
    }
}
