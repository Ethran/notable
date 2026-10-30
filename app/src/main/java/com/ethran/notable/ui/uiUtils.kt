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
import kotlin.math.ceil
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


fun sideBarsRectangles(
    pageMode: PageMode?,
    viewWidth: Int,
    viewHeight: Int,
    zoomLevel: Float,
    context: Context
): List<Rect> {
    val rectList = mutableListOf<Rect>()
    if (pageMode != null && pageMode != PageMode.INFINITE) {
        // The sheet's true physical width, whatever the viewport's: a sheet is never snapped to
        // the screen, so these touch-exclusion rects stay on the real sheet edge in every
        // orientation. Fitting the sheet to the width is an explicit user preference
        // (FitMode), applied as a zoom — not a rewrite of the sheet's size.
        val sheetWidth = convertPointsToPixel(pageMode.width, context) * zoomLevel
        if (sheetWidth < viewWidth) {
            val offset = Pair(ceil((viewWidth - sheetWidth) / 2).toInt(), viewHeight)

            // Define sideExcludeRect
            val sideExcludeRect = Rect(0, 0, offset.first, offset.second)
            rectList.add( // Add left rectangle
                sideExcludeRect
            )
            val sheetWidthInt = sheetWidth.roundToInt()

            log.d(
                """
                Sheet width: $sheetWidth
                To int: $sheetWidthInt
                viewWidth: $viewWidth
                offset: $offset
                zoomLevel $zoomLevel
            """.trimIndent()
            )

            rectList.add( // Add right rectangle
                Rect(sideExcludeRect).apply {
                    offset(sheetWidthInt + offset.first, 0)
                }
            )
        }
    }
    return rectList
}