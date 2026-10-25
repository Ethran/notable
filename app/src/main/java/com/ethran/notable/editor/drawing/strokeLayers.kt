package com.ethran.notable.editor.drawing

import android.graphics.Canvas
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.editor.utils.Pen

/** Alpha the highlighter layer as a whole is composited at. */
const val HIGHLIGHTER_ALPHA = 128

val Stroke.isHighlighter: Boolean
    get() = pen == Pen.MARKER

/**
 * Draws [strokes] in two passes: every highlighter stroke first, into a single offscreen layer
 * composited at [HIGHLIGHTER_ALPHA], then every other stroke on top of it.
 *
 * The layer is what keeps overlapping highlighter strokes one shade. Inside it the bands are
 * opaque, so an overlap is the union of identical colours rather than a blend, and the alpha is
 * applied once, to that union, when the layer is composited. A per-stroke alpha cannot do this:
 * SRC_OVER of a translucent band over a translucent band always accumulates.
 *
 * Two consequences of there being exactly one layer:
 * - all highlighter ink shares a single z-position, behind every pen stroke, whatever the
 *   insertion order — which is also what makes highlighted text stay legible;
 * - where two *different* highlighter colours overlap, the later stroke wins the overlap
 *   outright instead of blending.
 *
 * The layer is bounded by both the current clip and the union of the visible highlighter bounds,
 * so a redraw with no highlighter in the dirty region costs nothing and an export does not
 * automatically allocate a second bitmap as large as the whole page. Sizing it from the stored
 * bounds is safe because every path that creates a stroke pads them by the full stroke size
 * (`handleDraw`, the .xopp importer), leaving half a stroke width of slack around the band the
 * layer must not clip.
 *
 * [shouldDraw] filters both passes and is evaluated once for each stroke.
 */
fun drawStrokesLayered(
    canvas: Canvas,
    strokes: List<Stroke>,
    offset: Offset,
    shouldDraw: (Stroke) -> Boolean = { true },
) {
    var highlighters: MutableList<Stroke>? = null
    var layerBounds: RectF? = null

    for (stroke in strokes) {
        if (!stroke.isHighlighter || !shouldDraw(stroke)) continue

        if (highlighters == null) highlighters = mutableListOf()
        highlighters.add(stroke)

        val left = stroke.left + offset.x
        val top = stroke.top + offset.y
        val right = stroke.right + offset.x
        val bottom = stroke.bottom + offset.y
        if (layerBounds == null) layerBounds = RectF(left, top, right, bottom)
        else layerBounds.union(left, top, right, bottom)
    }

    if (highlighters != null && layerBounds != null && !layerBounds.isEmpty) {
        val layerRestoreCount = canvas.saveLayerAlpha(layerBounds, HIGHLIGHTER_ALPHA)
        try {
            for (stroke in highlighters) {
                StrokeRenderers.current.drawStroke(canvas, stroke, offset)
            }
        } finally {
            canvas.restoreToCount(layerRestoreCount)
        }
    }

    for (stroke in strokes) {
        if (stroke.isHighlighter || !shouldDraw(stroke)) continue
        StrokeRenderers.current.drawStroke(canvas, stroke, offset)
    }
}
