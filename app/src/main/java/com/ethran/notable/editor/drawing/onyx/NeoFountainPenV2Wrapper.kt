package com.ethran.notable.editor.drawing.onyx

import android.graphics.Canvas
import android.graphics.Paint
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.NeoFountainPenWrapper
import com.onyx.android.sdk.pen.NeoPenRender
import com.onyx.android.sdk.pen.utils.FountainShapes
import io.shipbook.shipbooksdk.ShipBook

private val logger = ShipBook.getLogger("NeoFountainPenV2Wrapper")


private data class FountainKey(val width: Float)

object NeoFountainPenV2Wrapper {

    fun drawStroke(
        canvas: Canvas,
        paint: Paint,
        points: List<TouchPoint>,
        strokeWidth: Float,
        maxTouchPressure: Float,
    ) {
        if (points.size < 2) {
            logger.e("Drawing strokes failed: Not enough points")
            return
        }

        // Fountain V2 produces closed (filled) paths. The pressure must be in [0, 1];
        // maxTouchPressure is the denominator of the incoming values (1 for the normalized
        // strokes the pipeline produces — see Stroke.maxPressure). Divide on a copy so we
        // never mutate the caller's points.
        val renderPoints = if (maxTouchPressure == 1f || maxTouchPressure <= 0f) points
        else points.map { p -> TouchPoint(p).apply { pressure /= maxTouchPressure } }

        // Mirror the official demo (BrushScribbleShape) via FountainShapes.createNeoPenV2 so
        // config matches the firmware's live rendering. fastMode=false yields PenPathResult
        // (smooth vector path); true would give discrete dab stamps and look faceted on redraw.
        // See docs/onyx-sdk/onyx-neo-fountain-pen-v2.md.
        val rendered = NeoPenPool.pool.use(
            key = FountainKey(strokeWidth),
            create = {
                FountainShapes.createNeoPenV2(
                    strokeWidth,                                  // width
                    NeoFountainPenWrapper.MIN_FOUNTAIN_PEN_WIDTH, // minWidth
                    1.0f,                                         // displayScaleX
                    1.0f,                                         // displayScaleY
                    1.0f,                                         // scalePrecision
                    1.0f,                                         // createScale
                    null,                                         // pressureSensitivity -> default 0.3
                    false,                                        // fastMode -> false = smooth PenPathResult
                    null,                                         // smoothLevel -> default 0.6
                )
            },
        ) { neoPen ->
            // render() runs the full onTouchDown/onTouchMove/onTouchDone pipeline including
            // the trailing prediction segment; this is required for complete stroke coverage.
            NeoPenRender(neoPen).render(canvas, paint, renderPoints)
        }
        if (rendered == null) logger.e("Drawing strokes failed: Pen creation failed")
    }

}
