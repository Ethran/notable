package com.ethran.notable.ui.viewmodels

import com.ethran.notable.data.db.Page
import com.ethran.notable.ui.viewmodels.QuickNavViewModel.PaneExclusion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the page picker may offer for a given pane.
 *
 * A notebook may be open in at most one pane and no two panes may show the same page (ROADMAP §8).
 * The picker enforces that by not offering a colliding page at all, so this predicate is the whole
 * rule — there is no downstream check to fall back on.
 */
class PaneExclusionTest {

    private fun page(id: String, notebookId: String? = null) = Page(id = id, notebookId = notebookId)

    @Test
    fun `nothing is excluded when the other pane holds nothing`() {
        val exclusion = PaneExclusion.NONE
        assertTrue(exclusion.allowsPage(page("p1", "book-a")))
        assertTrue(exclusion.allowsPage(page("loose")))
    }

    @Test
    fun `the page the other pane shows is excluded`() {
        val exclusion = PaneExclusion(occupiedNotebookId = "book-a", occupiedPageId = "p1")
        assertFalse(exclusion.allowsPage(page("p1", "book-a")))
    }

    @Test
    fun `any page of the other pane's notebook is excluded`() {
        val exclusion = PaneExclusion(occupiedNotebookId = "book-a", occupiedPageId = "p1")
        // Not the page the other pane shows, but the same notebook — this is the case the
        // one-notebook-per-pane rule adds over a bare distinct-pages check.
        assertFalse(exclusion.allowsPage(page("p2", "book-a")))
    }

    @Test
    fun `pages of a different notebook are allowed`() {
        val exclusion = PaneExclusion(occupiedNotebookId = "book-a", occupiedPageId = "p1")
        assertTrue(exclusion.allowsPage(page("p9", "book-b")))
    }

    @Test
    fun `a loose page is allowed beside a notebook`() {
        val exclusion = PaneExclusion(occupiedNotebookId = "book-a", occupiedPageId = "p1")
        assertTrue(exclusion.allowsPage(page("loose")))
    }

    @Test
    fun `a loose page collides only by id`() {
        // The other pane shows a loose page: it has no notebook, so notebook matching must not
        // exclude every other loose page along with it.
        val exclusion = PaneExclusion(occupiedNotebookId = null, occupiedPageId = "loose-1")
        assertFalse(exclusion.allowsPage(page("loose-1")))
        assertTrue(exclusion.allowsPage(page("loose-2")))
        assertTrue(exclusion.allowsPage(page("p1", "book-a")))
    }

    @Test
    fun `the target pane's own notebook is not offered`() {
        // Opening a notebook lands on its last-opened page, which for the notebook you are already
        // in is the page you are already on — a no-op that warned "tried to change to the same
        // page". Illegal and pointless are different reasons, hence two predicates.
        val exclusion = PaneExclusion(occupiedNotebookId = "book-a", targetNotebookId = "book-b")

        assertFalse(exclusion.allowsNotebook("book-a"))
        assertFalse(exclusion.allowsNotebook("book-b"))
        assertTrue(exclusion.allowsNotebook("book-c"))
    }

    @Test
    fun `other pages of the target's own notebook stay on offer`() {
        // The notebook is excluded from the *notebook* row only. Jumping to another page of the
        // notebook you are in is a good move, and it is what the favourites row is for.
        val exclusion = PaneExclusion(targetNotebookId = "book-a", targetPageId = "p1")

        assertFalse(exclusion.allowsPage(page("p1", "book-a")))
        assertTrue(exclusion.allowsPage(page("p2", "book-a")))
    }

    @Test
    fun `the page the target already shows is not offered`() {
        val exclusion = PaneExclusion(targetPageId = "here")

        assertFalse(exclusion.allowsPage(page("here")))
        assertTrue(exclusion.allowsPage(page("elsewhere")))
    }

    @Test
    fun `a null notebook on the exclusion does not exclude notebook pages`() {
        // Regression guard: comparing null == null would exclude every loose page, and comparing
        // loosely would exclude notebook pages against a loose other-pane.
        val exclusion = PaneExclusion(occupiedNotebookId = null, occupiedPageId = "loose-1")
        assertTrue(exclusion.allowsPage(page("loose-2", notebookId = null)))
    }
}
