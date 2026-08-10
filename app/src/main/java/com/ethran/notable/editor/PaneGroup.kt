package com.ethran.notable.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ethran.notable.editor.canvas.CanvasEventBus

/**
 * The panes on screen, and which of them input and the toolbar act on.
 *
 * Active-pane state lives here rather than inside `DrawCanvas` because more than the canvas needs
 * it: undo, redo, paste and the toolbar all have to act on the pane the user is looking at, and
 * they are wired up in the editor's composition, outside the view.
 *
 * [active] is Compose state so the UI recomposes when focus moves.
 */
class PaneGroup(val panes: List<Pane>) {

    var active: Pane by mutableStateOf(panes.first())
        private set

    // Declared after [active] deliberately: init blocks and property initialisers run in
    // declaration order, so publishing the bus above this point would read an uninitialised
    // delegate.
    init {
        // Two views of one document cannot be made coherent: each pane owns its own window bitmap
        // and its own History, so a stroke committed by one leaves the other's bitmap stale and an
        // undo in one is invisible to the other. Refreshing harder does not fix it — the state is
        // genuinely duplicated. Fail loudly rather than ship the confusing half-working version.
        val ids = panes.map { it.page.currentPageId }
        require(ids.distinct().size == ids.size) {
            "Panes must show distinct pages, got $ids"
        }
        publishActiveBus()
    }

    /** Direct input and the toolbar at [pane]. Ignores panes that are not part of this group. */
    fun focus(pane: Pane): Boolean {
        if (pane !in panes || pane === active) return false
        active = pane
        publishActiveBus()
        return true
    }

    /**
     * Point [CanvasEventBus.active] at the focused pane's bus.
     *
     * This belongs to focus, not to view construction. `PageView.init` used to assign it, which was
     * correct for one view and silently wrong for two: each new `PageView` overwrote it, so it
     * ended up pinned to whichever pane was *constructed last* and never moved again. Every
     * app-level emitter that has no view in hand — resume refresh, the background selector,
     * quick-nav scrubbing, `restoreCanvas` — was therefore addressing the second pane no matter
     * which one the user was writing in.
     */
    private fun publishActiveBus() {
        CanvasEventBus.active = active.events
    }

    /** The pane containing a surface coordinate, or null for the gutter or chrome. */
    fun paneAt(x: Float, y: Float): Pane? = panes.firstOrNull { it.contains(x, y) }

    /**
     * The pane that is not [pane], or null when there is only one.
     *
     * Two panes is the deliverable, so this is well defined today. With three it would have to
     * become a list and every caller would need to say which one it meant — the picker's
     * "other pane" target, for one.
     */
    fun other(pane: Pane): Pane? = panes.firstOrNull { it !== pane }
}
