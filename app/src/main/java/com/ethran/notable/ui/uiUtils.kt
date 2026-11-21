package com.ethran.notable.ui

import android.content.Context
import android.util.TypedValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.Dp
import kotlin.math.abs

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

private const val MM_PER_INCH = 25.4f

/**
 * xdpi/ydpi are OEM-filled and on these devices are often just set to densityDpi, which measures
 * nothing. Letting that guess decide how big a millimetre is would swing it by half, so the case
 * falls back to a nominal pitch instead: 10 px/mm is ~254 ppi, between the ~8.9 a 10" panel and
 * the ~11.8 a 7" one really give, so 2 mm stays in the same ballpark on every screen size.
 */
private const val NOMINAL_PX_PER_MM = 10f
private const val DENSITY_IS_PITCH_TOLERANCE = 2f

// Anything outside this is not a panel pitch: 4 px/mm is ~100 ppi, 24 is ~600.
private const val MIN_PX_PER_MM = 4f
private const val MAX_PX_PER_MM = 24f

/**
 * Screen pixels per millimetre of physical panel, so a distance means the same physical thing on a
 * 7" and a 13" device. dp cannot do that here: these devices report `densityDpi` 160, making one
 * dp exactly one px.
 */
fun pixelsPerMillimetre(context: Context): Float {
    val dm = context.resources.displayMetrics
    val dpi = (dm.xdpi + dm.ydpi) / 2f
    return if (abs(dpi - dm.densityDpi) < DENSITY_IS_PITCH_TOLERANCE) NOMINAL_PX_PER_MM
    else (dpi / MM_PER_INCH).coerceIn(MIN_PX_PER_MM, MAX_PX_PER_MM)
}

/** [mm] of physical panel, as screen pixels on [context]'s display. */
fun convertMmToPixel(mm: Float, context: Context): Float =
    mm * pixelsPerMillimetre(context)

