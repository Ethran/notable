package com.ethran.notable.editor.canvas

import com.ethran.notable.editor.Pane

/** A raw stroke assigned to the pane that owns it, with out-of-pane points removed. */
data class PaneStroke<T>(val pane: Pane, val points: List<T>)

/**
 * Assigns a raw stroke to a pane and discards the points that fall outside it.
 *
 * ### Why this cannot simply hit-test the first point
 *
 * The Onyx firmware clips the *live preview* per limit rect, but it does **not** split the stroke.
 * A drag that crosses from one pane into another arrives as a **single**
 * `onRawDrawingTouchPointListReceived` callback whose point list spans both regions — verified on
 * a BOOX Go 10.3, firmware `2026-05-12_4.2-rel`: the stroke renders with a visible gap, yet one
 * undo removes the whole thing.
 *
 * So resolving the owner from `points.first()` and stopping there would write the far-side points
 * into the near pane's document at coordinates outside its own rect — ink silently bleeding into
 * the wrong note, with no visible symptom until the user scrolled and found stray marks.
 *
 * ### Policy: latch and discard
 *
 * The pane is resolved from the first point and latched for the whole stroke; points outside it
 * are dropped. A cross-pane drag therefore writes to exactly one document — the one it started in.
 *
 * The rejected alternative was to split the stroke and commit a run to each pane. That gives exact
 * agreement between wet and dry ink, but lets a careless drag write into two notes at once, which
 * is the worse failure. The accepted cost is that wet ink briefly appears in the far pane during
 * the drag and vanishes on pen-up when that region repaints.
 *
 * Returns null when the stroke is empty, or when it starts outside every pane — on the divider or
 * on chrome. The returned list always contains at least the first point, since that is what
 * selected the pane.
 *
 * Generic over the point type so it can be tested without constructing Onyx SDK objects.
 */
fun <T> routeStrokeToPane(
    points: List<T>,
    panes: List<Pane>,
    x: (T) -> Float,
    y: (T) -> Float,
): PaneStroke<T>? {
    if (points.isEmpty()) return null

    val first = points.first()
    val owner = panes.firstOrNull { it.contains(x(first), y(first)) } ?: return null

    return PaneStroke(owner, points.filter { owner.contains(x(it), y(it)) })
}
