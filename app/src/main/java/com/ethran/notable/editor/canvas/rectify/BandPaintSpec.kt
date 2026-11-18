package com.ethran.notable.editor.canvas.rectify

import com.ethran.notable.editor.utils.Pen

/**
 * How the live rubber-band is painted, as data rather than as a `Paint`, so the rule can be
 * asserted in a JVM test. The adapter turns a spec into a `Paint` on device.
 *
 * @param strokeWidthPx the pen width already scaled by page zoom, in screen pixels.
 * @param color the active pen colour.
 * @param darken true for the highlighter, whose band must read as translucent over existing ink
 *   rather than as an opaque bar.
 */
data class BandPaintSpec(
    val strokeWidthPx: Float,
    val color: Int,
    val darken: Boolean,
)

/**
 * Builds the band's paint rule for [pen].
 *
 * The MARKER branch is the fix for the highlighter otherwise drawing as a solid bar during a
 * rectify: every other pen paints normally, because only the highlighter is meant to let the page
 * through. The band is a single straight `drawLine` rather than an overlapping stroke run, so
 * DARKEN also keeps it to one shade where a normal highlighter pass would darken itself.
 */
fun rectifyBandPaint(
    pen: Pen,
    color: Int,
    strokeWidthPx: Float,
): BandPaintSpec = BandPaintSpec(
    strokeWidthPx = strokeWidthPx,
    color = color,
    darken = pen == Pen.MARKER,
)
