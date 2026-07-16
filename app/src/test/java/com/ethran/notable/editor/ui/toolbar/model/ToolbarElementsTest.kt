package com.ethran.notable.editor.ui.toolbar.model

import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.utils.Pen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarElementsTest {

    @Test
    fun `every id resolves to an element`() {
        ToolbarElementId.entries.forEach { id ->
            val element = ToolbarElements.all[id]
            assertNotNull("No ToolbarElement registered for $id", element)
            assertEquals("Element registered under wrong id", id, element!!.id)
        }
    }

    @Test
    fun `registry contains no elements outside the id enum`() {
        assertEquals(ToolbarElementId.entries.size, ToolbarElements.all.size)
    }

    @Test
    fun `only structural divider lacks an icon among placeable buttons`() {
        ToolbarElements.all.values.forEach { element ->
            if (element.id != ToolbarElementId.DIVIDER && element.id != ToolbarElementId.PAGE_NAV) {
                assertNotNull("${element.id} has no icon", element.icon)
            }
        }
    }

    @Test
    fun `pen element selected in draw and line mode with matching pen only`() {
        val ball = ToolbarElements.of(ToolbarElementId.PEN_BALL)
        assertTrue(ball.isSelected(ToolbarUiState(mode = Mode.Draw, pen = Pen.BALLPEN)))
        assertTrue(ball.isSelected(ToolbarUiState(mode = Mode.Line, pen = Pen.BALLPEN)))
        assertFalse(ball.isSelected(ToolbarUiState(mode = Mode.Draw, pen = Pen.MARKER)))
        assertFalse(ball.isSelected(ToolbarUiState(mode = Mode.Erase, pen = Pen.BALLPEN)))
    }

    @Test
    fun `mode elements selected by mode`() {
        val eraser = ToolbarElements.of(ToolbarElementId.ERASER)
        val select = ToolbarElements.of(ToolbarElementId.SELECT)
        assertTrue(eraser.isSelected(ToolbarUiState(mode = Mode.Erase)))
        assertFalse(eraser.isSelected(ToolbarUiState(mode = Mode.Select)))
        assertTrue(select.isSelected(ToolbarUiState(mode = Mode.Select)))
        assertFalse(select.isSelected(ToolbarUiState(mode = Mode.Draw)))
    }

    @Test
    fun `every pen except the indicator-only DASHED has an element`() {
        val pensWithElements = ToolbarElements.all.values
            .filterIsInstance<PenElement>().map { it.pen }.toSet()
        Pen.entries.forEach { pen ->
            if (pen == Pen.DASHED) {
                assertFalse("DASHED must stay element-less (erase indicator only)", pen in pensWithElements)
            } else {
                assertTrue("Pen $pen has no toolbar element", pen in pensWithElements)
            }
        }
    }

    @Test
    fun `default pen settings are the single source of truth for the viewmodel`() {
        // Same object: EditorViewModel must alias the registry, not re-declare values.
        assertTrue(EditorViewModel.DEFAULT_PEN_SETTINGS === ToolbarElements.defaultPenSettings)
        // And the map matches the specs exactly.
        ToolbarElements.all.values.filterIsInstance<PenElement>().forEach { element ->
            assertEquals(
                "Default setting drifted for ${element.pen}",
                element.defaultSetting,
                ToolbarElements.defaultPenSettings[element.pen.penName],
            )
        }
        assertEquals(
            ToolbarElements.all.values.filterIsInstance<PenElement>().size,
            ToolbarElements.defaultPenSettings.size,
        )
    }

    @Test
    fun `collapsed icon falls back to dashed-line for the element-less DASHED pen`() {
        val state = ToolbarUiState(mode = Mode.Draw, pen = Pen.DASHED)
        assertEquals(
            IconRef.Drawable(com.ethran.notable.R.drawable.line_dashed),
            ToolbarElements.presentlyUsedToolIcon(state),
        )
    }

    @Test
    fun `presently used tool icon prefers the mode tool over the pen in line mode`() {
        val lineState = ToolbarUiState(mode = Mode.Line, pen = Pen.BALLPEN)
        assertEquals(
            ToolbarElements.of(ToolbarElementId.SHAPE).icon,
            ToolbarElements.presentlyUsedToolIcon(lineState),
        )
        val drawState = ToolbarUiState(mode = Mode.Draw, pen = Pen.MARKER)
        assertEquals(
            ToolbarElements.of(ToolbarElementId.PEN_MARKER).icon,
            ToolbarElements.presentlyUsedToolIcon(drawState),
        )
    }
}
