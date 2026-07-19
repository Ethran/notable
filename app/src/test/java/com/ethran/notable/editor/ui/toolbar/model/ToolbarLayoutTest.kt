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
}
