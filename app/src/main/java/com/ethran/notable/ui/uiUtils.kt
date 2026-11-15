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
import com.ethran.notable.data.model.SheetSize
import kotlin.math.ceil

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
 * The strips beside [sheet] when, drawn at [zoomLevel], it is narrower than the view: one on each
 * side, centring it. Empty for an infinite page or a sheet at least as wide as the view.
 */
fun sideBarsRectangles(
    sheet: SheetSize?,
    viewWidth: Int,
    viewHeight: Int,
    zoomLevel: Float,
): List<Rect> {
    if (sheet == null) return emptyList()
    val sheetWidth = sheet.width * zoomLevel
    // Less than half a pixel of slack is float rounding of a fit, not a strip.
    if (sheetWidth > viewWidth - 0.5f) return emptyList()
    val barWidth = ceil((viewWidth - sheetWidth) / 2).toInt()
    return listOf(
        Rect(0, 0, barWidth, viewHeight),
        Rect(viewWidth - barWidth, 0, viewWidth, viewHeight),
    )
}
