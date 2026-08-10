package com.ethran.notable.editor.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page counter and everything derived from a page's position.
 *
 * These are the rules that were previously spread across `loadToolbarState`, `loadBookData` and a
 * `bookId` held from the editor's route — where they could disagree, and did. Pure, so they are
 * testable without a device, which is the point of deriving them (STATE-PLAN §5).
 */
class PageLocationTest {

    private val bookPages = listOf("p1", "p2", "p3")

    @Test
    fun `a quick page belongs to no notebook`() {
        val location = PageLocation.of("loose", notebookId = null, pageIds = emptyList())

        assertFalse(location.isInNotebook)
        assertEquals(null, location.notebookId)
    }

    @Test
    fun `a quick page reads as a single page`() {
        // Not "0/0" or an empty string: a quick page is a whole document, it is just a short one.
        assertEquals("1/1", PageLocation.of("loose", null, emptyList()).label)
    }

    @Test
    fun `a quick page ignores any page list handed to it`() {
        // Guards the ordering of the null check: a loose page must not pick up a position from a
        // notebook it does not belong to.
        val location = PageLocation.of("loose", notebookId = null, pageIds = bookPages)

        assertEquals(0, location.index)
        assertEquals(1, location.count)
        assertEquals("1/1", location.label)
    }

    @Test
    fun `a notebook page reports its one-based position`() {
        val location = PageLocation.of("p2", "book", bookPages)

        assertTrue(location.isInNotebook)
        assertEquals(1, location.index)
        assertEquals(3, location.count)
        assertEquals("2/3", location.label)
    }

    @Test
    fun `the first and last pages read correctly`() {
        assertEquals("1/3", PageLocation.of("p1", "book", bookPages).label)
        assertEquals("3/3", PageLocation.of("p3", "book", bookPages).label)
    }

    @Test
    fun `a one-page notebook reads as one of one`() {
        // The case that produced "0/3": a count left over from a longer notebook. Resolving the
        // count alongside the index is what makes that unrepresentable.
        val location = PageLocation.of("only", "book", listOf("only"))

        assertEquals("1/1", location.label)
        assertTrue(location.isInNotebook)
    }

    @Test
    fun `a page missing from its notebook falls back to the first position`() {
        // A data inconsistency, repaired by fixNotebook. It must not throw or produce "0/3".
        val location = PageLocation.of("orphan", "book", bookPages)

        assertEquals(0, location.index)
        assertEquals("1/3", location.label)
    }

    @Test
    fun `a notebook with no pages still counts as one`() {
        // Division and "of N" displays both misbehave on zero; there is always at least the page
        // being looked at.
        val location = PageLocation.of("p1", "book", emptyList())

        assertEquals(1, location.count)
        assertEquals("1/1", location.label)
    }
}
