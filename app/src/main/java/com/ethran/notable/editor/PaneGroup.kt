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
class PaneGroup(initialPanes: List<Pane>) {

    /**
     * The panes on screen, in left-to-right order.
     *
     * Mutable, and the group instance outlives any change to it. Splitting used to build a *new*
     * PaneGroup and rebuild the canvas under a Compose `key` to match — which disposed the
     * `SurfaceView`, and a device trace showed the replacement surface was not allocated for six
     * seconds, until a rotation forced it. The screen was blank for that whole time. So the pane
     * set changes underneath a canvas that keeps living, and `DrawCanvas.syncPanes` reconciles.
     */
    var panes: List<Pane> by mutableStateOf(initialPanes)
        private set

    var active: Pane by mutableStateOf(initialPanes.first())
        private set

    // Declared after [panes] and [active] deliberately: init blocks and property initialisers run
    // in declaration order, so touching either above this point would read an uninitialised
    // delegate.
    init {
        requireShowDistinctPages(initialPanes)
        publishActiveBus()
    }

    /**
     * Replace the pane set.
     *
     * If the focused pane is no longer present — closing a split from the pane being removed —
     * focus falls back to the first remaining one rather than dangling.
     */
    fun updatePanes(newPanes: List<Pane>) {
        // Idempotent: this is driven from a Compose update block, so it runs on every
        // recomposition and must not churn state when nothing changed.
        if (newPanes == panes) return
        requireShowDistinctPages(newPanes)
        panes = newPanes
        if (active !in newPanes) active = newPanes.first()
        publishActiveBus()
    }

    /**
     * Two views of one document cannot be made coherent: each pane owns its own window bitmap and
     * its own History, so a stroke committed by one leaves the other's bitmap stale and an undo in
     * one is invisible to the other. Refreshing harder does not fix it — the state is genuinely
     * duplicated. Fail loudly rather than ship the confusing half-working version.
     */
    private fun requireShowDistinctPages(candidate: List<Pane>) {
        require(candidate.isNotEmpty()) { "A pane group needs at least one pane" }
        val ids = candidate.map { it.page.currentPageId }
        require(ids.distinct().size == ids.size) {
            "Panes must show distinct pages, got $ids"
        }
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
