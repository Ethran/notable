package com.ethran.notable.utils

import android.content.Context
import android.graphics.Paint
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
import com.ethran.notable.APP_SETTINGS_KEY
import com.ethran.notable.TAG
import com.ethran.notable.classes.AppRepository
import com.ethran.notable.classes.PageView
import com.ethran.notable.datastore.SimplePointF
import com.ethran.notable.db.Image
import com.ethran.notable.db.Stroke
import com.ethran.notable.db.StrokePoint
import com.ethran.notable.modals.AppSettings
import com.ethran.notable.modals.GlobalAppSettings
import com.onyx.android.sdk.data.note.TouchPoint
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

const val SCRIBBLE_TO_ERASE_GRACE_PERIOD_MS = 150L
const val SCRIBBLE_INTERSECTION_THRESHOLD = 0.20f

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

// TODO move this to repository
fun deletePage(context: Context, pageId: String) {
    val appRepository = AppRepository(context)
    val page = appRepository.pageRepository.getById(pageId) ?: return
    val proxy = appRepository.kvProxy
    val settings = proxy.get(APP_SETTINGS_KEY, AppSettings.serializer())


    runBlocking {
        // remove from book
        if (page.notebookId != null) {
            appRepository.bookRepository.removePage(page.notebookId, pageId)
        }

        // remove from quick nav
        if (settings != null && settings.quickNavPages.contains(pageId)) {
            proxy.setKv(
                APP_SETTINGS_KEY,
                settings.copy(quickNavPages = settings.quickNavPages - pageId),
                AppSettings.serializer()
            )
        }

        launch {
            appRepository.pageRepository.delete(pageId)
        }
        launch {
            val imgFile = File(context.filesDir, "pages/previews/thumbs/$pageId")
            if (imgFile.exists()) {
                imgFile.delete()
            }
        }
        launch {
            val imgFile = File(context.filesDir, "pages/previews/full/$pageId")
            if (imgFile.exists()) {
                imgFile.delete()
            }
        }

    }
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

// points is in page coordinates, returns effected area.
fun handleErase(
    page: PageView,
    history: History,
    points: List<SimplePointF>,
    eraser: Eraser
): Rect? {
    val paint = Paint().apply {
        this.strokeWidth = 30f
        this.style = Paint.Style.STROKE
        this.strokeCap = Paint.Cap.ROUND
        this.strokeJoin = Paint.Join.ROUND
        this.isAntiAlias = true
    }
    val path = pointsToPath(points)
    var outPath = Path()

    if (eraser == Eraser.SELECT) {
        path.close()
        outPath = path
    }


    if (eraser == Eraser.PEN) {
        paint.getFillPath(path, outPath)
    }

    val deletedStrokes = selectStrokesFromPath(page.strokes, outPath)

    val deletedStrokeIds = deletedStrokes.map { it.id }
    if (deletedStrokes.isEmpty()) return null
    page.removeStrokes(deletedStrokeIds)

    history.addOperationsToHistory(listOf(Operation.AddStroke(deletedStrokes)))

    val effectedArea = page.toScreenCoordinates(strokeBounds(deletedStrokes))
    page.drawAreaScreenCoordinates(screenArea = effectedArea)
    return effectedArea
}

enum class SelectPointPosition {
    LEFT,
    RIGHT,
    CENTER
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


// Filters strokes that significantly intersect with a given bounding box
fun filterStrokesByIntersection(
    candidateStrokes: List<Stroke>,
    boundingBox: RectF,
    threshold: Float = SCRIBBLE_INTERSECTION_THRESHOLD
): List<Stroke> {
    return candidateStrokes.filter { stroke ->
        val strokeRect = strokeBounds(stroke)
        val intersection = RectF()

        if (intersection.setIntersect(strokeRect, boundingBox)) {
            val strokeArea = strokeRect.width() * strokeRect.height()
            val intersectionArea = intersection.width() * intersection.height()
            val intersectionRatio = if (strokeArea > 0) intersectionArea / strokeArea else 0f

            intersectionRatio >= threshold
        } else {
            false
        }
    }
}

// Counts the number of direction changes (sharp reversals) in a stroke
fun calculateNumReversals(
    points: List<StrokePoint>,
    stepSize: Int = 10
): Int {
    var numReversals = 0
    for (i in 0 until points.size - 2 * stepSize step stepSize) {
        val p1 = points[i]
        val p2 = points[i + stepSize]
        val p3 = points[i + 2 * stepSize]
        val segment1 = SimplePointF(p2.x - p1.x, p2.y - p1.y)
        val segment2 = SimplePointF(p3.x - p2.x, p3.y - p2.y)
        val dotProduct = segment1.x * segment2.x + segment1.y * segment2.y
        // Reversal is detected when angle between segments > 90 degrees
        if (dotProduct < 0) {
            numReversals++
        }
    }
    return numReversals
}

// Calculates total stroke length using Manhattan distance
fun calculateStrokeLength(points: List<StrokePoint>): Float {
    var totalDistance = 0.0f
    for (i in 1 until points.size) {
        val dx = points[i].x - points[i - 1].x
        val dy = points[i].y - points[i - 1].y
        totalDistance += kotlin.math.abs(dx) + kotlin.math.abs(dy)
    }
    return totalDistance
}

const val MINIMUM_SCRIBBLE_POINTS = 15

// Erases strokes if touchPoints are "scribble", returns true if erased.
// returns null if not erased, dirty rectangle otherwise
fun handleScribbleToErase(
    page: PageView,
    touchPoints: List<StrokePoint>,
    history: History,
    pen: Pen,
    currentLastStrokeEndTime: Long
): Rect? {
    if (pen == Pen.MARKER)
        return null // do not erase with highlighter
    if (!GlobalAppSettings.current.scribbleToEraseEnabled)
        return null // scribble to erase is disabled
    if (touchPoints.size < MINIMUM_SCRIBBLE_POINTS)
        return null
    if (touchPoints.first().timestamp < currentLastStrokeEndTime + SCRIBBLE_TO_ERASE_GRACE_PERIOD_MS)
        return null // not enough time has passed since last stroke
    if (calculateNumReversals(touchPoints) < 2)
        return null

    val strokeLength = calculateStrokeLength(touchPoints)
    val boundingBox = calculateBoundingBox(touchPoints) { Pair(it.x, it.y) }
    val width = boundingBox.width()
    val height = boundingBox.height()
    if (width == 0f || height == 0f) return null

    // Require scribble to be long enough relative to bounding box
    val minLengthForScribble = (width + height) * 3
    if (strokeLength < minLengthForScribble) {
        Log.d("ScribbleToErase", "Stroke is too short, $strokeLength < $minLengthForScribble")
        return null
    }

    // calculate stroke width based on bounding box
    // bigger swinging in scribble = bigger bounding box => larger stroke size
    val minDim = kotlin.math.min(boundingBox.width(), boundingBox.height())
    val maxDim = kotlin.math.max(boundingBox.width(), boundingBox.height())
    val aspectRatio = if (minDim > 0) maxDim / minDim else 1f
    val scaleFactor = kotlin.math.min(1f + (aspectRatio - 1f) / 2f, 2f)
    val strokeSizeForDetection = minDim * 0.15f * scaleFactor


    // Get strokes that might intersect with the scribble path
    val path = pointsToPath(touchPoints.map { SimplePointF(it.x, it.y) })
    val outPath = Path()
    Paint().apply { this.strokeWidth = strokeSizeForDetection }.getFillPath(path, outPath)
    val candidateStrokes = selectStrokesFromPath(page.strokes, outPath)


    // Filter intersecting strokes based on intersection ratio
    val expandedBoundingBox = boundingBox.expandBy(strokeSizeForDetection / 2)
    val deletedStrokes = filterStrokesByIntersection(candidateStrokes, expandedBoundingBox)

    // If strokes were found, remove them and update history
    if (deletedStrokes.isNotEmpty()) {
        val deletedStrokeIds = deletedStrokes.map { it.id }
        page.removeStrokes(deletedStrokeIds)
        history.addOperationsToHistory(listOf(Operation.AddStroke(deletedStrokes)))
        val dirtyRect = strokeBounds(deletedStrokes)
        page.drawAreaPageCoordinates(dirtyRect)
        return dirtyRect
    }
    return null
}

// touchpoints are in page coordinates
fun handleDraw(
    page: PageView,
    historyBucket: MutableList<String>,
    strokeSize: Float,
    color: Int,
    pen: Pen,
    touchPoints: List<StrokePoint>
) {
    try {
        val boundingBox = calculateBoundingBox(touchPoints) { Pair(it.x, it.y) }

        //move rectangle
        boundingBox.inset(-strokeSize, -strokeSize)

        val stroke = Stroke(
            size = strokeSize,
            pen = pen,
            pageId = page.id,
            top = boundingBox.top,
            bottom = boundingBox.bottom,
            left = boundingBox.left,
            right = boundingBox.right,
            points = touchPoints,
            color = color
        )
        page.addStrokes(listOf(stroke))
        // this is causing lagging and crushing, neo pens are not good
        page.drawAreaPageCoordinates(strokeBounds(stroke).toRect())
        historyBucket.add(stroke.id)
    } catch (e: Exception) {
        Log.e(TAG, "Handle Draw: An error occurred while handling the drawing: ${e.message}")
    }
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

fun selectStrokesFromPath(strokes: List<Stroke>, path: Path): List<Stroke> {
    val bounds = RectF()
    path.computeBounds(bounds, true)

    //region is only 16 bit, so we need to move our region
    val translatedPath = Path(path)
    translatedPath.offset(0f, -bounds.top)
    val region = pathToRegion(translatedPath)

    return strokes.filter {
        strokeBounds(it).intersect(bounds)
    }.filter { it.points.any { region.contains(it.x.toInt(), (it.y - bounds.top).toInt()) } }
}

fun selectImagesFromPath(images: List<Image>, path: Path): List<Image> {
    val bounds = RectF()
    path.computeBounds(bounds, true)

    //region is only 16 bit, so we need to move our region
    val translatedPath = Path(path)
    translatedPath.offset(0f, -bounds.top)
    val region = pathToRegion(translatedPath)

    return images.filter {
        imageBounds(it).intersect(bounds)
    }.filter {
        // include image if all its corners are within region
        imagePoints(it).all { region.contains(it.x, (it.y - bounds.top).toInt()) }
    }
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