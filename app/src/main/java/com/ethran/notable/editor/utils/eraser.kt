package com.ethran.notable.editor.utils

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.datastore.SimplePointF
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Operation
import io.shipbook.shipbooksdk.Log

enum class Eraser(val _name: String) {
    PEN("PEN"), SELECT("SELECT"),
}

const val SCRIBBLE_TO_ERASE_GRACE_PERIOD_MS = 150L
const val SCRIBBLE_INTERSECTION_THRESHOLD = 0.20f

const val MINIMUM_SCRIBBLE_POINTS = 15


// Calculates total stroke length using Manhattan distance
private fun calculateStrokeLength(points: List<StrokePoint>): Float {
    var totalDistance = 0.0f
    for (i in 1 until points.size) {
        val dx = points[i].x - points[i - 1].x
        val dy = points[i].y - points[i - 1].y
        totalDistance += kotlin.math.abs(dx) + kotlin.math.abs(dy)
    }
    return totalDistance
}

// Counts the number of direction changes (sharp reversals) in a stroke
private fun calculateNumReversals(
    points: List<StrokePoint>, stepSize: Int = 10
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


// Filters strokes that significantly intersect with a given bounding box
private fun filterStrokesByIntersection(
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

// Erases strokes if touchPoints are "scribble", returns true if erased.
// returns null if not erased, dirty rectangle otherwise
fun handleScribbleToErase(
    page: PageView,
    touchPoints: List<StrokePoint>,
    history: History,
    pen: Pen,
    currentLastStrokeEndTime: Long
): Rect? {
    if (pen == Pen.MARKER) return null // do not erase with highlighter
    if (!GlobalAppSettings.current.scribbleToEraseEnabled) return null // scribble to erase is disabled
    if (touchPoints.size < MINIMUM_SCRIBBLE_POINTS) return null
    if (touchPoints.first().timestamp < currentLastStrokeEndTime + SCRIBBLE_TO_ERASE_GRACE_PERIOD_MS) return null // not enough time has passed since last stroke
    if (calculateNumReversals(touchPoints) < 2) return null

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


// points is in page coordinates, returns effected area.
fun handleErase(
    page: PageView, history: History, points: List<SimplePointF>, eraser: Eraser
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