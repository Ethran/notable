package com.ethran.notable.editor.ui.toolbar.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarLayoutTest {

    private val pens = ToolbarPen.DEFAULT_PENS

    @Test
    fun `default layout is a validator fixed point`() {
        assertEquals(ToolbarLayout.DEFAULT, ToolbarLayout.DEFAULT.validated(pens))
    }

    @Test
    fun `default layout only references known entries`() {
        (ToolbarLayout.DEFAULT.scrollable + ToolbarLayout.DEFAULT.pinned).forEach { name ->
            assertNotNull(
                "Unknown entry in DEFAULT: $name",
                ToolbarElements.resolve(name, pens),
            )
        }
    }

    @Test
    fun `validator drops unknown names`() {
        val layout = ToolbarLayout(
            scrollable = listOf("PEN:ball", "FROM_THE_FUTURE", "ERASER"),
            pinned = listOf("NOPE", "MENU"),
        ).validated(pens)
        assertEquals(listOf("PEN:ball", "ERASER"), layout.scrollable)
        assertEquals(listOf("MENU"), layout.pinned)
    }

    @Test
    fun `validator drops pen entries whose preset was deleted`() {
        val layout = ToolbarLayout(
            scrollable = listOf("PEN:ball", "PEN:deleted", "ERASER"),
            pinned = listOf("MENU"),
        ).validated(pens)
        assertEquals(listOf("PEN:ball", "ERASER"), layout.scrollable)
    }

    @Test
    fun `validator drops the bare pen sentinel`() {
        val layout = ToolbarLayout(
            scrollable = listOf("PEN", "PEN:ball"),
            pinned = listOf("MENU"),
        ).validated(pens)
        assertEquals(listOf("PEN:ball"), layout.scrollable)
    }

    @Test
    fun `validator drops duplicates across zones keeping first occurrence`() {
        val layout = ToolbarLayout(
            scrollable = listOf("PEN:ball", "PEN:ball", "ERASER"),
            pinned = listOf("ERASER", "MENU"),
        ).validated(pens)
        assertEquals(listOf("PEN:ball", "ERASER"), layout.scrollable)
        assertEquals(listOf("MENU"), layout.pinned)
    }

    @Test
    fun `two presets of the same base pen type may both be placed`() {
        // Not duplicates: distinct presets, one button each.
        val layout = ToolbarLayout(
            scrollable = listOf("PEN:ball", "PEN:red"),
            pinned = listOf("MENU"),
        ).validated(pens)
        assertEquals(listOf("PEN:ball", "PEN:red"), layout.scrollable)
    }

    @Test
    fun `validator allows repeated dividers`() {
        val layout = ToolbarLayout(
            scrollable = listOf("DIVIDER", "PEN:ball", "DIVIDER"),
            pinned = listOf("DIVIDER", "MENU"),
        ).validated(pens)
        assertEquals(listOf("DIVIDER", "PEN:ball", "DIVIDER"), layout.scrollable)
        assertEquals(listOf("DIVIDER", "MENU"), layout.pinned)
    }

    @Test
    fun `validator appends menu to pinned when missing`() {
        val layout = ToolbarLayout(
            scrollable = listOf("PEN:ball"),
            pinned = listOf("UNDO"),
        ).validated(pens)
        assertEquals(listOf("UNDO", "MENU"), layout.pinned)
    }

    @Test
    fun `validator keeps menu in scrollable if placed there`() {
        val layout = ToolbarLayout(
            scrollable = listOf("MENU"),
            pinned = emptyList(),
        ).validated(pens)
        assertEquals(listOf("MENU"), layout.scrollable)
        assertTrue(layout.pinned.isEmpty())
    }

    @Test
    fun `validator drops structural toggle`() {
        val layout = ToolbarLayout(
            scrollable = listOf("TOGGLE", "PEN:ball"),
            pinned = listOf("MENU"),
        ).validated(pens)
        assertEquals(listOf("PEN:ball"), layout.scrollable)
    }

    @Test
    fun `serialization round-trips by name`() {
        val json = Json.encodeToString(ToolbarLayout.serializer(), ToolbarLayout.DEFAULT)
        assertTrue("entries must serialize as names", json.contains("\"PEN:ball\""))
        val decoded = Json.decodeFromString(ToolbarLayout.serializer(), json)
        assertEquals(ToolbarLayout.DEFAULT, decoded)
    }

    @Test
    fun `unknown names survive deserialization and are dropped by validation`() {
        val json = """{"scrollable":["PEN:ball","TEXT_FROM_V99"],"pinned":["MENU"]}"""
        val decoded = Json.decodeFromString(ToolbarLayout.serializer(), json).validated(pens)
        assertEquals(listOf("PEN:ball"), decoded.scrollable)
        assertEquals(listOf("MENU"), decoded.pinned)
    }

    // --- one-time seeds: an element added in a later version reaching saved layouts ---

    @Test
    fun `seeding puts a new element directly after its anchor and leaves the rest alone`() {
        val seeded = ToolbarLayout(
            scrollable = listOf("PEN:ball", "ERASER", "DIVIDER", "PASTE", "RESET_VIEW", "DIVIDER"),
            pinned = listOf("UNDO", "REDO", "MENU"),
        ).withSeeded(ToolbarElementId.FIT_MODE, ToolbarElementId.RESET_VIEW)
        assertEquals(
            "the new entry lands next to its anchor, wherever the anchor sits",
            listOf(
                "PEN:ball", "ERASER", "DIVIDER", "PASTE", "RESET_VIEW",
                ToolbarElementId.FIT_MODE.name, "DIVIDER",
            ),
            seeded.scrollable,
        )
        assertEquals(listOf("UNDO", "REDO", "MENU"), seeded.pinned)
    }

    @Test
    fun `seeding follows the anchor into whichever zone holds it`() {
        val seeded = ToolbarLayout(
            scrollable = listOf("PEN:ball"),
            pinned = listOf("RESET_VIEW", "MENU"),
        ).withSeeded(ToolbarElementId.FIT_MODE, ToolbarElementId.RESET_VIEW)
        assertTrue(
            "the entry must not be pushed into the scrolling zone",
            ToolbarElementId.FIT_MODE.name !in seeded.scrollable,
        )
        assertEquals(
            listOf("RESET_VIEW", ToolbarElementId.FIT_MODE.name, "MENU"),
            seeded.pinned,
        )
    }

    @Test
    fun `seeding changes nothing when the element is already placed`() {
        assertEquals(
            "DEFAULT places the entry itself, so seeding it is a no-op",
            ToolbarLayout.DEFAULT,
            ToolbarLayout.DEFAULT.withSeeded(ToolbarElementId.FIT_MODE, ToolbarElementId.RESET_VIEW),
        )
    }

    @Test
    fun `seeding respects a user who hid the anchor element`() {
        // No anchor means no place to seed into: putting the new button somewhere the user never
        // asked for would ignore a deliberate choice.
        val hidden = ToolbarLayout(
            scrollable = listOf("PEN:ball", "ERASER"),
            pinned = listOf("MENU"),
        )
        assertEquals(hidden, hidden.withSeeded(ToolbarElementId.FIT_MODE, ToolbarElementId.RESET_VIEW))
    }
}
