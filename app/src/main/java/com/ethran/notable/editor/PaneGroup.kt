package com.ethran.notable.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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

    init {
        // Two views of one document cannot be made coherent: each pane owns its own window bitmap
        // and its own History, so a stroke committed by one leaves the other's bitmap stale and an
        // undo in one is invisible to the other. Refreshing harder does not fix it — the state is
        // genuinely duplicated. Fail loudly rather than ship the confusing half-working version.
        val ids = panes.map { it.page.currentPageId }
        require(ids.distinct().size == ids.size) {
            "Panes must show distinct pages, got $ids"
        }
    }

    var active: Pane by mutableStateOf(panes.first())
        private set

    /** Direct input and the toolbar at [pane]. Ignores panes that are not part of this group. */
    fun focus(pane: Pane): Boolean {
        if (pane !in panes || pane === active) return false
        active = pane
        return true
    }

    /** The pane containing a surface coordinate, or null for the gutter or chrome. */
    fun paneAt(x: Float, y: Float): Pane? = panes.firstOrNull { it.contains(x, y) }
}
