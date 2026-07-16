package com.ethran.notable.editor.ui.toolbar.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarLayoutTest {

    @Test
    fun `default layout is a validator fixed point`() {
        assertEquals(ToolbarLayout.DEFAULT, ToolbarLayout.DEFAULT.validated())
    }

    @Test
    fun `default layout only references known ids`() {
        (ToolbarLayout.DEFAULT.scrollable + ToolbarLayout.DEFAULT.pinned).forEach { name ->
            org.junit.Assert.assertNotNull(
                "Unknown id in DEFAULT: $name",
                ToolbarElementId.fromString(name),
            )
        }
    }

    @Test
    fun `validator drops unknown names`() {
        val layout = ToolbarLayout(
            scrollable = listOf("PEN_BALL", "FROM_THE_FUTURE", "ERASER"),
            pinned = listOf("NOPE", "MENU"),
        ).validated()
        assertEquals(listOf("PEN_BALL", "ERASER"), layout.scrollable)
        assertEquals(listOf("MENU"), layout.pinned)
    }

    @Test
    fun `validator drops duplicates across zones keeping first occurrence`() {
        val layout = ToolbarLayout(
            scrollable = listOf("PEN_BALL", "PEN_BALL", "ERASER"),
            pinned = listOf("ERASER", "MENU"),
        ).validated()
        assertEquals(listOf("PEN_BALL", "ERASER"), layout.scrollable)
        assertEquals(listOf("MENU"), layout.pinned)
    }

    @Test
    fun `validator allows repeated dividers`() {
        val layout = ToolbarLayout(
            scrollable = listOf("DIVIDER", "PEN_BALL", "DIVIDER"),
            pinned = listOf("DIVIDER", "MENU"),
        ).validated()
        assertEquals(listOf("DIVIDER", "PEN_BALL", "DIVIDER"), layout.scrollable)
        assertEquals(listOf("DIVIDER", "MENU"), layout.pinned)
    }

    @Test
    fun `validator appends menu to pinned when missing`() {
        val layout = ToolbarLayout(
            scrollable = listOf("PEN_BALL"),
            pinned = listOf("UNDO"),
        ).validated()
        assertEquals(listOf("UNDO", "MENU"), layout.pinned)
    }

    @Test
    fun `validator keeps menu in scrollable if placed there`() {
        val layout = ToolbarLayout(
            scrollable = listOf("MENU"),
            pinned = emptyList(),
        ).validated()
        assertEquals(listOf("MENU"), layout.scrollable)
        assertTrue(layout.pinned.isEmpty())
    }

    @Test
    fun `validator drops structural toggle`() {
        val layout = ToolbarLayout(
            scrollable = listOf("TOGGLE", "PEN_BALL"),
            pinned = listOf("MENU"),
        ).validated()
        assertEquals(listOf("PEN_BALL"), layout.scrollable)
    }

    @Test
    fun `serialization round-trips by name`() {
        val json = Json.encodeToString(ToolbarLayout.serializer(), ToolbarLayout.DEFAULT)
        assertTrue("ids must serialize as names", json.contains("\"PEN_BALL\""))
        val decoded = Json.decodeFromString(ToolbarLayout.serializer(), json)
        assertEquals(ToolbarLayout.DEFAULT, decoded)
    }

    @Test
    fun `unknown names survive deserialization and are dropped by validation`() {
        val json = """{"scrollable":["PEN_BALL","TEXT_FROM_V99"],"pinned":["MENU"]}"""
        val decoded = Json.decodeFromString(ToolbarLayout.serializer(), json).validated()
        assertEquals(listOf("PEN_BALL"), decoded.scrollable)
        assertEquals(listOf("MENU"), decoded.pinned)
    }

    @Test
    fun `id resolution helpers map names to ids in order`() {
        val layout = ToolbarLayout(
            scrollable = listOf("PEN_BALL", "DIVIDER"),
            pinned = listOf("MENU"),
        )
        assertEquals(
            listOf(ToolbarElementId.PEN_BALL, ToolbarElementId.DIVIDER),
            layout.scrollableIds(),
        )
        assertEquals(listOf(ToolbarElementId.MENU), layout.pinnedIds())
    }
}
