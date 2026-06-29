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

        // Mirror the official demo (BrushScribbleShape): build the pen via
        // FountainShapes.createNeoPenV2 so the config (width compensation, minWidth,
        // smoothLevel, pressureSensitivity, tilt=off, fastMode) matches what the
        // firmware uses while drawing live. Any deviation here makes the redraw differ.
        // fastMode MUST be false for the offline redraw. With fastMode = true the pen
        // returns PenPointResult (discrete point/dab stamps) — this is what the firmware
        // uses for low-latency LIVE drawing, but when we re-draw the finished stroke onto
        // our surface it renders "point by point" and looks faceted. fastMode = false
        // returns PenPathResult, a continuous smooth vector path, which is what we want for
        // a clean redraw (this is what the old hand-rolled wrapper got for free, since a
        // bare NeoPenConfig defaults fastMode to false). The rest of the config still comes
        // from createNeoPenV2 so the size/compensation matches the firmware.
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
            // render() runs the full onTouchDown/onTouchMove/onTouchDone pipeline and
            // draws every accumulated result plus the trailing prediction segment of the
            // last result. Doing this by hand and drawing only `.first` (as before) drops
            // the tail of the stroke and skips the SDK's batching.
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
