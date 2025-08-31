package com.ethran.notable.utils

import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.util.TypedValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.toRect
import androidx.core.graphics.toRegion
import com.ethran.notable.TAG
import com.ethran.notable.data.datastore.SimplePointF
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.calculateBoundingBox
import com.onyx.android.sdk.data.note.TouchPoint
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn


fun Modifier.noRippleClickable(
    onClick: () -> Unit
): Modifier = composed {
    clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
        onClick()
    }
}


fun convertDpToPixel(dp: Dp, context: Context): Float {
//    val resources = context.resources
//    val metrics: DisplayMetrics = resources.displayMetrics
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp.value,
        context.resources.displayMetrics
    )
}


fun <T : Any> Flow<T>.withPrevious(): Flow<Pair<T?, T>> = flow {
    var prev: T? = null
    this@withPrevious.collect {
        emit(prev to it)
        prev = it
    }
}

fun pointsToPath(points: List<SimplePointF>): Path {
    val path = Path()
    val prePoint = PointF(points[0].x, points[0].y)
    path.moveTo(prePoint.x, prePoint.y)

    for (point in points) {
        // skip strange jump point.
        //if (abs(prePoint.y - point.y) >= 30) continue
        path.quadTo(prePoint.x, prePoint.y, point.x, point.y)
        prePoint.x = point.x
        prePoint.y = point.y
    }
    return path
}


fun scaleRect(rect: Rect, scale: Float): Rect {
    return Rect(
        (rect.left / scale).toInt(),
        (rect.top / scale).toInt(),
        (rect.right / scale).toInt(),
        (rect.bottom / scale).toInt()
    )
}

fun toPageCoordinates(rect: Rect, scale: Float, scroll: Offset): Rect {
    return Rect(
        (rect.left.toFloat() / scale + scroll.x).toInt(),
        (rect.top.toFloat() / scale + scroll.y).toInt(),
        (rect.right.toFloat() / scale + scroll.x).toInt(),
        (rect.bottom.toFloat() / scale + scroll.y).toInt()
    )
}

fun copyInput(touchPoints: List<TouchPoint>, scroll: Offset, scale: Float): List<StrokePoint> {
    val points = touchPoints.map {
        it.toStrokePoint(scroll, scale)
    }
    return points
}

