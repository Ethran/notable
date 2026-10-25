package com.ethran.notable.editor.drawing.onyx

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.core.graphics.withTranslation
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.editor.drawing.OnyxStrokeStyle
import com.ethran.notable.editor.drawing.StrokeRenderer
import com.ethran.notable.editor.drawing.StrokeStyleRegistry
import com.ethran.notable.editor.drawing.drawBallPenStroke
import com.onyx.android.sdk.data.note.ShapeCreateArgs
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.NeoBrushPenWrapper
import com.onyx.android.sdk.pen.NeoCharcoalPenV2Wrapper
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

    /**
     * Shared PenRenderArgs for the charcoal pens (V1 and V2 take the same args, only the
     * wrapper differs). The scroll offset is applied to [canvas] by the caller
     * (canvas.withTranslation), so the stroke's original points are used as-is.
     */
    private fun charcoalArgs(
        canvas: Canvas,
        paint: Paint,
        stroke: Stroke,
        tiltEnabled: Boolean,
    ): PenRenderArgs {
        // ShapeCreateArgs.maxPressure defaults to the device digitizer max; it is the divisor the
        // charcoal renderer applies to point pressure, so it must match the scale the points are
        // stored in.
        val shapeArg = ShapeCreateArgs().setMaxPressure(stroke.maxPressure.toFloat())
        return PenRenderArgs()
            .setCanvas(canvas)
            .setPaint(paint)
            .setPoints(strokeToTouchPoints(stroke))
            .setColor(stroke.color)
            .setStrokeWidth(stroke.size)
            .setTiltEnabled(tiltEnabled)
            .setErase(false)
            .setCreateArgs(shapeArg)
            .setRenderMatrix(Matrix())
            .setScreenMatrix(Matrix())
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

        // Apply the scroll [offset] as a canvas translation instead of copying every point
        // into a shifted Stroke (the old offsetStroke). All pen paths below ultimately draw
        // via canvas.drawPath at point coordinates, so they honour this transform — same
        // pixels, but zero per-frame per-point allocation on a page that may hold thousands
        // of strokes.
        canvas.withTranslation(offset.x, offset.y) {
            // Trying to find what throws error when drawing quickly
            try {
                // In-memory stroke pressure is normalized to [0,1] with maxPressure == 1
                // (see Stroke.withNormalizedPressure). The wrappers take maxPressure as the
                // pressure denominator, so passing stroke.maxPressure is a no-op divide for
                // normalized strokes and stays correct for raw-scale ones.
                when (val onyx = style.onyx) {
                    OnyxStrokeStyle.BallPen ->
                        drawBallPenStroke(canvas, paint, stroke.size, stroke.points)

                    OnyxStrokeStyle.Fountain -> {
                        NeoFountainPenV2Wrapper.drawStroke(
                            /* canvas = */ canvas,
                            /* paint = */ paint,
                            /* points = */ strokeToTouchPoints(stroke),
                            /* strokeWidth = */ stroke.size,
                            /* maxTouchPressure = */ stroke.maxPressure.toFloat(),
                        )
                    }

                    OnyxStrokeStyle.Brush -> {
                        NeoBrushPenWrapper.drawStroke(
                            canvas,
                            paint,
                            strokeToTouchPoints(stroke),
                            stroke.size,
                            stroke.maxPressure.toFloat(),
                            false
                        )
                    }

                    OnyxStrokeStyle.Marker -> {
                        NeoMarkerPenWrapper.drawStroke(
                            canvas,
                            paint,
                            strokeToTouchPoints(stroke),
                            stroke.size,
                            false
                        )
                    }

                    is OnyxStrokeStyle.Charcoal ->
                        NeoCharcoalPenWrapper.drawNormalStroke(
                            charcoalArgs(canvas, paint, stroke, onyx.tiltEnabled)
                        )

                    is OnyxStrokeStyle.CharcoalV2 ->
                        NeoCharcoalPenV2Wrapper.drawNormalStroke(
                            charcoalArgs(canvas, paint, stroke, onyx.tiltEnabled)
                        )

                    is OnyxStrokeStyle.Calligraphy -> {
                        NeoSquarePenWrapper.drawStroke(
                            canvas,
                            paint,
                            strokeToTouchPoints(stroke),
                            stroke.size,
                            onyx.angle,
                            stroke.maxPressure.toFloat(),
                        )
                    }
                }
            } catch (e: Exception) {
                strokeDrawingLogger.e("Drawing strokes failed: ${e.message}")
            }
        }
    }
}
