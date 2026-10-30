package com.ethran.notable.data.datastore

import com.ethran.notable.editor.ui.toolbar.model.ToolbarElementId
import com.ethran.notable.editor.ui.toolbar.model.ToolbarLayout
import com.ethran.notable.editor.ui.toolbar.model.ToolbarPen
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsToolbarLayoutTest {

    // Same configuration as KvProxy's Json instance.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `settings persisted before the field decode with a null layout and default pens`() {
        val decoded = json.decodeFromString(AppSettings.serializer(), """{"version":1}""")
        assertNull(decoded.toolbarLayout)
        assertEquals(ToolbarPen.DEFAULT_PENS, decoded.toolbarPens)
    }

    @Test
    fun `toolbarPens round-trip through AppSettings serialization`() {
        val settings = AppSettings(
            version = 1,
            toolbarPens = ToolbarPen.DEFAULT_PENS + ToolbarPen(
                id = "a1b2c3d4",
                pen = com.ethran.notable.editor.utils.Pen.FOUNTAIN,
                color = android.graphics.Color.MAGENTA,
                size = 7f,
            ),
        )
        val decoded = json.decodeFromString(
            AppSettings.serializer(),
            json.encodeToString(AppSettings.serializer(), settings),
        )
        assertEquals(settings.toolbarPens, decoded.toolbarPens)
    }

    @Test
    fun `toolbarLayout round-trips through AppSettings serialization`() {
        val settings = AppSettings(
            version = 1,
            toolbarLayout = ToolbarLayout(
                scrollable = listOf("PEN:ball", "DIVIDER", "ERASER"),
                pinned = listOf("UNDO", "MENU"),
            ),
        )
        val decoded = json.decodeFromString(
            AppSettings.serializer(),
            json.encodeToString(AppSettings.serializer(), settings),
        )
        assertEquals(settings.toolbarLayout, decoded.toolbarLayout)
    }

    // --- one-time toolbar layout seeds (see MainActivity's settings load) ---

    @Test
    fun `settings saved before the seed marker decode as unseeded, so existing users get seeded`() {
        val decoded = json.decodeFromString(
            AppSettings.serializer(),
            """{"version":1,"toolbarLayout":{"scrollable":["RESET_VIEW"],"pinned":["MENU"]}}""",
        )
        assertFalse(
            "an old settings blob must not look already-seeded, or the seed would never reach it",
            decoded.toolbarLayoutSeeded,
        )
    }

    @Test
    fun `a layout saved before the element gains it once, and the seed never runs again`() {
        val saved = AppSettings(
            version = 1,
            toolbarLayout = ToolbarLayout(
                scrollable = listOf("PEN:ball", "RESET_VIEW"),
                pinned = listOf("MENU"),
            ),
        )

        val seeded = saved.withSeededToolbarLayout()
        assertEquals(
            listOf("PEN:ball", "RESET_VIEW", ToolbarElementId.FIT_MODE.name),
            seeded.toolbarLayout?.scrollable,
        )
        assertTrue("the marker must be set, or the seed would repeat on every launch", seeded.toolbarLayoutSeeded)

        // The marker is what makes it one-shot: a user who removes the button afterwards keeps it
        // removed, even though their layout still has the anchor and lacks the entry.
        val afterUserRemoved = seeded.copy(
            toolbarLayout = seeded.toolbarLayout?.copy(
                scrollable = seeded.toolbarLayout.scrollable.filterNot { it == ToolbarElementId.FIT_MODE.name },
            ),
        )
        assertSame(afterUserRemoved, afterUserRemoved.withSeededToolbarLayout())
    }

    @Test
    fun `a toolbar nobody customised needs no seed but is still marked seeded`() {
        // `toolbarLayout == null` means ToolbarLayout.DEFAULT, which already places the element — so
        // nothing is written into the layout, and the marker stops the seed being reconsidered.
        val seeded = AppSettings(version = 1).withSeededToolbarLayout()
        assertNull(seeded.toolbarLayout)
        assertTrue(seeded.toolbarLayoutSeeded)
    }
}
