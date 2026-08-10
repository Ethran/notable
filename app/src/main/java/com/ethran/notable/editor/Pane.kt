package com.ethran.notable.editor

import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.editor.canvas.PaneEventBus
import com.ethran.notable.editor.state.History

/**
 * One editable view of a page, and the region of the shared drawing surface it occupies.
 *
 * The editor draws every pane into a **single** `SurfaceView`, because the Onyx firmware has one
 * raw-drawing state for the whole panel: exactly one surface in the process can accept ink, so
 * panes cannot be separate views. A pane is therefore a *region plus its state*, not a widget.
 *
 * Everything a pane needs is already per-instance — [PageView] owns a `PageRenderer` (its window
 * buffer), a `ViewportState` (its scroll and zoom) and a [PaneEventBus] (its signals). This class
 * bundles those with the pane's undo history, its in-flight stroke batch, and where it sits on
 * screen.
 *
 * While there is one pane, [screenRect] covers the whole surface — a degenerate case rather than a
 * special one, which is what keeps single-pane behaviour identical.
 */
class Pane(
    val page: PageView,
    val history: History,
) {
    /**
     * Where this pane sits on the shared surface, in screen pixels.
     *
     * Empty until the surface has been laid out; [layout] is called from the surface-changed
     * callback. Strokes are assigned to panes by hit-testing against this.
     */
    var screenRect: Rect = Rect()
        private set

    /**
     * Top-left of this pane on the shared surface. Raw firmware points arrive in surface
     * coordinates, so this must be subtracted before converting them to page coordinates.
     */
    val origin: Offset
        get() = Offset(screenRect.left.toFloat(), screenRect.top.toFloat())

    /** Signals scoped to this pane. Shorthand for `page.events`. */
    val events: PaneEventBus
        get() = page.events

    /**
     * The notebook this pane currently has open, or null for a loose page or before the record
     * loads. A notebook may be open in at most one pane — see ROADMAP §8 — so this is what the
     * page picker filters on.
     */
    val notebookId: String?
        get() = page.openPage.notebookId

    /** The page this pane currently shows. Shorthand for `page.currentPageId`. */
    val pageId: String
        get() = page.currentPageId

    /**
     * Ids of strokes drawn since the last history commit, batched so a burst of strokes becomes
     * one undo step. Per-pane: undoing in one pane must not disturb the other.
     */
    val strokeHistoryBatch = mutableListOf<String>()

    fun layout(rect: Rect) {
        screenRect = Rect(rect)
    }

    /** True if the given surface coordinate falls inside this pane. */
    fun contains(x: Float, y: Float): Boolean =
        screenRect.contains(x.toInt(), y.toInt())
}
