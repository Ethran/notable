package com.ethran.notable.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.toOffset
import androidx.core.net.toUri
import com.ethran.notable.R
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.TAG
import com.ethran.notable.classes.DrawCanvas
import com.ethran.notable.classes.PageView
import com.ethran.notable.classes.pressure
import com.ethran.notable.db.BackgroundType
import com.ethran.notable.db.Image
import com.ethran.notable.db.Stroke
import com.ethran.notable.modals.GlobalAppSettings
import com.onyx.android.sdk.data.note.ShapeCreateArgs
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.extension.isNotNull
import com.onyx.android.sdk.pen.NeoBrushPen
import com.onyx.android.sdk.pen.NeoCharcoalPen
import com.onyx.android.sdk.pen.NeoFountainPen
import com.onyx.android.sdk.pen.NeoMarkerPen
import io.shipbook.shipbooksdk.Log
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt


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
        Log.e(TAG, "Exception during draw", e)
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
    if (image.uri.isNullOrEmpty())
        return
    val imageBitmap = uriToBitmap(context, image.uri.toUri())?.asImageBitmap()
    if (imageBitmap != null) {
        // Convert the image to a software-backed bitmap
        val softwareBitmap =
            imageBitmap.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)

        DrawCanvas.addImageByUri.value = null

        val rectOnImage = Rect(0, 0, imageBitmap.width, imageBitmap.height)
        val rectOnCanvas = Rect(
            image.x + offset.x,
            image.y + offset.y,
            image.x + image.width + offset.x,
            image.y + image.height + offset.y
        )
        // Draw the bitmap on the canvas at the center of the page
        canvas.drawBitmap(softwareBitmap, rectOnImage, rectOnCanvas, null)

        // Log after drawing
        Log.i(TAG, "Image drawn successfully at center!")
    } else
        Log.e(TAG, "Could not get image from: ${image.uri}")
}


const val padding = 0
const val lineHeight = 80
const val dotSize = 6f
const val hexVerticalCount = 26

