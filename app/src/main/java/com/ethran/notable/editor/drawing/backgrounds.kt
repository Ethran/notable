package com.ethran.notable.editor.drawing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import com.ethran.notable.R
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.utils.scaleRect
import com.ethran.notable.io.getPdfPageCount
import com.ethran.notable.io.loadBackgroundBitmap
import com.ethran.notable.utils.logCallStack
import com.onyx.android.sdk.extension.copy
import com.onyx.android.sdk.extension.isNotNull
import io.shipbook.shipbooksdk.ShipBook
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private val backgroundsLog = ShipBook.getLogger("BackgroundsLog")

const val padding = 0
const val lineHeight = 80
const val dotSize = 6f
const val hexVerticalCount = 26


// Default paint for lines, dots, etc
private val defaultPaint = Paint().apply {
    this.color = Color.GRAY
    this.strokeWidth = 1f
}

// For drawing Hexagons
private val defaultPaintStroke = defaultPaint.copy().apply { this.style = Paint.Style.STROKE }
private val marginPaint = Paint().apply {
    this.color = Color.MAGENTA
    this.strokeWidth = 2f
}
private val paginationLinePaint = Paint().apply {
    color = Color.RED
    strokeWidth = 4f
    pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
}

fun drawLinedBg(canvas: Canvas, scroll: Offset, scale: Float) {
    val height = (canvas.height / scale).toInt()
    val width = (canvas.width / scale).toInt()

    // white bg
    canvas.drawColor(Color.WHITE)


    val offset = IntOffset(lineHeight, lineHeight) - IntOffset(
        scroll.x.toInt() % lineHeight, scroll.y.toInt() % lineHeight
    )

    for (y in 0..height step lineHeight) {
        canvas.drawLine(
            padding.toFloat(),
            y.toFloat() + offset.y,
            (width - padding).toFloat(),
            y.toFloat() + offset.y,
            defaultPaint
        )
    }
}

fun drawDottedBg(canvas: Canvas, scroll: Offset, scale: Float) {
    val height = (canvas.height / scale).toInt()
    val width = (canvas.width / scale).toInt()
    // white bg
    canvas.drawColor(Color.WHITE)


    // dots
    val offset = IntOffset(lineHeight, lineHeight) - IntOffset(
        scroll.x.toInt() % lineHeight, scroll.y.toInt() % lineHeight
    )

    for (y in 0..height step lineHeight) {
        for (x in padding..width - padding step lineHeight) {
            canvas.drawOval(
                x.toFloat() + offset.x - dotSize / 2,
                y.toFloat() + offset.y - dotSize / 2,
                x.toFloat() + offset.x + dotSize / 2,
                y.toFloat() + offset.y + dotSize / 2,
                defaultPaint
            )
        }
    }

}

fun drawSquaredBg(canvas: Canvas, scroll: Offset, scale: Float) {
    val height = (canvas.height / scale).toInt()
    val width = (canvas.width / scale).toInt()

    // white bg
    canvas.drawColor(Color.WHITE)

    // paint

    val offset = IntOffset(lineHeight, lineHeight) - IntOffset(
        scroll.x.toInt() % lineHeight, scroll.y.toInt() % lineHeight
    )

    for (y in 0..height step lineHeight) {
        canvas.drawLine(
            padding.toFloat(),
            y.toFloat() + offset.y,
            (width - padding).toFloat(),
            y.toFloat() + offset.y,
            defaultPaint
        )
    }

    for (x in padding..width - padding step lineHeight) {
        canvas.drawLine(
            x.toFloat() + offset.x,
            padding.toFloat(),
            x.toFloat() + offset.x,
            height.toFloat(),
            defaultPaint
        )
    }
}

fun drawHexedBg(canvas: Canvas, scroll: Offset, scale: Float) {
    val height = (canvas.height / scale)
    val width = (canvas.width / scale)

    // background
    canvas.drawColor(Color.WHITE)


    // https://www.redblobgames.com/grids/hexagons/#spacing
    val r = max(width, height) / (hexVerticalCount * 1.5f) * scale
    val hexHeight = r * 2
    val hexWidth = r * sqrt(3f)

    val rows = (height / hexVerticalCount).toInt()
    val cols = (width / hexWidth).toInt() + 1

    for (row in 0..rows) {
        val offsetX = if (row % 2 == 0) 0f else hexWidth / 2
        for (col in 0..cols) {
            val x = col * hexWidth + offsetX - scroll.x.mod(hexWidth) - hexWidth
            val y = row * hexHeight * 0.75f - scroll.y.mod(hexHeight * 1.5f)
            drawHexagon(canvas, x, y, r)
        }
    }
}

fun drawHexagon(canvas: Canvas, centerX: Float, centerY: Float, r: Float) {
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
    canvas.drawPath(path, defaultPaintStroke)
}

