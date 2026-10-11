package com.ethran.notable.ui

import android.content.Context
import android.graphics.Rect
import android.util.TypedValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ethran.notable.data.datastore.PageMode
import io.shipbook.shipbooksdk.ShipBook
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round
import kotlin.math.roundToInt

val log = ShipBook.getLogger("UI Utils")

fun Modifier.noRippleClickable(
    onClick: () -> Unit
): Modifier = composed {
    clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
        onClick()
    }
}


fun convertDpToPixel(dp: Dp, context: Context): Float {
//    val resources = context.resources
//    val metrics: DisplayMetrics = resources.displayMetrics
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp.value,
        context.resources.displayMetrics
    )
}

/**
 * Converts a value in PDF points to pixels using the display's horizontal DPI.
 *
 * @param points The value in PDF points, where 1 point equals 1/72 of an inch.
 * @param context The Android context used to obtain the display's DPI.
 * @return The equivalent value in pixels.
 */
fun convertPointsToPixel(points: Int, context: Context): Float {
    return points * context.resources.displayMetrics.xdpi / 72f
}

fun mmToPdfPoints(mm: Float): Int {
    return round(mm * 72f / 25.4f).toInt()
}

/**
 * Calculates the sheet width adjusted to the viewport.
 *
 * If the sheet width is within the configured tolerance (2%) of the screen width,
 * the screen width is used; otherwise, the converted sheet width is returned.
 *
 * @param screenWidth the current viewport width in pixels
 * @param pageWidth the original sheet width in PDF points (1/72 inch)
 * @return the adjusted sheet width
 */
fun sheetAdjustedWidth(screenWidth: Int, pageWidth: Int, context: Context): Float {
    val deviationThreshold = convertPointsToPixel(mmToPdfPoints(5f),context)
    val sheetWidth = convertPointsToPixel(pageWidth, context)
    val deviation = abs(sheetWidth - screenWidth)
    val sheetAdjustedWidth  = if (deviation <= deviationThreshold) {
        screenWidth.toFloat()
    } else {
        sheetWidth
    }

    log.d(
        """
            Sheet width: $sheetWidth
            View width: $screenWidth
            Deviation: $deviation
            Deviation threshold: $deviationThreshold
            Sheet width adjusted: $sheetAdjustedWidth
                """.trimIndent()
    )

    return sheetAdjustedWidth
}

fun sideBarsRectangles(
    pageMode: PageMode?,
    viewWidth: Int,
    viewHeight: Int,
    zoomLevel: Float,
    context: Context
): List<Rect> {
    val rectList = mutableListOf<Rect>()
    if (pageMode != null && pageMode != PageMode.INFINITE) {
        val pageWidth = pageMode.width
        val sheetWidthOG = sheetAdjustedWidth(viewWidth, pageWidth, context)
        val sheetAdjustedWidth = sheetWidthOG * zoomLevel
        if (sheetAdjustedWidth < viewWidth) {
            val offset = Pair(ceil((viewWidth - sheetAdjustedWidth) / 2).toInt(), viewHeight)

            // Define sideExcludeRect
            val sideExcludeRect = Rect(0, 0, offset.first, offset.second)
            rectList.add( // Add left rectangle
                sideExcludeRect
            )
            val sheetAdjustedInt = sheetAdjustedWidth.roundToInt()

            log.d(
                """
                Sheet width original: 
                Sheet width adjusted: $sheetAdjustedWidth
                To int: $sheetAdjustedInt
                viewWidth: $viewWidth
                offset: $offset
                zoomLevel $zoomLevel
            """.trimIndent()
            )

            rectList.add( // Add right rectangle
                Rect(sideExcludeRect).apply {
                    offset(sheetAdjustedInt + offset.first, 0)
                }
            )
        }
    }
    return rectList
}