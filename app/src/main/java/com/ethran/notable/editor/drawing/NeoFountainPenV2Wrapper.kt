package com.ethran.notable.editor.drawing

import android.graphics.Canvas
import android.graphics.Paint
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.NeoFountainPenV2
import com.onyx.android.sdk.pen.NeoPenConfig
import com.onyx.android.sdk.pen.PenPathResult
import com.onyx.android.sdk.pen.PenResult
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
            logger.e("Drawing strokes failed: Pen creation failed")
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
            logger.d("Expected PenPathResult but got ${first?.javaClass?.simpleName ?: "null"}")
            return
        }
        first.draw(canvas, paint = paint)
    }
}
