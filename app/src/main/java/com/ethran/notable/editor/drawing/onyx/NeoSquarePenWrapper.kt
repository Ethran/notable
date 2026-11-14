package com.ethran.notable.editor.drawing.onyx

import android.graphics.Canvas
import android.graphics.Paint
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.NeoPenRender
import com.onyx.android.sdk.pen.NeoSquarePen
import io.shipbook.shipbooksdk.ShipBook

private val logger = ShipBook.getLogger("NeoSquarePenWrapper")

/**
 * Renders a dry stroke with the Onyx square/calligraphy pen (NeoSquarePen). The SDK ships no
 * `NeoSquarePenWrapper`, so — like [NeoFountainPenV2Wrapper] — we drive the pen directly:
 * build a [com.onyx.android.sdk.pen.NeoPenConfig], create the pen, and run it through
 * [NeoPenRender]. Mirrors kreader's `SquarePenShape.drawBrush` (config.width / brushAngle /
 * brushRatio). The chisel nib angle is fixed per pen preset (Notable ships +45°).
 */
private data class SquareKey(val width: Float, val angle: Float)

object NeoSquarePenWrapper {

    fun drawStroke(
        canvas: Canvas,
        paint: Paint,
        points: List<TouchPoint>,
        strokeWidth: Float,
        brushAngle: Float,
        maxTouchPressure: Float,
    ) {
        if (points.size < 2) {
            logger.e("Drawing strokes failed: Not enough points")
            return
        }

        // Pressure must be in [0, 1]; maxTouchPressure is the denominator of the incoming values
        // (1 for normalized strokes). Divide on a copy so we never mutate the caller's points.
        val renderPoints = if (maxTouchPressure == 1f || maxTouchPressure <= 0f) points
        else points.map { p -> TouchPoint(p).apply { pressure /= maxTouchPressure } }

        val rendered = NeoPenPool.pool.use(
            key = SquareKey(strokeWidth, brushAngle),
            create = {
                val config = NeoSquarePen.defaultPenConfig().apply {
                    // Match the stock SquarePenShape (drawBrush): nib width is twice the stroke
                    // size and brush ratio is min(size, 10).
                    width = strokeWidth * 2f
                    this.brushAngle = brushAngle
                    brushRatio = strokeWidth.coerceAtMost(10f)
                }
                NeoSquarePen.create(config)
            },
        ) { neoPen ->
            NeoPenRender(neoPen).render(canvas, paint, renderPoints)
        }
        if (rendered == null) logger.e("Drawing strokes failed: Pen creation failed")
    }
}
