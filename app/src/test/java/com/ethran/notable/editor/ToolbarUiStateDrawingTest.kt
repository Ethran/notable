package com.ethran.notable.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pen draws only when nothing on screen needs the finger/pen for itself. A table test of
 * [ToolbarUiState.isDrawing]: each listed input must block drawing on its own and in any
 * combination. A blocker missing from this list is not caught.
 */
class ToolbarUiStateDrawingTest {

    private val blockers: List<Pair<String, (ToolbarUiState) -> ToolbarUiState>> = listOf(
        "menu open" to { it.copy(isMenuOpen = true) },
        "eraser menu open" to { it.copy(isEraserMenuOpen = true) },
        "pen stroke menu open" to { it.copy(penMenuPresetId = "pen") },
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
