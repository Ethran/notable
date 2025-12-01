package com.ethran.notable.editor.drawing

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.offsetStroke
import com.ethran.notable.editor.utils.strokeToTouchPoints
import com.ethran.notable.ui.SnackState
import com.onyx.android.sdk.data.note.ShapeCreateArgs
import com.onyx.android.sdk.pen.NeoBrushPenWrapper
import com.onyx.android.sdk.pen.NeoCharcoalPenWrapper
import com.onyx.android.sdk.pen.NeoMarkerPenWrapper
import com.onyx.android.sdk.pen.PenRenderArgs
import io.shipbook.shipbooksdk.ShipBook


private val strokeDrawingLogger = ShipBook.getLogger("drawStroke")



fun drawStroke(canvas: Canvas, stroke: Stroke, offset: Offset) {
    //canvas.save()
    //canvas.translate(offset.x.toFloat(), offset.y.toFloat())

    val paint = Paint().apply {
        color = stroke.color
        this.strokeWidth = stroke.size
    }

    val points = strokeToTouchPoints(offsetStroke(stroke, offset))

    // Trying to find what throws error when drawing quickly
    try {
        when (stroke.pen) {
            Pen.BALLPEN -> drawBallPenStroke(canvas, paint, stroke.size, points)
            Pen.REDBALLPEN -> drawBallPenStroke(canvas, paint, stroke.size, points)
            Pen.GREENBALLPEN -> drawBallPenStroke(canvas, paint, stroke.size, points)
            Pen.BLUEBALLPEN -> drawBallPenStroke(canvas, paint, stroke.size, points)

            Pen.FOUNTAIN -> {
                NeoFountainPenV2Wrapper.drawStroke(
                    /* canvas = */ canvas,
                    /* paint = */ paint,
                    /* points = */ points,
                    /* strokeWidth = */ stroke.size,
                    /* maxTouchPressure = */ stroke.maxPressure.toFloat(),
                )
            }

            Pen.BRUSH -> {
                NeoBrushPenWrapper.drawStroke(
                    canvas,
                    paint,
                    points,
                    stroke.size,
                    stroke.maxPressure.toFloat(),
                    false
                )
            }

            Pen.MARKER -> {
                NeoMarkerPenWrapper.drawStroke(
                    canvas,
                    paint,
                    points,
                    stroke.size,
                    false
                )
            }

            Pen.PENCIL -> {
                val shapeArg = ShapeCreateArgs()
                val arg = PenRenderArgs()
                    .setCanvas(canvas)
                    .setPaint(paint)
                    .setPoints(points)
                    .setColor(stroke.color)
                    .setStrokeWidth(stroke.size)
                    .setTiltEnabled(true)
                    .setErase(false)
                    .setCreateArgs(shapeArg)
                    .setRenderMatrix(Matrix())
                    .setScreenMatrix(Matrix())
                NeoCharcoalPenWrapper.drawNormalStroke(arg)
            }
            else -> {
                SnackState.logAndShowError("drawStroke", "Unknown pen type: ${stroke.pen}")
            }
        }
    } catch (e: Exception) {
        strokeDrawingLogger.e("Drawing strokes failed: ${e.message}")
    }
    //canvas.restore()
}
