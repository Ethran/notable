package com.ethran.notable.ui

import android.content.Context
import android.util.TypedValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.Dp

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
fun convertPointsToPixel(points: Float, context: Context): Float {
    return points * context.resources.displayMetrics.xdpi / 72f
}
