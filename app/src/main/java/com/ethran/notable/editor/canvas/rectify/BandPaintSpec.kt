package com.ethran.notable.editor.canvas.rectify

import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.rawInputMaxPressure

/**
 * How the live rubber-band is painted, as data rather than as a `Paint`, so the rule can be
 * asserted in a JVM test. The adapter turns a spec into a `Paint` on device.
 *
 * @param pen the pen the band is drawn with, which decides whether the band is stamped.
 * @param strokeWidthPx the pen width already scaled by page zoom, in screen pixels.
 * @param strokeWidth the pen's own width, unscaled — the nib, matching `Stroke.size`.
 * @param color the active pen colour.
 * @param darken true for the highlighter, whose band must read as translucent over existing ink
 *   rather than as an opaque bar.
 * @param stamped true for pens drawn by their own wrapper rather than as a flat line.
 * @param pressure the gesture's pressure level, on the digitizer's raw scale.
 */
data class BandPaintSpec(
    val pen: Pen,
    val strokeWidthPx: Float,
    val strokeWidth: Float,
    val color: Int,
    val darken: Boolean,
    val stamped: Boolean = false,
    val pressure: Float = rawInputMaxPressure,
)

/**
 * Builds the band's paint rule for [pen].
 *
 * The MARKER branch is the fix for the highlighter otherwise drawing as a solid bar during a
 * rectify: every other pen paints normally, because only the highlighter is meant to let the page
 * through. The band is a single straight `drawLine` rather than an overlapping stroke run, so
 * DARKEN also keeps it to one shade where a normal highlighter pass would darken itself.
 *
 * The stamped pens — pencil, charcoal, brush, fountain — are the ones whose grain or
 * pressure-shaped width a `drawLine` cannot produce. MARKER, the ballpoints and calligraphy stay
 * flat: their bands are already right, and the highlighter must keep its plain translucent bar.
 */
fun rectifyBandPaint(
    pen: Pen,
    color: Int,
    strokeWidthPx: Float,
    strokeWidth: Float,
    pressure: Float = rawInputMaxPressure,
): BandPaintSpec = BandPaintSpec(
    pen = pen,
    strokeWidthPx = strokeWidthPx,
    strokeWidth = strokeWidth,
    color = color,
    darken = pen == Pen.MARKER,
    stamped = pen in stampedBandPens,
    pressure = pressure,
)

/** Pens whose band is drawn by their own wrapper rather than as a flat line. */
private val stampedBandPens = setOf(Pen.PENCIL, Pen.CHARCOAL, Pen.BRUSH, Pen.FOUNTAIN)

/**
 * Folds [reading] into a running mean over [count] samples, ignoring a zero or absent reading.
 *
 * Averaged over the gesture rather than its endpoints: the pen-down tap and the lift are the two
 * lightest points, so an average of just those collapses to near nothing, and a pressure-shaped pen
 * renders its width from that level.
 *
 * In and out are units of [maxPressure], the digitizer's raw range. It is passed in rather than read
 * from `rawInputMaxPressure` so this stays testable on the JVM, where there is no digitizer.
 */
internal fun foldBandPressure(
    mean: Float,
    count: Int,
    reading: Float,
    maxPressure: Float,
    fullPressure: Float = maxPressure,
): Float {
    if (reading <= 0f) return if (count == 0) fullPressure else mean.coerceIn(0f, maxPressure)
    val next = if (count == 0) reading else mean + (reading - mean) / (count + 1)
    return next.coerceIn(0f, maxPressure)
}
