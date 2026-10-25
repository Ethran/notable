package com.ethran.notable.editor.drawing

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.core.graphics.withTranslation
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.editor.utils.Pen

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
        val points = stroke.points
        if (points.isEmpty()) return

        // Apply the scroll [offset] as a canvas translation rather than copying every point
        // into a shifted Stroke (see OnyxStrokeRenderer / P15).
        canvas.withTranslation(offset.x, offset.y) {
            when (stroke.pen) {
                Pen.MARKER -> drawMarkerStroke(canvas, paint, stroke.size, points)
                else -> drawBallPenStroke(canvas, paint, stroke.size, points)
            }
        }
    }
}
