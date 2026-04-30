package com.ethran.notable.editor.drawing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.toRect
import androidx.core.graphics.withClip
import androidx.core.net.toUri
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.utils.imageBounds
import com.ethran.notable.editor.utils.plus
import com.ethran.notable.editor.utils.strokeBounds
import com.ethran.notable.io.uriToBitmap
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.plus
import io.shipbook.shipbooksdk.ShipBook

private val pageDrawingLog = ShipBook.getLogger("PageDrawingLog")


/**
 * Draws an image onto the provided [Canvas] at the location and size specified by the [Image] object.
 *
 * The drawing process includes:
 * 1. Resolving the [image] URI into a [Bitmap].
 * 2. Creating a software-backed copy of the bitmap for compatibility with the [Canvas].
 * 3. Resetting [CanvasEventBus.addImageByUri] to prevent redundant add events.
 * 4. Drawing the bitmap into a destination rectangle calculated from the image's position
 *    and dimensions, adjusted by the provided [offset].
 * 5. Logging the outcome of the operation.
 *
 * @param context The context used to resolve the image URI.
 * @param canvas The Android [Canvas] where the image will be rendered.
 * @param image The data model containing the URI, coordinates (`x`, `y`), and size.
 * @param offset An [Offset] applied to the drawing coordinates (typically representing scroll position).
 * @return An [AppResult] indicating success ([Unit]) or a [DomainError] (e.g., if the URI is invalid
 * or the bitmap fails to load).
 */
fun drawImage(
    context: Context, canvas: Canvas, image: Image, offset: Offset
): AppResult<Unit, DomainError> {
    val uriString = image.uri
    if (uriString.isNullOrEmpty()) {
        return AppResult.Error(DomainError.NotFound("Image URI"))
    }

    // Attempt to load the bitmap
    val imageBitmap = try {
        uriToBitmap(context, uriString.toUri())?.asImageBitmap()
    } catch (e: Exception) {
        return AppResult.Error(DomainError.DrawingError("System error loading bitmap: ${e.message}"))
    }

    if (imageBitmap == null) {
        pageDrawingLog.e("Could not get image from: $uriString")
        return AppResult.Error(DomainError.NotFound("Image file at $uriString"))
    }

    return try {
        // Convert to software-backed bitmap
        val softwareBitmap = imageBitmap.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)

        CanvasEventBus.addImageByUri.value = null

        val rectOnImage = Rect(0, 0, imageBitmap.width, imageBitmap.height)
        val rectOnCanvas = Rect(
            image.x, image.y, image.x + image.width, image.y + image.height
        ) + offset
        // Draw the bitmap on the canvas at the center of the page
        canvas.drawBitmap(softwareBitmap, rectOnImage, rectOnCanvas, null)

        pageDrawingLog.i("Image drawn successfully!")
        AppResult.Success(Unit)
    } catch (e: Exception) {
        pageDrawingLog.e("Failed to render bitmap to canvas", e)
        AppResult.Error(DomainError.DrawingError("Canvas rendering failed: ${e.message}"))
    }
}


fun drawDebugRectWithLabels(
    canvas: Canvas, rect: RectF, rectColor: Int = Color.RED, labelColor: Int = Color.BLUE
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
        bottomRightLabel, rect.right - bottomRightTextWidth - 8f, rect.bottom - 8f, labelPaint
    )
}


fun drawOnCanvasFromPage(
    page: PageView,
    canvas: Canvas,
    canvasClipBounds: Rect,
    pageArea: Rect,
    ignoredStrokeIds: List<String> = listOf(),
    ignoredImageIds: List<String> = listOf(),
): AppResult<Unit, DomainError> {
    val zoomLevel = page.zoomLevel.value
    val backgroundType = page.pageDataManager.getBackgroundType() ?: BackgroundType.Native
    val background = page.pageDataManager.getBackgroundName()
    pageDrawingLog.d("drawOnCanvasFromPage, zoom: $zoomLevel, background: $background, type: $backgroundType")

    var persistentError: DomainError? = null

    // Canvas is scaled, it will scale page area.
    canvas.withClip(canvasClipBounds) {
        // for debugging:
        drawColor(Color.WHITE)

//        drawBg(page.context, this, backgroundType, background, page.scroll, zoomLevel, page, page.currentPageNumber)
        page.drawBgToCanvas(null)
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
                drawImage(page.context, this, image, -page.scroll).onError { error ->
                    pageDrawingLog.e("Individual image failed: ${error.userMessage}")
                    persistentError = persistentError?.let { it + error } ?: error
                }
            }
        } catch (e: Exception) {
            pageDrawingLog.e("PageView.kt(${page.currentPageId}): Images failed", e)
            val error = if (e.message?.contains("permission") == true) {
                DomainError.DrawingError("Permission denied: Unable to access image.")
            } else {
                DomainError.DrawingError("Failed to load images.")
            }
            persistentError = persistentError?.let { it + error } ?: error
        }
        try {
            page.strokes.forEach { stroke ->
                if (ignoredStrokeIds.contains(stroke.id)) return@forEach
                val bounds = strokeBounds(stroke)
                // if stroke is not inside page section
                if (!bounds.toRect().intersect(pageArea)) return@forEach

                drawStroke(this, stroke, -page.scroll)
            }
        } catch (e: Exception) {
            val error = DomainError.DrawingError("Strokes failed: ${e.message ?: e.toString()}")
            pageDrawingLog.e("PageView.kt: ${error.userMessage}", e)
            persistentError = persistentError?.let { it + error } ?: error
        }
    }
    pageDrawingLog.d(
        "drawOnCanvasFromPage, finished drawing to canvas: ${canvas.hashCode()}"
    )
    return persistentError?.let { AppResult.Error(it) } ?: AppResult.Success(Unit)
}