fun drawLinedBg(canvas: Canvas, scroll: Int, scale: Float) {
    val height = (canvas.height / scale).toInt()
    val width = (canvas.width / scale).toInt()

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

fun drawDottedBg(canvas: Canvas, offset: Int, scale: Float) {
    val height = (canvas.height / scale).toInt()
    val width = (canvas.width / scale).toInt()
    Log.d(TAG, "height(drawDottedBg): $height, width: $width")

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

fun drawSquaredBg(canvas: Canvas, scroll: Int, scale: Float) {
    val height = (canvas.height / scale).toInt()
    val width = (canvas.width / scale).toInt()

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

fun drawHexedBg(canvas: Canvas, scroll: Int, scale: Float) {
    val height = (canvas.height / scale)
    val width = (canvas.width / scale)

    // background
    canvas.drawColor(Color.WHITE)

    // stroke
    val paint = Paint().apply {
        this.color = Color.GRAY
        this.strokeWidth = 1f
        this.style = Paint.Style.STROKE
    }

    // https://www.redblobgames.com/grids/hexagons/#spacing
    val r = max(width, height) / (hexVerticalCount * 1.5f)
    val hexHeight = r * 2
    val hexWidth = r * sqrt(3f)

    val rows = (height / hexVerticalCount).toInt()
    val cols = (width / hexWidth).toInt()

    for (row in 0..rows) {
        val offsetX = if (row % 2 == 0) 0f else hexWidth / 2

        for (col in 0..cols) {
            val x = col * hexWidth + offsetX
            val y = row * hexHeight * 0.75f - scroll.toFloat().mod(hexHeight * 1.5f)
            drawHexagon(canvas, x, y, r, paint)
        }
    }
}

fun drawHexagon(canvas: Canvas, centerX: Float, centerY: Float, r: Float, paint: Paint) {
    val path = Path()
    for (i in 0..5) {
        val angle = Math.toRadians((30 + 60 * i).toDouble())
        val x = (centerX + r * cos(angle)).toFloat()
        val y = (centerY + r * sin(angle)).toFloat()
        if (i == 0) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
    }
    path.close()
    canvas.drawPath(path, paint)
}

fun drawBackgroundImages(
    context: Context,
    canvas: Canvas,
    backgroundImage: String,
    scroll: Int,
    page: PageView? = null,
    scale: Float = 1.0F,
    repeat: Boolean = false,
) {
    try {
        val imageBitmap = when (backgroundImage) {
            "iris" -> {
                val resId = R.drawable.iris
                ImageBitmap.imageResource(context.resources, resId).asAndroidBitmap()
            }

            else -> {
                if (page != null) {
                    page.getOrLoadBackground(backgroundImage, -1, scale)
                } else {
                    loadBackgroundBitmap(backgroundImage, -1, scale)
                }
            }
        }

        if (imageBitmap != null) {
            drawBitmapToCanvas(canvas, imageBitmap, scroll, scale, repeat)
        } else {
            Log.e(TAG, "Failed to load image from $backgroundImage")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error loading background image: ${e.message}", e)
    }
}

fun drawTitleBox(canvas: Canvas) {

    // Draw label-like area in center
    val paint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    val borderPaint = Paint().apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    // This might not be actual width in some situations
    // investigate it, in case of problems
    val canvasHeight = max(SCREEN_WIDTH, SCREEN_HEIGHT)
    val canvasWidth = min(SCREEN_WIDTH, SCREEN_HEIGHT)

    // Dimensions for the label box
    val labelWidth = canvasWidth * 0.8f
    val labelHeight = canvasHeight * 0.25f
    val left = (canvasWidth - labelWidth) / 2
    val top = (canvasHeight - labelHeight) / 2
    val right = left + labelWidth
    val bottom = top + labelHeight

    val rectF = RectF(left, top, right, bottom)
    val cornerRadius = 64f

    canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
    canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, borderPaint)
}


fun drawPdfPage(
    canvas: Canvas,
    pdfUriString: String,
    pageNumber: Int,
    scroll: Int,
    page: PageView? = null,
    scale: Float = 1.0f
) {
    if (pageNumber == -1) {
        Log.e(TAG, "Page number should not be -1, uri: $pdfUriString")
        return
    }
    try {
        val imageBitmap = if (page != null) {
            page.getOrLoadBackground(pdfUriString, pageNumber, scale)
        } else {
            loadBackgroundBitmap(pdfUriString, pageNumber,scale)
        }
        if (imageBitmap.isNotNull()) {
            drawBitmapToCanvas(canvas, imageBitmap, scroll, scale, false)
        }

    } catch (e: Exception) {
        Log.e(TAG, "drawPdfPage: Failed to render PDF", e)
    }
}

fun drawBitmapToCanvas(
    canvas: Canvas,
    imageBitmap: Bitmap,
    scroll: Int,
    scale: Float,
    repeat: Boolean
) {
    canvas.drawColor(Color.WHITE)
    val imageWidth = imageBitmap.width
    val imageHeight = imageBitmap.height


//    val canvasWidth = canvas.width
    val canvasHeight = canvas.height
    val widthOnCanvas = min(SCREEN_WIDTH, SCREEN_HEIGHT)

    val scaleFactor = widthOnCanvas.toFloat() / imageWidth
    val scaledHeight = (imageHeight * scaleFactor).toInt()


    // Draw the first image, considering the scroll offset
    val srcTop = (scroll / scaleFactor).toInt() % imageHeight
    val rectOnImage = Rect(0, srcTop.coerceAtLeast(0), imageWidth, imageHeight)
    val rectOnCanvas = Rect(
        0,
        0,
        widthOnCanvas,
        ((imageHeight - srcTop) * scaleFactor).toInt()
    )

    var filledHeight = 0
    if (repeat || scroll < canvasHeight) {
        canvas.drawBitmap(imageBitmap, rectOnImage, rectOnCanvas, null)
        filledHeight = rectOnCanvas.bottom
    }

//    if (widthOnCanvas < canvasWidth / scale) {
//        Log.e(
//            TAG,
//            "left: $filledHeight, top: 0, right: $canvasWidth, bottom: $canvasHeight"
//        )
//        canvas.drawRect(
//            widthOnCanvas.toFloat(),
//            0f,
//            canvasWidth.toFloat(),
//            canvasHeight.toFloat(),
//            paint
//        )
//    }

    if (repeat) {
        var currentTop = filledHeight
        val srcRect = Rect(0, 0, imageWidth, imageHeight)
        while (currentTop < canvasHeight / scale) {

            val dstRect = RectF(
                0f,
                currentTop / scale,
                widthOnCanvas / scale,
                (currentTop + scaledHeight) / scale
            )
            canvas.drawBitmap(imageBitmap, srcRect, dstRect, null)
            currentTop += scaledHeight
        }
    } else {
//        // Fill the remaining area with white if necessary
//        if (filledHeight < canvasHeight / scale) {
//            canvas.drawRect(
//                0f,
//                filledHeight / scale,
//                canvasWidth / scale,
//                canvasHeight / scale,
//                paint
//            )
//        }
    }
}

fun drawBg(
    context: Context,
    canvas: Canvas,
    backgroundType: BackgroundType,
    background: String,
    scroll: Int = 0,
    scale: Float = 1f, // When exporting, we change scale of canvas. therefore canvas.width/height is scaled
    page: PageView? = null,
    clipRect: Rect? = null // before the scaling
) {
    clipRect?.let {
        canvas.save()
        canvas.clipRect(scaleRect(it, scale))
    }
    when (backgroundType) {
        is BackgroundType.Image -> {
            drawBackgroundImages(context, canvas, background, scroll, page, scale)
        }

        is BackgroundType.ImageRepeating -> {
            drawBackgroundImages(context, canvas, background, scroll, page, scale, true)
        }

        is BackgroundType.CoverImage -> {
            drawBackgroundImages(context, canvas, background, 0, page, scale)
            drawTitleBox(canvas)
        }

        is BackgroundType.Pdf -> {
            drawPdfPage(canvas, background, backgroundType.page, scroll, page, scale)
        }

        is BackgroundType.Native -> {
            when (background) {
                "blank" -> canvas.drawColor(Color.WHITE)
                "dotted" -> drawDottedBg(canvas, scroll, scale)
                "lined" -> drawLinedBg(canvas, scroll, scale)
                "squared" -> drawSquaredBg(canvas, scroll, scale)
                "hexed" -> drawHexedBg(canvas, scroll, scale)
            }
        }
    }

    // in landscape orientation add margin to indicate what will be visible in vertical orientation.
    if (SCREEN_WIDTH > SCREEN_HEIGHT || scale < 1.0f) {
        val paint = Paint().apply {
            this.color = Color.MAGENTA
            this.strokeWidth = 2f
        }
        val margin = min( SCREEN_HEIGHT, SCREEN_WIDTH)
        // Draw vertical line with x= SCREEN_HEIGHT
        canvas.drawLine(
            margin.toFloat(),
            padding.toFloat(),
            margin.toFloat(),
            (SCREEN_HEIGHT / scale - padding),
            paint
        )
    }
    if (clipRect != null) {
        canvas.restore()
    }
}

val selectPaint = Paint().apply {
    strokeWidth = 5f
    style = Paint.Style.STROKE
    pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    isAntiAlias = true
    color = Color.GRAY
}