fun drawBackgroundImages(
    context: Context,
    canvas: Canvas,
    backgroundImage: String,
    scroll: Offset,
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
            backgroundsLog.e("Failed to load image from $backgroundImage")
        }
    } catch (e: Exception) {
        backgroundsLog.e("Error loading background image: ${e.message}", e)
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
    scroll: Offset,
    page: PageView? = null,
    scale: Float = 1.0f
) {
    if (pageNumber < 0) {
        backgroundsLog.e("Page number should not be ${pageNumber}, uri: $pdfUriString")
        logCallStack("DrawPdfPage")
        return
    }
    try {
        val imageBitmap = if (page != null) {
            page.getOrLoadBackground(pdfUriString, pageNumber, scale)
        } else {
            // here, if we don't have page, we assume are doing export,
            // so background have to be in better quality
            // (it is scaled down, but still takes whole screen, not like when we render it)
            loadBackgroundBitmap(pdfUriString, pageNumber, 1f)
        }
        if (imageBitmap.isNotNull()) {
            drawBitmapToCanvas(canvas, imageBitmap, scroll, scale, false)
        }

    } catch (e: Exception) {
        backgroundsLog.e("drawPdfPage: Failed to render PDF", e)
    }
}

fun drawBitmapToCanvas(
    canvas: Canvas, imageBitmap: Bitmap, scroll: Offset, scale: Float, repeat: Boolean
) {
    canvas.drawColor(Color.WHITE)
    val imageWidth = imageBitmap.width
    val imageHeight = imageBitmap.height


//    val canvasWidth = canvas.width
    val canvasHeight = canvas.height
    val widthOnCanvas = min(SCREEN_WIDTH, SCREEN_HEIGHT)

    val scaleFactor = widthOnCanvas.toFloat() / imageWidth
    val scaledHeight = (imageHeight * scaleFactor).toInt()

    // TODO: It's working, but its not nice -- do it in better style.
    // Draw the first image, considering the scroll offset
    val srcTop = Offset(
        (scroll.x / scaleFactor).coerceAtLeast(0f),
        ((scroll.y / scaleFactor) % imageHeight).coerceAtLeast(0f)
    )
    val rectOnImage = Rect(0, srcTop.y.toInt(), imageWidth, imageHeight)
    val rectOnCanvas = Rect(
        -scroll.x.toInt(),
        0,
        widthOnCanvas - scroll.x.toInt(),
        ((imageHeight - srcTop.y) * scaleFactor).toInt()
    )

    var filledHeight = 0
    if (repeat || scroll.y < canvasHeight) {
        canvas.drawBitmap(imageBitmap, rectOnImage, rectOnCanvas, null)
        filledHeight = rectOnCanvas.bottom
    }
    // TODO: Should we also repeat horizontally?

    if (repeat) {
        var currentTop = filledHeight
        val srcRect = Rect(0, 0, imageWidth, imageHeight)
        while (currentTop < canvasHeight / scale) {

            val dstRect = Rect(
                -scroll.x.toInt(),
                currentTop,
                widthOnCanvas - scroll.x.toInt(),
                currentTop + scaledHeight
            )
            canvas.drawBitmap(imageBitmap, srcRect, dstRect, null)
            currentTop += scaledHeight
        }
    }
}

fun drawBg(
    context: Context,
    canvas: Canvas,
    backgroundType: BackgroundType,
    background: String,
    scroll: Offset = Offset.Zero,
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
            drawBackgroundImages(context, canvas, background, Offset.Zero, page, scale)
            drawTitleBox(canvas)
        }

        is BackgroundType.AutoPdf -> {
            if (page == null) return
            val pageNumber = page.currentPageNumber
            if (pageNumber < getPdfPageCount(background)) drawPdfPage(
                canvas, background, pageNumber, scroll, page, scale
            )
            else canvas.drawColor(Color.WHITE)
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
    drawMargin(canvas, scroll, scale)

    if (GlobalAppSettings.current.visualizePdfPagination) {
        drawPaginationLine(canvas, scroll, scale)
    }
    if (clipRect != null) {
        canvas.restore()
    }
}

// TODO: make sure it respects horizontal scroll
fun drawMargin(canvas: Canvas, scroll: Offset, scale: Float) {
    // in landscape orientation add margin to indicate what will be visible in vertical orientation.
    if (SCREEN_WIDTH > SCREEN_HEIGHT || scale < 1.0f || scroll.x > 1) {
        val margin = min(SCREEN_HEIGHT, SCREEN_WIDTH) - scroll.x
        // Draw vertical line with x= SCREEN_HEIGHT
        canvas.drawLine(
            margin, padding.toFloat(), margin, (SCREEN_HEIGHT / scale - padding), marginPaint
        )
    }
}

fun drawPaginationLine(canvas: Canvas, scroll: Offset, scale: Float) {
    val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 24f
        isAntiAlias = true
    }

    // A4 paper ratio (height/width in portrait)
    val a4Ratio = 297f / 210f
    val screenWidth = min(SCREEN_HEIGHT, SCREEN_WIDTH)
    val pageHeight = screenWidth * a4Ratio

    // Convert scroll position to canvas coordinates
    // Calculate current page number (1-based)
    val currentPage = floor(scroll.y / pageHeight).toInt() + 1

    // Calculate position of first page break
    var yPos = (currentPage * pageHeight) - scroll.y

    var pageNum = currentPage
    while (yPos < canvas.height / scale) {
        if (yPos >= 0) { // Only draw visible lines
            val yPosScaled = yPos
            canvas.drawLine(
                0f, yPosScaled, screenWidth.toFloat(), yPosScaled, paginationLinePaint
            )

            // Draw page number label (offset slightly below the line)
            canvas.drawText(
                "Subpage ${pageNum + 1}", 20f - scroll.x, yPosScaled + 30f, textPaint
            )
        } else {
            backgroundsLog.d("Skipping line at $yPos (above visible area)")
        }
        yPos += pageHeight
        pageNum++
    }
}