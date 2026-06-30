package com.ethran.notable.editor.drawing

import android.graphics.Canvas
import android.graphics.Paint
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.NeoFountainPenWrapper
import com.onyx.android.sdk.pen.NeoPenRender
import com.onyx.android.sdk.pen.utils.FountainShapes
import io.shipbook.shipbooksdk.ShipBook

private val logger = ShipBook.getLogger("NeoFountainPenV2Wrapper")


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

        // Fountain V2 produces closed (filled) paths. The pressure must be in [0, 1].
        // Work on a copy so we never mutate the caller's points (otherwise re-rendering
        // the same stroke after an unfreeze would normalize the pressures again).
        val renderPoints = copyAndNormalizePressure(points, maxTouchPressure)

        // Mirror the official demo (BrushScribbleShape) via FountainShapes.createNeoPenV2 so
        // config matches the firmware's live rendering. fastMode=false yields PenPathResult
        // (smooth vector path); true would give discrete dab stamps and look faceted on redraw.
        // See docs/onyx-neo-fountain-pen-v2.md.
        val neoPen = FountainShapes.createNeoPenV2(
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
        if (neoPen == null) {
            logger.e("Drawing strokes failed: Pen creation failed")
            return
        }

        val penRender = NeoPenRender(neoPen)
        try {
            // render() runs the full onTouchDown/onTouchMove/onTouchDone pipeline including
            // the trailing prediction segment; this is required for complete stroke coverage.
            penRender.render(canvas, paint, renderPoints)
        } finally {
            penRender.destroyPen()
        }
    }

    private fun copyAndNormalizePressure(
        points: List<TouchPoint>,
        maxTouchPressure: Float,
    ): List<TouchPoint> {
        val needNormalize = maxTouchPressure > 0f && points.any { it.pressure > 1.0f }
        return points.map { p ->
            TouchPoint(p).apply {
                if (needNormalize) pressure /= maxTouchPressure
            }
        }
    }
}