fun TouchPoint.toStrokePoint(scroll: Offset, scale: Float): StrokePoint {
    return StrokePoint(
        x = x / scale + scroll.x,
        y = y / scale + scroll.y,
        pressure = pressure,
        size = size,
        tiltX = tiltX,
        tiltY = tiltY,
        timestamp = timestamp,
    )
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

//fun pageAreaToCanvasArea(pageArea: Rect, scroll: Int, scale: Float = 1f): Rect {
//    return scaleRect(
//        Rect(
//            pageArea.left, pageArea.top - scroll, pageArea.right, pageArea.bottom - scroll
//        ), scale
//    )
//}

fun strokeBounds(stroke: Stroke): RectF {
    return RectF(
        stroke.left, stroke.top, stroke.right, stroke.bottom
    )
}

fun imageBounds(image: Image): RectF {
    return RectF(
        image.x.toFloat(),
        image.y.toFloat(),
        image.x + image.width.toFloat(),
        image.y + image.height.toFloat()
    )
}

fun imagePoints(image: Image): Array<Point> {
    return arrayOf(
        Point(image.x, image.y),
        Point(image.x, image.y + image.height),
        Point(image.x + image.width, image.y),
        Point(image.x + image.width, image.y + image.height),
    )
}

fun strokeBounds(strokes: List<Stroke>): Rect {
    if (strokes.isEmpty()) return Rect()
    val stroke = strokes[0]
    val rect = Rect(
        stroke.left.toInt(), stroke.top.toInt(), stroke.right.toInt(), stroke.bottom.toInt()
    )
    strokes.forEach {
        rect.union(
            Rect(
                it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()
            )
        )
    }
    return rect
}

fun imageBoundsInt(image: Image, padding: Int = 0): Rect {
    return Rect(
        image.x + padding,
        image.y + padding,
        image.x + image.width + padding,
        image.y + image.height + padding
    )
}

fun imageBoundsInt(images: List<Image>): Rect {
    if (images.isEmpty()) return Rect()
    val rect = imageBoundsInt(images[0])
    images.forEach {
        rect.union(
            imageBoundsInt(it)
        )
    }
    return rect
}


fun pathToRegion(path: Path): Region {
    val bounds = RectF()
    // TODO: it deprecated, find replacement.
    path.computeBounds(bounds, true)
    val region = Region()
    region.setPath(
        path,
        bounds.toRegion()
    )
    return region
}

fun divideStrokesFromCut(
    strokes: List<Stroke>,
    cutLine: List<SimplePointF>
): Pair<List<Stroke>, List<Stroke>> {
    val maxY = cutLine.maxOfOrNull { it.y }
    val cutArea = listOf(SimplePointF(0f, maxY!!)) + cutLine + listOf(
        SimplePointF(
            cutLine.last().x,
            maxY
        )
    )
    val cutPath = pointsToPath(cutArea)
    cutPath.close()

    val bounds = RectF().apply {
        cutPath.computeBounds(this, true)
    }
    val cutRegion = pathToRegion(cutPath)

    val strokesOver: MutableList<Stroke> = mutableListOf()
    val strokesUnder: MutableList<Stroke> = mutableListOf()

    strokes.forEach { stroke ->
        if (stroke.top > bounds.bottom) strokesUnder.add(stroke)
        else if (stroke.bottom < bounds.top) strokesOver.add(stroke)
        else {
            if (stroke.points.any { point ->
                    cutRegion.contains(
                        point.x.toInt(),
                        point.y.toInt()
                    )
                }) strokesUnder.add(stroke)
            else strokesOver.add(stroke)
        }
    }

    return strokesOver to strokesUnder
}

fun offsetStroke(stroke: Stroke, offset: Offset): Stroke {
    return stroke.copy(
        points = stroke.points.map { p -> p.copy(x = p.x + offset.x, y = p.y + offset.y) },
        top = stroke.top + offset.y,
        bottom = stroke.bottom + offset.y,
        left = stroke.left + offset.x,
        right = stroke.right + offset.x,
    )
}

fun offsetImage(image: Image, offset: Offset): Image {
    return image.copy(
        x = image.x + offset.x.toInt(),
        y = image.y + offset.y.toInt(),
        height = image.height,
        width = image.width,
        uri = image.uri,
        pageId = image.pageId
    )
}

fun getModifiedStrokeEndpoints(
    points: List<TouchPoint>,
    scroll: Offset,
    zoomLevel: Float
): Pair<StrokePoint, StrokePoint> {
    if (points.isEmpty()) throw IllegalArgumentException("points list is empty")

    val startIdx = points.size / 10
    val endIdx = (9 * points.size) / 10

    val baseStartPoint = points.first().toStrokePoint(scroll, zoomLevel)
    val baseEndPoint = points.last().toStrokePoint(scroll, zoomLevel)

    val startPoint = baseStartPoint.copy(
        tiltX = points[startIdx].tiltX,
        tiltY = points[startIdx].tiltY,
        pressure = points[startIdx].pressure
    )

    val endPoint = baseEndPoint.copy(
        tiltX = points[endIdx].tiltX,
        tiltY = points[endIdx].tiltY,
        pressure = points[endIdx].pressure
    )

    return Pair(startPoint, endPoint)
}


fun logCallStack(reason: String, n: Int = 8) {
    val stackTrace = Thread.currentThread().stackTrace
        .drop(3) // Skip internal calls
        .take(n) // Limit depth
        .joinToString("\n") {
            "${it.className.removePrefix("com.ethran.notable.")}.${it.methodName} (${it.fileName}:${it.lineNumber})"
        }
    Log.w(TAG, "$reason Call stack:\n$stackTrace")
}

// Helper function to achieve time-based chunking
fun <T> Flow<T>.chunked(timeoutMillisSelector: Long): Flow<List<T>> = flow {
    val buffer = mutableListOf<T>()
    coroutineScope {
        val channel = produceIn(this)
        while (true) {
            val start = System.currentTimeMillis()
            val received = channel.receiveCatching().getOrNull() ?: break
            buffer.add(received)

            while (System.currentTimeMillis() - start < timeoutMillisSelector) {
                val next = channel.tryReceive().getOrNull() ?: continue
                buffer.add(next)
            }
            emit(buffer.toList())
            buffer.clear()
        }
    }
}
