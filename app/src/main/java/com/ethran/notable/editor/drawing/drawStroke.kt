package com.ethran.notable.editor.drawing

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.offsetStroke
import com.onyx.android.sdk.data.note.ShapeCreateArgs
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.NeoBrushPenWrapper
import com.onyx.android.sdk.pen.NeoCharcoalPenWrapper
import com.onyx.android.sdk.pen.NeoMarkerPenWrapper
import com.onyx.android.sdk.pen.PenRenderArgs
import io.shipbook.shipbooksdk.ShipBook


private val strokeDrawingLogger = ShipBook.getLogger("drawStroke")


/**
 * Converts pipeline points to Onyx TouchPoints for the Onyx pen wrappers. This is an Onyx
 * rendering detail — nothing outside this renderer should need TouchPoint. Fresh objects
 * are created on every call because the SDK wrappers mutate the points they are given
 * (e.g. NeoPenUtils.computeStrokePoints divides pressure in place).
 */
private fun strokeToTouchPoints(stroke: Stroke): List<TouchPoint> {
    return stroke.points.map {
        TouchPoint(
            it.x,
            it.y,
            it.pressure ?: 1f,
            stroke.size,
            it.tiltX ?: 0,
            it.tiltY ?: 0,
            stroke.updatedAt.time
        )
    }
}

fun drawStroke(canvas: Canvas, stroke: Stroke, offset: Offset) {
    val paint = Paint().apply {
        color = stroke.color
        this.strokeWidth = stroke.size
    }

    val positionedStroke = offsetStroke(stroke, offset)

    // Trying to find what throws error when drawing quickly
    try {
        when (stroke.pen) {
            Pen.BALLPEN -> drawBallPenStroke(canvas, paint, stroke.size, positionedStroke.points)
            Pen.REDBALLPEN -> drawBallPenStroke(canvas, paint, stroke.size, positionedStroke.points)
            Pen.GREENBALLPEN -> drawBallPenStroke(canvas, paint, stroke.size, positionedStroke.points)
            Pen.BLUEBALLPEN -> drawBallPenStroke(canvas, paint, stroke.size, positionedStroke.points)

            // In-memory stroke pressure is normalized to [0,1] with maxPressure == 1
            // (see Stroke.withNormalizedPressure). The wrappers take maxPressure as the
            // pressure denominator, so passing stroke.maxPressure is a no-op divide for
            // normalized strokes and stays correct for raw-scale ones.
            Pen.FOUNTAIN -> {
                NeoFountainPenV2Wrapper.drawStroke(
                    /* canvas = */ canvas,
                    /* paint = */ paint,
                    /* points = */ strokeToTouchPoints(positionedStroke),
                    /* strokeWidth = */ stroke.size,
                    /* maxTouchPressure = */ stroke.maxPressure.toFloat(),
                )
            }

            Pen.BRUSH -> {
                NeoBrushPenWrapper.drawStroke(
                    canvas,
                    paint,
                    strokeToTouchPoints(positionedStroke),
                    stroke.size,
                    stroke.maxPressure.toFloat(),
                    false
                )
            }

            Pen.MARKER -> {
                NeoMarkerPenWrapper.drawStroke(
                    canvas,
                    paint,
                    strokeToTouchPoints(positionedStroke),
                    stroke.size,
                    false
                )
            }

            Pen.PENCIL -> {
                // ShapeCreateArgs.maxPressure defaults to the device digitizer max; it is
                // the divisor the charcoal renderer applies to point pressure, so it must
                // match the scale the points are stored in.
                val shapeArg = ShapeCreateArgs().setMaxPressure(stroke.maxPressure.toFloat())
                val arg = PenRenderArgs()
                    .setCanvas(canvas)
                    .setPaint(paint)
                    .setPoints(strokeToTouchPoints(positionedStroke))
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
                strokeDrawingLogger.e("Unknown pen type: ${stroke.pen}")
            }
        }
    } catch (e: Exception) {
        strokeDrawingLogger.e("Drawing strokes failed: ${e.message}")
    }
}
