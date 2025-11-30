package com.ethran.notable.editor.drawing

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.offsetStroke
import com.ethran.notable.editor.utils.strokeToTouchPoints
import com.onyx.android.sdk.data.note.ShapeCreateArgs
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.NeoBrushPenWrapper
import com.onyx.android.sdk.pen.NeoCharcoalPenWrapper
import com.onyx.android.sdk.pen.NeoFountainPenV2
import com.onyx.android.sdk.pen.NeoMarkerPenWrapper
import com.onyx.android.sdk.pen.NeoPenConfig
import com.onyx.android.sdk.pen.PenPathResult
import com.onyx.android.sdk.pen.PenRenderArgs
import com.onyx.android.sdk.pen.PenResult
import io.shipbook.shipbooksdk.ShipBook


private val strokeDrawingLogger = ShipBook.getLogger("drawStroke")


/* loaded from: onyxsdk-pen-1.5.0.4.aar:classes.jar:com/onyx/android/sdk/pen/NeoFountainPenWrapper.class */
object NeoFountainPenV2Wrapper {

    fun drawStroke(
        canvas: Canvas,
        paint: Paint,
        points: List<TouchPoint>,
        strokeWidth: Float,
        maxTouchPressure: Float,
    ) {

        if (points.size < 2) {
            strokeDrawingLogger.e("pageDrawing.kt: Drawing strokes failed: Not enough points")
            return
        }

        // Normalize pressure to [0, 1] using provided maxTouchPressure
        if (maxTouchPressure > 0f) {
            for (i in points.indices) {
                points[i].pressure /= maxTouchPressure
            }
        }

        val neoPenConfig = NeoPenConfig().apply {
            setWidth(strokeWidth)
            setTiltEnabled(true)
            setMaxTouchPressure(maxTouchPressure)
        }
        val neoPen = NeoFountainPenV2.create(neoPenConfig)
        if (neoPen == null) {
            strokeDrawingLogger.e("pageDrawing.kt: Drawing strokes failed: Pen creation failed")
            return
        }

        try {
            // Pen down
            drawResult(
                neoPen.onPenDown(points.first(), repaint = true),
                canvas,
                paint
            )

            // Moves (exclude first and last)
            if (points.size > 2) {
                drawResult(
                    neoPen.onPenMove(
                        points.subList(1, points.size - 1),
                        prediction = null,
                        repaint = true
                    ),
                    canvas,
                    paint
                )
            }

            // Pen up
            drawResult(
                neoPen.onPenUp(points.last(), repaint = true),
                canvas,
                paint
            )
        } finally {
            neoPen.destroy()
        }
    }


    private fun drawResult(
        result: Pair<PenResult?, PenResult?>?,
        canvas: Canvas,
        paint: Paint
    ) {
        val first = result?.first
        if (first !is PenPathResult) {
            strokeDrawingLogger.d("pageDrawing.kt: Not Path")
            return
        }
        first.draw(canvas, paint = paint)
    }


}


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


            else -> {}
        }
    } catch (e: Exception) {
        strokeDrawingLogger.e("pageDrawing.kt: Drawing strokes failed: ${e.message}")
    }
    //canvas.restore()
}
