package com.ethran.notable.editor.ui.toolbar.model

import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.utils.Pen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarElementsTest {

    private val pens = ToolbarPen.DEFAULT_PENS

    @Test
    fun `every static id resolves to an element`() {
        ToolbarElementId.entries.forEach { id ->
            if (id == ToolbarElementId.PEN) return@forEach // sentinel for pen instances
            val element = ToolbarElements.all[id]
            assertNotNull("No ToolbarElement registered for $id", element)
            assertEquals("Element registered under wrong id", id, element!!.id)
        }
    }

    @Test
    fun `registry contains exactly the static ids`() {
        assertEquals(ToolbarElementId.entries.size - 1, ToolbarElements.all.size)
        assertFalse(
            "Pen instances must not live in the static registry",
            ToolbarElements.all.values.any { it is PenElement },
        )
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
    fun `layout entries resolve pens by preset and statics by name`() {
        val ball = ToolbarElements.resolve("PEN:ball", pens)
        assertTrue(ball is PenElement)
        assertEquals("ball", (ball as PenElement).presetId)
        assertEquals(Pen.BALLPEN, ball.pen)

        assertEquals(
            ToolbarElements.of(ToolbarElementId.ERASER),
            ToolbarElements.resolve("ERASER", pens),
        )
        assertNull("Deleted preset must not resolve", ToolbarElements.resolve("PEN:gone", pens))
        assertNull("Bare PEN sentinel must not resolve", ToolbarElements.resolve("PEN", pens))
        assertNull(ToolbarElements.resolve("FROM_THE_FUTURE", pens))
    }

    @Test
    fun `pen element selected in draw and line mode with matching preset only`() {
        val ball = ToolbarElements.penElement(pens.first { it.id == "ball" })
        assertTrue(ball.isSelected(ToolbarUiState(mode = Mode.Draw, penPresetId = "ball")))
        assertTrue(ball.isSelected(ToolbarUiState(mode = Mode.Line, penPresetId = "ball")))
        // Same base pen type, different preset: not selected.
        assertFalse(ball.isSelected(ToolbarUiState(mode = Mode.Draw, penPresetId = "red")))
        assertFalse(ball.isSelected(ToolbarUiState(mode = Mode.Erase, penPresetId = "ball")))
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
    fun `default presets only use placeable base pen types`() {
        pens.forEach { preset ->
            assertTrue(
                "Preset ${preset.id} uses non-placeable base type ${preset.pen}",
                preset.pen in ToolbarPen.BASE_TYPES,
            )
        }
        assertFalse(Pen.DASHED in ToolbarPen.BASE_TYPES)
        assertFalse(Pen.REDBALLPEN in ToolbarPen.BASE_TYPES)
    }

    @Test
    fun `default preset ids are unique and match the default layout references`() {
        assertEquals(pens.size, pens.map { it.id }.distinct().size)
        val penEntries = (ToolbarLayout.DEFAULT.scrollable + ToolbarLayout.DEFAULT.pinned)
            .filter { it.startsWith(ToolbarPen.LAYOUT_PREFIX) }
        assertEquals(
            "DEFAULT layout must place every default preset exactly once",
            pens.map { it.layoutEntry }.toSet(),
            penEntries.toSet(),
        )
        assertEquals(penEntries.size, penEntries.distinct().size)
    }

    @Test
    fun `default pen settings are the single source of truth for the viewmodel`() {
        // Same object: EditorViewModel must alias the presets, not re-declare values.
        assertTrue(EditorViewModel.DEFAULT_PEN_SETTINGS === ToolbarPen.defaultPenSettings)
        pens.forEach { preset ->
            assertEquals(
                "Default setting drifted for preset ${preset.id}",
                preset.setting(),
                ToolbarPen.defaultPenSettings[preset.id],
            )
        }
        assertEquals(pens.size, ToolbarPen.defaultPenSettings.size)
    }

    @Test
    fun `collapsed icon falls back to dashed-line when no preset matches`() {
        val state = ToolbarUiState(mode = Mode.Draw, penPresetId = "deleted-preset")
        assertEquals(
            IconRef.Drawable(com.ethran.notable.R.drawable.line_dashed),
            ToolbarElements.presentlyUsedToolIcon(state, pens),
        )
    }

    @Test
    fun `presently used tool icon prefers the mode tool over the pen in line mode`() {
        val lineState = ToolbarUiState(mode = Mode.Line, penPresetId = "ball")
        assertEquals(
            ToolbarElements.of(ToolbarElementId.SHAPE).icon,
            ToolbarElements.presentlyUsedToolIcon(lineState, pens),
        )
        val drawState = ToolbarUiState(mode = Mode.Draw, penPresetId = "marker")
        assertEquals(
            ToolbarElements.penIcon(Pen.MARKER),
            ToolbarElements.presentlyUsedToolIcon(drawState, pens),
        )
    }
}
