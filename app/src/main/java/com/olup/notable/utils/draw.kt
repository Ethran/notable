package com.olup.notable

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.shipbook.shipbooksdk.Log
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.toOffset
import com.olup.notable.db.Image
import com.olup.notable.db.Stroke
import com.onyx.android.sdk.data.note.ShapeCreateArgs
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.NeoBrushPen
import com.onyx.android.sdk.pen.NeoCharcoalPen
import com.onyx.android.sdk.pen.NeoFountainPen
import com.onyx.android.sdk.pen.NeoMarkerPen
import kotlin.math.abs


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

    canvas.drawPath(path, copyPaint)
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
        this.alpha = 30

    }

    val path = pointsToPath(points.map { SimplePointF(it.x, it.y) })

    canvas.drawPath(path, copyPaint)
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
            //Pen.MARKER -> NeoMarkerPen.drawStroke(canvas, paint, points, stroke.size, false)
            Pen.MARKER -> drawMarkerStroke(canvas, paint, stroke.size, points)
            Pen.FOUNTAIN -> NeoFountainPen.drawStroke(
                canvas, paint, points, 1f, stroke.size, pressure, false
            )

        }
    } catch (e: Exception) {
        Log.e(TAG, "draw.kt: Drawing strokes failed: ${e.message}")
    }
    //canvas.restore()
}


/**
 * Draws an image onto the provided Canvas at a specified location and size, using its URI.
 *
 * This function performs the following steps:
 * 1. Converts the URI of the image into a `Bitmap` object.
 * 2. Converts the `ImageBitmap` to a software-backed `Bitmap` for compatibility.
 * 3. Clears the value of `DrawCanvas.addImageByUri` to null.
 * 4. Draws the specified bitmap onto the provided Canvas within a destination rectangle
 *    defined by the `Image` object coordinates (`x`, `y`) and its dimensions (`width`, `height`),
 *    adjusted by the `offset`.
 * 5. Logs the success or failure of the operation.
 *
 * @param context The context used to retrieve the image from the URI.
 * @param canvas The Canvas object where the image will be drawn.
 * @param image The `Image` object containing details about the image (URI, position, and size).
 * @param offset The `IntOffset` used to adjust the drawing position relative to the Canvas.
 */
fun drawImage(context: Context, canvas: Canvas, image: Image, offset: IntOffset) {
    val imageBitmap = uriToBitmap(context, Uri.parse(image.uri))?.asImageBitmap()
    if (imageBitmap != null) {
        // Convert the image to a software-backed bitmap
        val softwareBitmap =
            imageBitmap.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)

        DrawCanvas.addImageByUri.value = null

        // Draw the bitmap on the canvas at the center of the page
        canvas.drawBitmap(
            softwareBitmap,
            Rect(
                0,
                0,
                imageBitmap.width,
                imageBitmap.height
            ),  // Source rectangle (full image)
            Rect(
                image.x,
                image.y,
                image.x+image.width,
                image.y+image.height
            ), // Destination rectangle (centered)
            null // Optional Paint object (null for default)
        )

        // Log after drawing
        Log.i(TAG, "Image drawn successfully at center!")
    } else
        Log.e(TAG, "Could not get image from: ${image.uri}")
}


const val padding = 0
const val lineHeight = 50
const val dotSize = 4f

fun drawLinedBg(canvas: Canvas, scroll: Int) {
    val height = canvas.height
    val width = canvas.width

    // white bg
    canvas.drawColor(Color.WHITE)

    // paint
    val paint = Paint().apply {
        this.color = Color.GRAY
        this.strokeWidth = 1f
    }

    // lines
    for (y in 0..height) {
        val line = scroll + y
        if (line % lineHeight == 0) {
            canvas.drawLine(
                padding.toFloat(), y.toFloat(), (width - padding).toFloat(), y.toFloat(), paint
            )
        }
    }
}

fun drawDottedBg(canvas: Canvas, offset: Int) {
    val height = canvas.height
    val width = canvas.width

    // white bg
    canvas.drawColor(Color.WHITE)

    // paint
    val paint = Paint().apply {
        this.color = Color.GRAY
        this.strokeWidth = 1f
    }

    // dots
    for (y in 0..height) {
        val line = offset + y
        if (line % lineHeight == 0 && line >= padding) {
            for (x in padding..width - padding step lineHeight) {
                canvas.drawOval(
                    x.toFloat() - dotSize / 2,
                    y.toFloat() - dotSize / 2,
                    x.toFloat() + dotSize / 2,
                    y.toFloat() + dotSize / 2,
                    paint
                )
            }
        }
    }

}

fun drawSquaredBg(canvas: Canvas, scroll: Int) {
    Log.i(TAG, "Drawing BG")
    val height = canvas.height
    val width = canvas.width

    // white bg
    canvas.drawColor(Color.WHITE)

    // paint
    val paint = Paint().apply {
        this.color = Color.GRAY
        this.strokeWidth = 1f
    }

    // lines
    for (y in 0..height) {
        val line = scroll + y
        if (line % lineHeight == 0) {
            canvas.drawLine(
                padding.toFloat(), y.toFloat(), (width - padding).toFloat(), y.toFloat(), paint
            )
        }
    }

    for (x in padding..width - padding step lineHeight) {
        canvas.drawLine(
            x.toFloat(), padding.toFloat(), x.toFloat(), height.toFloat(), paint
        )
    }
}

fun drawBg(canvas: Canvas, nativeTemplate: String, scroll: Int) {
    when (nativeTemplate) {
        "blank" -> canvas.drawColor(Color.WHITE)
        "dotted" -> drawDottedBg(canvas, scroll)
        "lined" -> drawLinedBg(canvas, scroll)
        "squared" -> drawSquaredBg(canvas, scroll)
    }
}

val selectPaint = Paint().apply {
    strokeWidth = 5f
    style = Paint.Style.STROKE
    pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    isAntiAlias = true
    color = Color.GRAY
}