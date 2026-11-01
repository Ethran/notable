package com.ethran.notable.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pen draws only when nothing on screen needs the finger/pen for itself. Every input
 * that blocks drawing is listed once here; adding a new one means adding it to this list.
 */
class ToolbarUiStateDrawingTest {

    private val blockers: List<Pair<String, (ToolbarUiState) -> ToolbarUiState>> = listOf(
        "menu open" to { it.copy(isMenuOpen = true) },
        "stroke menu open" to { it.copy(isStrokeSelectionOpen = true) },
        "background selector open" to { it.copy(isBackgroundSelectorModalOpen = true) },
        "QuickNav open" to { it.copy(isQuickNavOpen = true) },
        "selection active" to { it.copy(isSelectionActive = true) },
        "selection gesture in progress" to { it.copy(isSelectionGestureActive = true) },
        "window not focused" to { it.copy(hasWindowFocus = false) },
    )

    @Test
    fun drawsWhenNothingBlocks() {
        assertTrue(ToolbarUiState().isDrawing)
    }

    @Test
    fun drawsOnlyWhenNoBlockerIsSet() {
        // Every combination of blockers, so no pair can cancel out or be order-dependent.
        for (mask in 0 until (1 shl blockers.size)) {
            val applied = blockers.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            val state = applied.fold(ToolbarUiState()) { s, (_, block) -> block(s) }
            assertEquals(applied.map { it.first }.toString(), applied.isEmpty(), state.isDrawing)
        }
    }
}
