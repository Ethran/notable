package com.ethran.notable.editor.utils

import android.graphics.Path
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.model.SimplePointF
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.state.EditorState
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.CoroutineScope
import kotlin.math.sqrt

// Minimum number of points required for a smart lasso stroke
const val MINIMUM_SMART_LASSO_POINTS = 20

// Maximum distance between first and last point to consider it a closed loop
// This is a percentage of the stroke's bounding box diagonal
const val CLOSED_LOOP_THRESHOLD_PERCENTAGE = 0.15f

// Minimum perimeter required to avoid accidental tiny loops
const val MINIMUM_PERIMETER_THRESHOLD = 100f

/**
 * Calculates the Euclidean distance between two points
 */
private fun distance(p1: StrokePoint, p2: StrokePoint): Float {
    val dx = p1.x - p2.x
    val dy = p1.y - p2.y
    return sqrt(dx * dx + dy * dy)
}

/**
 * Calculates the total perimeter length of a stroke path
 */
private fun calculatePerimeter(points: List<StrokePoint>): Float {
    var totalDistance = 0.0f
    for (i in 1 until points.size) {
        totalDistance += distance(points[i - 1], points[i])
    }
    return totalDistance
}

/**
 * Checks if a stroke forms a closed loop suitable for smart lasso selection
 */
private fun isClosedLoop(points: List<StrokePoint>): Boolean {
    if (points.size < MINIMUM_SMART_LASSO_POINTS) {
        Log.d("SmartLasso", "Too few points: ${points.size} < $MINIMUM_SMART_LASSO_POINTS")
        return false
    }

    val firstPoint = points.first()
    val lastPoint = points.last()

    // Calculate bounding box to determine if closure distance is reasonable
    val boundingBox = calculateBoundingBox(points) { Pair(it.x, it.y) }
    val boxWidth = boundingBox.width()
    val boxHeight = boundingBox.height()

    // Calculate diagonal of bounding box
    val diagonal = sqrt(boxWidth * boxWidth + boxHeight * boxHeight)

    // Check if first and last points are close enough (relative to stroke size)
    val closureDistance = distance(firstPoint, lastPoint)
    val closureThreshold = diagonal * CLOSED_LOOP_THRESHOLD_PERCENTAGE

    if (closureDistance > closureThreshold) {
        Log.d("SmartLasso", "Not closed: distance=$closureDistance, threshold=$closureThreshold")
        return false
    }

    // Check minimum perimeter to avoid tiny accidental loops
    val perimeter = calculatePerimeter(points)
    if (perimeter < MINIMUM_PERIMETER_THRESHOLD) {
        Log.d("SmartLasso", "Perimeter too small: $perimeter < $MINIMUM_PERIMETER_THRESHOLD")
        return false
    }

    // Additional check: ensure the stroke doesn't have too many sharp reversals
    // (which would indicate scribbling rather than deliberate loop drawing)
    // We allow some reversals (unlike scribble which requires many), but not too many
    val reversals = calculateNumReversalsForLasso(points)
    if (reversals > 8) { // Allow some natural hand movements but reject scribbles
        Log.d("SmartLasso", "Too many reversals: $reversals > 8 (likely scribble, not lasso)")
        return false
    }

    Log.d("SmartLasso", "Detected closed loop: points=${points.size}, perimeter=$perimeter, closure=$closureDistance")
    return true
}

/**
 * Calculates direction reversals but with different thresholds for lasso detection
 * Unlike scribble detection, we're looking for smooth loops, not back-and-forth motion
 */
private fun calculateNumReversalsForLasso(
    points: List<StrokePoint>,
    stepSize: Int = 8
): Int {
    var numReversals = 0
    for (i in 0 until points.size - 2 * stepSize step stepSize) {
        val p1 = points[i]
        val p2 = points[i + stepSize]
        val p3 = points[i + 2 * stepSize]
        val segment1 = SimplePointF(p2.x - p1.x, p2.y - p1.y)
        val segment2 = SimplePointF(p3.x - p2.x, p3.y - p2.y)
        val dotProduct = segment1.x * segment2.x + segment1.y * segment2.y
        // Count sharp reversals (angle > 120 degrees) instead of just > 90
        if (dotProduct < -0.5f * sqrt(
                (segment1.x * segment1.x + segment1.y * segment1.y) *
                (segment2.x * segment2.x + segment2.y * segment2.y)
            )) {
            numReversals++
        }
    }
    return numReversals
}

/**
 * Attempts to handle a stroke as a smart lasso selection.
 * Returns true if the stroke was handled as a smart lasso (and selection was triggered),
 * false if the stroke should be drawn normally.
 *
 * @param scope Coroutine scope for async operations
 * @param page Current page view
 * @param editorState Current editor state
 * @param touchPoints The stroke points to analyze (in page coordinates)
 * @return true if handled as smart lasso, false if should be drawn as regular stroke
 */
fun handleSmartLasso(
    scope: CoroutineScope,
    page: PageView,
    editorState: EditorState,
    touchPoints: List<StrokePoint>
): Boolean {
    // Check if feature is enabled
    if (!GlobalAppSettings.current.smartLassoEnabled) {
        return false
    }

    // Don't interfere with marker (highlighter) strokes
    if (editorState.pen == Pen.MARKER) {
        return false
    }

    // Check if this stroke forms a closed loop
    if (!isClosedLoop(touchPoints)) {
        return false
    }

    // Convert points to SimplePointF for handleSelect
    val selectionPoints = touchPoints.map { SimplePointF(it.x, it.y) }

    // Create selection path and find selected content
    val selectionPath = pointsToPath(selectionPoints)
    selectionPath.close()

    val selectedStrokes = selectStrokesFromPath(page.strokes, selectionPath)
    val selectedImages = selectImagesFromPath(page.images, selectionPath)

    // If nothing was selected, don't treat this as a smart lasso
    // (let it be drawn as a normal stroke instead)
    if (selectedStrokes.isEmpty() && selectedImages.isEmpty()) {
        Log.d("SmartLasso", "No content selected, drawing as regular stroke")
        return false
    }

    Log.i("SmartLasso", "Smart lasso triggered! Selected ${selectedStrokes.size} strokes and ${selectedImages.size} images")

    // Store the original stroke data (points + pen settings) so they can be drawn if user dismisses without using panel
    editorState.selectionState.pendingSmartLassoStroke = touchPoints
    editorState.selectionState.pendingSmartLassoPen = editorState.pen
    editorState.selectionState.pendingSmartLassoStrokeSize = editorState.penSettings[editorState.pen.penName]?.strokeSize
    editorState.selectionState.pendingSmartLassoColor = editorState.penSettings[editorState.pen.penName]?.color

    // Trigger selection
    selectImagesAndStrokes(scope, page, editorState, selectedImages, selectedStrokes)

    return true
}
