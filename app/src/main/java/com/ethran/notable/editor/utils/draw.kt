package com.ethran.notable.editor.utils

import androidx.core.graphics.toRect
import com.ethran.notable.data.db.MAX_PRESSURE_NORMALIZED
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.state.History
import io.shipbook.shipbooksdk.ShipBook


private val log = ShipBook.getLogger("draw")

/**
 * Adds a stroke through [touchPoints] to [pageId], the page the pen went down on, draws it and
 * records it in [history].
 * [touchPoints] are in page coordinates. Returns false if another page is shown by now, which
 * adds nothing, or if drawing failed.
 */
fun handleDraw(
    page: PageView,
    pageId: String,
    history: History,
    strokeSize: Float,
    color: Int,
    pen: Pen,
    touchPoints: List<StrokePoint>
): Boolean {
    if (pageId != page.currentPageId) {
        log.i("Handle Draw: page $pageId is no longer shown, stroke dropped")
        return false
    }
    try {
        val boundingBox = calculateBoundingBox(touchPoints) { Pair(it.x, it.y) }

        //move rectangle
        boundingBox.inset(-strokeSize, -strokeSize)

        val stroke = Stroke(
            size = strokeSize,
            pen = pen,
            pageId = pageId,
            top = boundingBox.top,
            bottom = boundingBox.bottom,
            left = boundingBox.left,
            right = boundingBox.right,
            points = touchPoints,
            color = color,
            // Pressure is normalized to [0,1] at capture (TouchPoint.toStrokePoint).
            maxPressure = MAX_PRESSURE_NORMALIZED
        )
        page.addStrokes(listOf(stroke))
        // this is causing lagging and crushing, neo pens are not good
        page.drawAreaPageCoordinates(strokeBounds(stroke).toRect())
        history.addDrawnStroke(stroke)
    } catch (e: Exception) {
        log.e("Handle Draw: An error occurred while handling the drawing: ${e.message}")
        return false
    }
    return true
}
