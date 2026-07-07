package com.ethran.notable.editor.drawing

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.offsetStroke

/**
 * Onyx-free renderer stub that exercises the StrokeRenderer seam before the real
 * perfect-freehand geometry exists (plan Phase 1/2). Every pen renders through the
 * existing ballpen/marker path code, so strokes persist, undo, erase and re-render —
 * they just all look like plain lines for now.
 */
object AppStrokeRenderer : StrokeRenderer {

    override fun drawStroke(canvas: Canvas, stroke: Stroke, offset: Offset) {
        val paint = Paint().apply {
            color = stroke.color
            strokeWidth = stroke.size
        }
        val points = offsetStroke(stroke, offset).points
        if (points.isEmpty()) return

        when (stroke.pen) {
            Pen.MARKER -> drawMarkerStroke(canvas, paint, stroke.size, points)
            else -> drawBallPenStroke(canvas, paint, stroke.size, points)
        }
    }
}
