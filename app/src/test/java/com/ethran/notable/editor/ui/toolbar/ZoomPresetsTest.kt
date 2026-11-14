package com.ethran.notable.editor.ui.toolbar

import com.ethran.notable.editor.ToolbarUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ZoomPresetsTest {
    @Test fun `labels and preset choices are stable`() {
        assertEquals(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f), ZoomPresets.levels)
        assertEquals("150%", ZoomPresets.label(1.5f))
    }

    @Test fun `labels round rather than truncate`() {
        assertEquals("126%", ZoomPresets.label(1.255f))
    }

    @Test fun `open zoom menu blocks drawing`() {
        assertFalse(ToolbarUiState(isZoomMenuOpen = true).isDrawingAllowed)
    }
}
