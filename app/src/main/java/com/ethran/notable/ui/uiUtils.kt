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
import com.ethran.notable.data.model.PageMode
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

/** Converts PDF points to pixels using the display's horizontal DPI. */
fun convertPointsToPixel(points: Int, context: Context): Float {
    return points * context.resources.displayMetrics.xdpi / 72f
}

/** Calculate the size of sidebars rectangles*/
fun sideBarsRectangles(
    pageMode: PageMode?,
    viewWidth: Int,
    viewHeight: Int,
    zoomLevel: Float,
    context: Context
): List<Rect> {
    val rectList = mutableListOf<Rect>()
    if (pageMode != null && pageMode != PageMode.INFINITE) {
        val sheetWidth = convertPointsToPixel(pageMode.width, context) * zoomLevel
        if (sheetWidth < viewWidth) {
            val offset = Pair(ceil((viewWidth - sheetWidth) / 2).toInt(), viewHeight)

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