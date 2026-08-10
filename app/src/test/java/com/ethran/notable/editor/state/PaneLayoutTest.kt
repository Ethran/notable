package com.ethran.notable.editor.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rules about which documents may share the screen.
 *
 * These were previously encoded twice — `PaneGroup` validating a pane set, and the picker deciding
 * what to offer — and covered different cases, so a page *turn* was checked by neither. Stated once
 * here, and pure, so they can be tested without a device.
 */
class PaneLayoutTest {

    private val notes = PaneSlot("notes-p1", "notes")
    private val diary = PaneSlot("diary-p1", "diary")
    private val loose = PaneSlot("loose-1")

    @Test
    fun `a page from an unopened notebook may be opened`() {
        val layout = PaneLayout(listOf(notes, diary))

        assertNull(layout.rejectionFor(0, PaneSlot("other-p1", "other")))
    }

    @Test
    fun `a page held by another pane is rejected`() {
        val layout = PaneLayout(listOf(notes, diary))

        assertEquals(
            PaneRejection.PageHeldElsewhere("diary-p1"),
            layout.rejectionFor(0, diary),
        )
    }

    @Test
    fun `another page of another pane's notebook is rejected`() {
        val layout = PaneLayout(listOf(notes, diary))

        // Not the page the other pane shows, but the same notebook. This is what
        // one-notebook-per-pane adds over a bare distinct-pages check.
        assertEquals(
            PaneRejection.NotebookHeldElsewhere("diary"),
            layout.rejectionFor(0, PaneSlot("diary-p7", "diary")),
        )
    }

    @Test
    fun `another page of the pane's own notebook is allowed`() {
        val layout = PaneLayout(listOf(notes, diary))

        // Jumping from page 1 to page 40 of the notebook you are in is a normal move, and is what
        // the favourites row and the scrubber are for.
        assertNull(layout.rejectionFor(0, PaneSlot("notes-p40", "notes")))
    }

    @Test
    fun `the page a pane already shows is a no-op`() {
        val layout = PaneLayout(listOf(notes, diary))

        assertEquals(PaneRejection.AlreadyShown, layout.rejectionFor(0, notes))
    }

    @Test
    fun `re-opening your own notebook is caught as a no-op through its landing page`() {
        // A notebook opens at its last-opened page, which updateOpenedPage records on every page
        // change — so for the notebook you are in, that is the page you are on. Asking about the
        // page it would actually open means no separate notebook rule is needed.
        val layout = PaneLayout(listOf(notes, diary))
        val landingPage = PaneSlot("notes-p1", "notes")

        assertEquals(PaneRejection.AlreadyShown, layout.rejectionFor(0, landingPage))
    }

    @Test
    fun `quick pages collide only by page`() {
        val layout = PaneLayout(listOf(loose, notes))

        assertEquals(
            PaneRejection.PageHeldElsewhere("loose-1"),
            layout.rejectionFor(1, loose),
        )
        // A null notebook must not match another null notebook, or every quick page would exclude
        // every other one.
        assertNull(layout.rejectionFor(1, PaneSlot("loose-2")))
    }

    @Test
    fun `a new pane treats every existing pane as another pane`() {
        val layout = PaneLayout(listOf(notes, diary))

        // Splitting: nothing can be "already shown", because there is no pane yet.
        assertEquals(
            PaneRejection.PageHeldElsewhere("notes-p1"),
            layout.rejectionFor(null, notes),
        )
        assertEquals(
            PaneRejection.NotebookHeldElsewhere("diary"),
            layout.rejectionFor(null, PaneSlot("diary-p7", "diary")),
        )
        assertNull(layout.rejectionFor(null, PaneSlot("other-p1", "other")))
    }

    @Test
    fun `an empty layout allows anything`() {
        assertNull(PaneLayout(emptyList()).rejectionFor(null, notes))
    }

    @Test
    fun `a legal set is accepted`() {
        assertNull(PaneLayout.rejectionAmong(listOf(notes, diary)))
        assertNull(PaneLayout.rejectionAmong(listOf(loose, notes)))
        assertNull(PaneLayout.rejectionAmong(listOf(notes)))
    }

    @Test
    fun `a set with two panes on one page is rejected`() {
        assertEquals(
            PaneRejection.PageHeldElsewhere("notes-p1"),
            PaneLayout.rejectionAmong(listOf(notes, notes)),
        )
    }

    @Test
    fun `a set with two panes in one notebook is rejected`() {
        assertEquals(
            PaneRejection.NotebookHeldElsewhere("notes"),
            PaneLayout.rejectionAmong(listOf(notes, PaneSlot("notes-p7", "notes"))),
        )
    }

    @Test
    fun `slots whose notebook is not yet known are compared on page alone`() {
        // A page record loads asynchronously, so a pane's notebook is null for a moment. Treating
        // that as a conflict would reject a legal layout; comparing pages still catches the case
        // that actually corrupts state.
        val unknownA = PaneSlot("a", notebookId = null)
        val unknownB = PaneSlot("b", notebookId = null)

        assertNull(PaneLayout.rejectionAmong(listOf(unknownA, unknownB)))
        assertEquals(
            PaneRejection.PageHeldElsewhere("a"),
            PaneLayout.rejectionAmong(listOf(unknownA, unknownA)),
        )
    }
}
