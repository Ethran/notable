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
import androidx.compose.ui.unit.IntOffset
import androidx.core.graphics.withScale
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.datastore.PageMode
import com.ethran.notable.ui.convertPointsToPixel
import com.ethran.notable.ui.sheetAdjustedWidth
import com.onyx.android.sdk.extension.copy
import io.shipbook.shipbooksdk.ShipBook
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private val log = ShipBook.getLogger("BackgroundsLog")

const val padding = 0
const val lineHeight = 80
const val dotSize = 6f
const val hexVerticalCount = 26


// Default paint for lines, dots, etc
private val defaultPaint = Paint().apply {
    this.color = Color.GRAY
    this.strokeWidth = 1f
}

private val sideBarPaint = Paint().apply {
    this.color = Color.rgb(120, 120, 120)
}

// For drawing Hexagons
private val defaultPaintStroke = defaultPaint.copy().apply { this.style = Paint.Style.STROKE }
private val marginPaint = Paint().apply {
    this.color = Color.MAGENTA
    this.strokeWidth = 2.5f
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


// Backgrounds are rendered oversampled (see loadBackgroundBitmap's resolutionModifier) and drawn
// downscaled onto the screen canvas. Bilinear filtering + dithering makes that downscale crisp
// instead of aliased, and the dither maps grey edges cleanly onto e-ink. Costs no extra memory.
private val bgBitmapPaint = Paint().apply {
    isFilterBitmap = true
    isAntiAlias = true
    isDither = true
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
        canvas.drawBitmap(imageBitmap, rectOnImage, rectOnCanvas, bgBitmapPaint)
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
            canvas.drawBitmap(imageBitmap, srcRect, dstRect, bgBitmapPaint)
            currentTop += scaledHeight
        }
    }
}

fun drawDotPattern(canvas: Canvas, rect: Rect, scale: Float) {

    canvas.withScale( // Use no scale to apply the dow pattern
        1f / scale, 1f / scale
    ) {


        // Background
        /*
         * TODO: Remove when/if the background rendering is overhauled.
         * Ideally, background drawing functions should be constrained to the sheet bounds
         * instead of drawing across the entire canvas, eliminating the need to cover
         * the areas outside the sheet with white rectangles.
         *
         * For now, they draw across the entire canvas, so the white rectangles are necessary.
         */
        canvas.drawRect(
            rect.left.toFloat(),
            rect.top.toFloat(),
            rect.right.toFloat(),
            rect.bottom.toFloat(),
            Paint().apply { color = Color.WHITE }
        )

        val spacing = 9
        val radius = 1.25f

        for (y in rect.top until rect.bottom step spacing) {
            for (x in rect.left until rect.right step spacing) {
                canvas.drawCircle(
                    x.toFloat(),
                    y.toFloat(),
                    radius,
                    sideBarPaint
                )
            }
        }

        paginationLinePaint.pathEffect = DashPathEffect(
            floatArrayOf(10f, 10f), // dash length, gap length
            0f
        )
        var startX = 0f
        startX = if (rect.left == 0) { // if left rectangle
            rect.right.toFloat()
        } else{ // if right rectangle
            rect.left.toFloat()
        }

        canvas.drawLine(
            startX,
            padding.toFloat(),
            startX,
            (canvas.height - padding).toFloat(),
            marginPaint
        )
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

fun drawPaginationLine(
    canvas: Canvas,
    scroll: Offset,
    scale: Float,
    context: Context,
    pageMode: PageMode?
) {
    val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 24f
        isAntiAlias = true
    }
    if (pageMode == null || pageMode == PageMode.INFINITE) return

    val sheetHeightPixels = convertPointsToPixel(pageMode.height, context)
    val sheetAdjustedWidth = sheetAdjustedWidth(
        canvas.width, pageMode.width, context
    )


    // Convert scroll position to canvas coordinates
    // Calculate current page number (1-based)
    val currentPage = floor(scroll.y / sheetHeightPixels).toInt() + 1

    // Calculate position of first page break
    var yPos = (currentPage * sheetHeightPixels) - scroll.y

    val residualScrollX = scroll.x
    val startX = -residualScrollX
    val stopX = -residualScrollX + sheetAdjustedWidth
    log.d(
        """
    Sheet adj width $sheetAdjustedWidth, scrollX $residualScrollX
    StartX $startX, stopX $stopX
    """.trimIndent()
    )

    var pageNum = currentPage
    while (yPos < canvas.height / scale) {
        canvas.drawLine(
            startX,
            yPos,
            stopX,
            yPos,
            paginationLinePaint
        )

        canvas.drawText(
            "Subpage ${pageNum + 1}",
            20f - residualScrollX,
            yPos + 30f,
            textPaint
        )

        yPos += sheetHeightPixels
        pageNum++
    }
}