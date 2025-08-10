package com.ethran.notable.drawing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.core.graphics.toRect
import androidx.core.graphics.withClip
import androidx.core.net.toUri
import com.ethran.notable.classes.DrawCanvas
import com.ethran.notable.classes.PageView
import com.ethran.notable.classes.showHint
import com.ethran.notable.db.BackgroundType
import com.ethran.notable.db.Image
import com.ethran.notable.db.getBackgroundType
import com.ethran.notable.modals.GlobalAppSettings
import com.ethran.notable.utils.imageBounds
import com.ethran.notable.utils.strokeBounds
import com.ethran.notable.utils.uriToBitmap
import io.shipbook.shipbooksdk.ShipBook
import kotlin.system.measureTimeMillis

private val pageDrawingLog = ShipBook.getLogger("PageDrawingLog")




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
        pageDrawingLog.i("Image drawn successfully at center!")
    } else
        pageDrawingLog.e("Could not get image from: ${image.uri}")
}


fun drawDebugRectWithLabels(
    canvas: Canvas,
    rect: RectF,
    rectColor: Int = Color.RED,
    labelColor: Int = Color.BLUE
) {
    val rectPaint = Paint().apply {
        color = rectColor
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    pageDrawingLog.w("Drawing debug rect $rect")
    // Draw rectangle outline
    canvas.drawRect(rect, rectPaint)

    // Setup label paint
    val labelPaint = Paint().apply {
        color = labelColor
        textAlign = Paint.Align.LEFT
        textSize = 40f
        isAntiAlias = true
    }

    // Helper to format text
    fun format(x: Float, y: Float) = "(${x.toInt()}, ${y.toInt()})"

    val topLeftLabel = format(rect.left, rect.top)
    val topRightLabel = format(rect.right, rect.top)
    val bottomLeftLabel = format(rect.left, rect.bottom)
    val bottomRightLabel = format(rect.right, rect.bottom)

    val topRightTextWidth = labelPaint.measureText(topRightLabel)
    val bottomRightTextWidth = labelPaint.measureText(bottomRightLabel)

    // Draw coordinate labels at corners
    canvas.drawText(topLeftLabel, rect.left + 8f, rect.top + labelPaint.textSize, labelPaint)
    canvas.drawText(
        topRightLabel,
        rect.right - topRightTextWidth - 8f,
        rect.top + labelPaint.textSize,
        labelPaint
    )
    canvas.drawText(bottomLeftLabel, rect.left + 8f, rect.bottom - 8f, labelPaint)
    canvas.drawText(
        bottomRightLabel,
        rect.right - bottomRightTextWidth - 8f,
        rect.bottom - 8f,
        labelPaint
    )
}


fun drawOnCanvasFromPage(
    page: PageView,
    canvas: Canvas,
    canvasClipBounds: Rect,
    pageArea: Rect,
    ignoredStrokeIds: List<String> = listOf(),
    ignoredImageIds: List<String> = listOf(),
) {

    val zoomLevel = page.zoomLevel.value
    val backgroundType = page.pageFromDb?.getBackgroundType() ?: BackgroundType.Native
    val background = page.pageFromDb?.background ?: "blank"

    // Canvas is scaled, it will scale page area.
    canvas.withClip(canvasClipBounds) {
        drawColor(Color.BLACK)

        val timeToDraw = measureTimeMillis {
            drawBg(page.context, this, backgroundType, background, page.scroll, zoomLevel, page)
            if (GlobalAppSettings.current.debugMode) {
                drawDebugRectWithLabels(canvas, RectF(canvasClipBounds), Color.BLACK)
            }
            try {
                page.images.forEach { image ->
                    if (ignoredImageIds.contains(image.id)) return@forEach
                    pageDrawingLog.i("PageView.kt: drawing image!")
                    val bounds = imageBounds(image)
                    // if stroke is not inside page section
                    if (!bounds.toRect().intersect(pageArea)) return@forEach
                    drawImage(page.context, this, image, IntOffset(0, -page.scroll))

                }
            } catch (e: Exception) {
                pageDrawingLog.e("PageView.kt: Drawing images failed: ${e.message}", e)

                val errorMessage =
                    if (e.message?.contains("does not have permission") == true) {
                        "Permission error: Unable to access image."
                    } else {
                        "Failed to load images."
                    }
                showHint(errorMessage, page.coroutineScope)
            }
            try {
                page.strokes.forEach { stroke ->
                    if (ignoredStrokeIds.contains(stroke.id)) return@forEach
                    val bounds = strokeBounds(stroke)
                    // if stroke is not inside page section
                    if (!bounds.toRect().intersect(pageArea)) return@forEach

                    drawStroke(
                        this, stroke, IntOffset(0, -page.scroll)
                    )
                }
            } catch (e: Exception) {
                pageDrawingLog.e("PageView.kt: Drawing strokes failed: ${e.message}", e)
                showHint("Error drawing strokes", page.coroutineScope)
            }

        }
        pageDrawingLog.i("Drew area in ${timeToDraw}ms")
    }
}