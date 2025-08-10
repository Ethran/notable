package com.ethran.notable.drawing

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.toOffset
import com.ethran.notable.classes.pressure
import com.ethran.notable.db.Stroke
import com.ethran.notable.db.StrokePoint
import com.ethran.notable.modals.GlobalAppSettings
import com.ethran.notable.utils.Pen
import com.ethran.notable.utils.SimplePointF
import com.ethran.notable.utils.offsetStroke
import com.ethran.notable.utils.pointsToPath
import com.ethran.notable.utils.strokeToTouchPoints
import com.onyx.android.sdk.data.note.ShapeCreateArgs
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.NeoBrushPen
import com.onyx.android.sdk.pen.NeoCharcoalPen
import com.onyx.android.sdk.pen.NeoFountainPen
import com.onyx.android.sdk.pen.NeoMarkerPen
import io.shipbook.shipbooksdk.ShipBook
import kotlin.math.abs
import kotlin.math.cos

private val penStrokesLog = ShipBook.getLogger("PenStrokesLog")


fun drawBallPenStroke(
    canvas: Canvas, paint: Paint, strokeSize: Float, points: List<TouchPoint>
) {
    val copyPaint = Paint(paint).apply {
        this.strokeWidth = strokeSize
        this.style = Paint.Style.STROKE
        this.strokeCap = Paint.Cap.ROUND
        this.strokeJoin = Paint.Join.ROUND

        this.isAntiAlias = true
    }

    val path = Path()
    val prePoint = PointF(points[0].x, points[0].y)
    path.moveTo(prePoint.x, prePoint.y)

    for (point in points) {
        // skip strange jump point.
        if (abs(prePoint.y - point.y) >= 30) continue
        path.quadTo(prePoint.x, prePoint.y, point.x, point.y)
        prePoint.x = point.x
        prePoint.y = point.y
    }
    try {
        canvas.drawPath(path, copyPaint)
    } catch (e: Exception) {
        penStrokesLog.e("Exception during draw", e)
    }
}

val eraserPaint = Paint().apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
    color = Color.BLACK
    xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    isAntiAlias = false
}
private val reusablePath = Path()
fun drawEraserStroke(canvas: Canvas, points: List<StrokePoint>, strokeSize: Float) {
    eraserPaint.strokeWidth = strokeSize

    reusablePath.reset()
    if (points.isEmpty()) return

    val prePoint = PointF(points[0].x, points[0].y)
    reusablePath.moveTo(prePoint.x, prePoint.y)

    for (i in 1 until points.size) {
        val point = points[i]
        if (abs(prePoint.y - point.y) >= 30) continue
        reusablePath.quadTo(prePoint.x, prePoint.y, point.x, point.y)
        prePoint.x = point.x
        prePoint.y = point.y
    }

    try {
        canvas.drawPath(reusablePath, eraserPaint)
    } catch (e: Exception) {
        penStrokesLog.e("Exception during draw", e)
    }
}


fun drawMarkerStroke(
    canvas: Canvas, paint: Paint, strokeSize: Float, points: List<TouchPoint>
) {
    val copyPaint = Paint(paint).apply {
        this.strokeWidth = strokeSize
        this.style = Paint.Style.STROKE
        this.strokeCap = Paint.Cap.ROUND
        this.strokeJoin = Paint.Join.ROUND
        this.isAntiAlias = true
        this.alpha = 100

    }

    val path = pointsToPath(points.map { SimplePointF(it.x, it.y) })

    canvas.drawPath(path, copyPaint)
}

fun drawFountainPenStroke(
    canvas: Canvas, paint: Paint, strokeSize: Float, points: List<TouchPoint>
) {
    val copyPaint = Paint(paint).apply {
        this.strokeWidth = strokeSize
        this.style = Paint.Style.STROKE
        this.strokeCap = Paint.Cap.ROUND
        this.strokeJoin = Paint.Join.ROUND
//        this.blendMode = BlendMode.OVERLAY
        this.isAntiAlias = true
    }

    val path = Path()
    val prePoint = PointF(points[0].x, points[0].y)
    path.moveTo(prePoint.x, prePoint.y)

    for (point in points) {
        // skip strange jump point.
        if (abs(prePoint.y - point.y) >= 30) continue
        path.quadTo(prePoint.x, prePoint.y, point.x, point.y)
        prePoint.x = point.x
        prePoint.y = point.y
        copyPaint.strokeWidth =
            (1.5f - strokeSize / 40f) * strokeSize * (1 - cos(0.5f * 3.14f * point.pressure / pressure))
        point.tiltX
        point.tiltY
        point.timestamp

        canvas.drawPath(path, copyPaint)
        path.reset()
        path.moveTo(point.x, point.y)
    }
}

fun drawStroke(canvas: Canvas, stroke: Stroke, offset: IntOffset) {
    //canvas.save()
    //canvas.translate(offset.x.toFloat(), offset.y.toFloat())

    val paint = Paint().apply {
        color = stroke.color
        this.strokeWidth = stroke.size
    }

    val points = strokeToTouchPoints(offsetStroke(stroke, offset.toOffset()))

    // Trying to find what throws error when drawing quickly
    try {
        when (stroke.pen) {
            Pen.BALLPEN -> drawBallPenStroke(canvas, paint, stroke.size, points)
            Pen.REDBALLPEN -> drawBallPenStroke(canvas, paint, stroke.size, points)
            Pen.GREENBALLPEN -> drawBallPenStroke(canvas, paint, stroke.size, points)
            Pen.BLUEBALLPEN -> drawBallPenStroke(canvas, paint, stroke.size, points)
            // TODO: this functions for drawing are slow and unreliable
            // replace them with something better
            Pen.PENCIL -> NeoCharcoalPen.drawNormalStroke(
                null,
                canvas,
                paint,
                points,
                stroke.color,
                stroke.size,
                ShapeCreateArgs(),
                Matrix(),
                false
            )

            Pen.BRUSH -> NeoBrushPen.drawStroke(canvas, paint, points, stroke.size, pressure, false)
            Pen.MARKER -> {
                if (GlobalAppSettings.current.neoTools)
                    NeoMarkerPen.drawStroke(canvas, paint, points, stroke.size, false)
                else
                    drawMarkerStroke(canvas, paint, stroke.size, points)
            }

            Pen.FOUNTAIN -> {
                if (GlobalAppSettings.current.neoTools)
                    NeoFountainPen.drawStroke(
                        canvas,
                        paint,
                        points,
                        1f,
                        stroke.size,
                        pressure,
                        false
                    )
                else
                    drawFountainPenStroke(canvas, paint, stroke.size, points)
            }


        }
    } catch (e: Exception) {
        penStrokesLog.e("pageDrawing.kt: Drawing strokes failed: ${e.message}")
    }
    //canvas.restore()
}



val selectPaint = Paint().apply {
    strokeWidth = 5f
    style = Paint.Style.STROKE
    pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    isAntiAlias = true
    color = Color.GRAY
}