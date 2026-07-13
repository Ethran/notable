package com.ethran.notable.editor.drawing

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.offsetStroke

/**
 * Onyx-free renderer that draws every pen through plain Canvas path code. Markers use the
 * marker path; all other pens fall back to the ballpen path, so strokes render as flat lines
 * with no pressure or texture. Usable on any device where the Onyx SDK is unavailable.
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
