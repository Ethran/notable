package com.ethran.notable.editor.ui.toolbar.model

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
