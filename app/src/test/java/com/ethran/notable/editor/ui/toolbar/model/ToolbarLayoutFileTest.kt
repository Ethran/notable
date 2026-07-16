package com.ethran.notable.editor.ui.toolbar.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

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
