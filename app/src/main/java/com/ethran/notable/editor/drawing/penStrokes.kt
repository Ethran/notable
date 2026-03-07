package com.ethran.notable.editor.drawing

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.model.SimplePointF
import com.ethran.notable.editor.canvas.pressure
import com.ethran.notable.editor.utils.pointsToPath
import com.onyx.android.sdk.data.note.TouchPoint
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



val selectPaint = Paint().apply {
    strokeWidth = 5f
    style = Paint.Style.STROKE
    pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    isAntiAlias = true
    color = Color.GRAY
}