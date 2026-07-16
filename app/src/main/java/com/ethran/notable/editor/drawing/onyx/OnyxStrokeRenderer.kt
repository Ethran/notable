package com.ethran.notable.editor.drawing.onyx

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.editor.drawing.OnyxStrokeStyle
import com.ethran.notable.editor.drawing.StrokeRenderer
import com.ethran.notable.editor.drawing.StrokeStyleRegistry
import com.ethran.notable.editor.drawing.drawBallPenStroke
import com.ethran.notable.editor.utils.offsetStroke
import com.onyx.android.sdk.data.note.ShapeCreateArgs
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.NeoBrushPenWrapper
import com.onyx.android.sdk.pen.NeoCharcoalPenWrapper
import com.onyx.android.sdk.pen.NeoMarkerPenWrapper
import com.onyx.android.sdk.pen.PenRenderArgs
import io.shipbook.shipbooksdk.ShipBook


private val strokeDrawingLogger = ShipBook.getLogger("OnyxStrokeRenderer")

/**
 * Renders dry strokes with the Onyx SDK pen wrappers (NeoPen family). This is the only
 * renderer that speaks Onyx types; the TouchPoint conversion below is its private detail.
 * Which wrapper a pen uses comes from [StrokeStyleRegistry] — this object only executes
 * the style it is handed.
 */
object OnyxStrokeRenderer : StrokeRenderer {

    /**
     * Converts pipeline points to Onyx TouchPoints for the Onyx pen wrappers. Fresh objects
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

    override fun drawStroke(canvas: Canvas, stroke: Stroke, offset: Offset) {
        val style = StrokeStyleRegistry.forPen(stroke.pen)
        if (style == null) {
            strokeDrawingLogger.e("No stroke style for pen: ${stroke.pen}")
            return
        }

        val paint = Paint().apply {
            color = stroke.color
            this.strokeWidth = stroke.size
        }

        val positionedStroke = offsetStroke(stroke, offset)

        // Trying to find what throws error when drawing quickly
        try {
            // In-memory stroke pressure is normalized to [0,1] with maxPressure == 1
            // (see Stroke.withNormalizedPressure). The wrappers take maxPressure as the
            // pressure denominator, so passing stroke.maxPressure is a no-op divide for
            // normalized strokes and stays correct for raw-scale ones.
            when (val onyx = style.onyx) {
                OnyxStrokeStyle.BallPen ->
                    drawBallPenStroke(canvas, paint, stroke.size, positionedStroke.points)

                OnyxStrokeStyle.Fountain -> {
                    NeoFountainPenV2Wrapper.drawStroke(
                        /* canvas = */ canvas,
                        /* paint = */ paint,
                        /* points = */ strokeToTouchPoints(positionedStroke),
                        /* strokeWidth = */ stroke.size,
                        /* maxTouchPressure = */ stroke.maxPressure.toFloat(),
                    )
                }

                OnyxStrokeStyle.Brush -> {
                    NeoBrushPenWrapper.drawStroke(
                        canvas,
                        paint,
                        strokeToTouchPoints(positionedStroke),
                        stroke.size,
                        stroke.maxPressure.toFloat(),
                        false
                    )
                }

                OnyxStrokeStyle.Marker -> {
                    NeoMarkerPenWrapper.drawStroke(
                        canvas,
                        paint,
                        strokeToTouchPoints(positionedStroke),
                        stroke.size,
                        false
                    )
                }

                is OnyxStrokeStyle.Charcoal -> {
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
                        .setTiltEnabled(onyx.tiltEnabled)
                        .setErase(false)
                        .setCreateArgs(shapeArg)
                        .setRenderMatrix(Matrix())
                        .setScreenMatrix(Matrix())
                    NeoCharcoalPenWrapper.drawNormalStroke(arg)
                }
            }
        } catch (e: Exception) {
            strokeDrawingLogger.e("Drawing strokes failed: ${e.message}")
        }
    }
}
