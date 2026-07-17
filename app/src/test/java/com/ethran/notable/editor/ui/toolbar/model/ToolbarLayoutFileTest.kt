package com.ethran.notable.editor.ui.toolbar.model

import com.ethran.notable.editor.utils.Pen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import android.graphics.Color as AndroidColor

class ToolbarLayoutFileTest {

    private val pens = ToolbarPen.DEFAULT_PENS

    @Test
    fun `export then import round-trips the default layout`() {
        val text = ToolbarLayoutFile.encode(ToolbarLayout.DEFAULT, pens)
        val result = ToolbarLayoutFile.decode(text)
        assertEquals(ToolbarLayout.DEFAULT, result.layout)
        assertEquals(pens, result.pens)
        assertEquals(0, result.droppedCount)
    }

    @Test
    fun `envelope self-identifies`() {
        val text = ToolbarLayoutFile.encode(ToolbarLayout.DEFAULT, pens)
        assertTrue(text.contains("\"type\": \"${ToolbarLayoutFile.TYPE}\""))
        assertTrue(text.contains("\"version\": ${ToolbarLayoutFile.VERSION}"))
    }

    @Test
    fun `arbitrary json is rejected with a readable message`() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolbarLayoutFile.decode("""{"foo": "bar"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolbarLayoutFile.decode("not json at all")
        }
    }

    @Test
    fun `wrong type field is rejected even when the shape matches`() {
        val text = ToolbarLayoutFile.encode(ToolbarLayout.DEFAULT, pens)
            .replace(ToolbarLayoutFile.TYPE, "some-other-format")
        assertThrows(IllegalArgumentException::class.java) { ToolbarLayoutFile.decode(text) }
    }

    @Test
    fun `newer file version is rejected`() {
        val text = ToolbarLayoutFile.encode(ToolbarLayout.DEFAULT, pens)
            .replace("\"version\": ${ToolbarLayoutFile.VERSION}", "\"version\": 99")
        assertThrows(IllegalArgumentException::class.java) { ToolbarLayoutFile.decode(text) }
    }

    @Test
    fun `duplicate pen ids are deduplicated, first wins, and counted as dropped`() {
        val duplicated = listOf(
            ToolbarPen("dup", Pen.BALLPEN, AndroidColor.RED, 5f),
            ToolbarPen("dup", Pen.MARKER, AndroidColor.BLACK, 40f),
        )
        val layout = ToolbarLayout(scrollable = listOf("PEN:dup"), pinned = listOf("MENU"))
        val result = ToolbarLayoutFile.decode(ToolbarLayoutFile.encode(layout, duplicated))
        assertEquals(1, result.pens.size)
        assertEquals(Pen.BALLPEN, result.pens.single().pen)
        assertEquals(1, result.droppedCount)
    }

    @Test
    fun `empty pen option lists fall back to defaults`() {
        val pen = ToolbarPen(
            "p", Pen.BALLPEN, AndroidColor.BLACK, 5f,
            colorOptions = emptyList(),
            sizeOptions = emptyList(),
        )
        val layout = ToolbarLayout(scrollable = listOf("PEN:p"), pinned = listOf("MENU"))
        val result = ToolbarLayoutFile.decode(ToolbarLayoutFile.encode(layout, listOf(pen)))
        assertEquals(null, result.pens.single().colorOptions)
        assertEquals(null, result.pens.single().sizeOptions)
    }

    @Test
    fun `a single valid size option is kept`() {
        val pen = ToolbarPen("p", Pen.BALLPEN, AndroidColor.BLACK, 5f, sizeOptions = listOf(5f))
        val layout = ToolbarLayout(scrollable = listOf("PEN:p"), pinned = listOf("MENU"))
        val result = ToolbarLayoutFile.decode(ToolbarLayoutFile.encode(layout, listOf(pen)))
        assertEquals(listOf(5f), result.pens.single().sizeOptions)
    }

    @Test
    fun `off-candidate option values are removed, all-foreign lists fall back to defaults`() {
        val pen = ToolbarPen(
            "p", Pen.BALLPEN, AndroidColor.BLACK, 5f,
            colorOptions = listOf(0x123456, AndroidColor.RED), // 0x123456 isn't a candidate
            sizeOptions = listOf(7.5f, 999f), // neither is a candidate
        )
        val layout = ToolbarLayout(scrollable = listOf("PEN:p"), pinned = listOf("MENU"))
        val result = ToolbarLayoutFile.decode(ToolbarLayoutFile.encode(layout, listOf(pen)))
        assertEquals(listOf(AndroidColor.RED), result.pens.single().colorOptions)
        assertEquals(null, result.pens.single().sizeOptions)
    }

    @Test
    fun `duplicates across zones are dropped and counted`() {
        val layout = ToolbarLayout(
            scrollable = listOf("ERASER", "PEN:ball"),
            pinned = listOf("ERASER", "PEN:ball", "MENU"),
        )
        val result = ToolbarLayoutFile.decode(ToolbarLayoutFile.encode(layout, pens))
        assertEquals(listOf("ERASER", "PEN:ball"), result.layout.scrollable)
        assertEquals(listOf("MENU"), result.layout.pinned)
        assertEquals(2, result.droppedCount)
    }

    @Test
    fun `unknown entries are dropped and counted`() {
        val layout = ToolbarLayout(
            scrollable = listOf("PEN:ball", "FROM_THE_FUTURE", "ERASER"),
            pinned = listOf("PEN:deleted-preset", "MENU"),
        )
        val result = ToolbarLayoutFile.decode(ToolbarLayoutFile.encode(layout, pens))
        assertEquals(listOf("PEN:ball", "ERASER"), result.layout.scrollable)
        assertEquals(listOf("MENU"), result.layout.pinned)
        assertEquals(2, result.droppedCount)
    }

    @Test
    fun `missing MENU is appended without inflating the dropped count`() {
        val layout = ToolbarLayout(scrollable = listOf("PEN:ball"), pinned = emptyList())
        val result = ToolbarLayoutFile.decode(ToolbarLayoutFile.encode(layout, pens))
        assertEquals(listOf("MENU"), result.layout.pinned)
        assertEquals(0, result.droppedCount)
    }
}
