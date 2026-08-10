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
        assertTrue(exclusion.allows(page("p1", "book-a")))
        assertTrue(exclusion.allows(page("loose")))
    }

    @Test
    fun `the page the other pane shows is excluded`() {
        val exclusion = PaneExclusion(notebookId = "book-a", pageId = "p1")
        assertFalse(exclusion.allows(page("p1", "book-a")))
    }

    @Test
    fun `any page of the other pane's notebook is excluded`() {
        val exclusion = PaneExclusion(notebookId = "book-a", pageId = "p1")
        // Not the page the other pane shows, but the same notebook — this is the case the
        // one-notebook-per-pane rule adds over a bare distinct-pages check.
        assertFalse(exclusion.allows(page("p2", "book-a")))
    }

    @Test
    fun `pages of a different notebook are allowed`() {
        val exclusion = PaneExclusion(notebookId = "book-a", pageId = "p1")
        assertTrue(exclusion.allows(page("p9", "book-b")))
    }

    @Test
    fun `a loose page is allowed beside a notebook`() {
        val exclusion = PaneExclusion(notebookId = "book-a", pageId = "p1")
        assertTrue(exclusion.allows(page("loose")))
    }

    @Test
    fun `a loose page collides only by id`() {
        // The other pane shows a loose page: it has no notebook, so notebook matching must not
        // exclude every other loose page along with it.
        val exclusion = PaneExclusion(notebookId = null, pageId = "loose-1")
        assertFalse(exclusion.allows(page("loose-1")))
        assertTrue(exclusion.allows(page("loose-2")))
        assertTrue(exclusion.allows(page("p1", "book-a")))
    }

    @Test
    fun `a null notebook on the exclusion does not exclude notebook pages`() {
        // Regression guard: comparing null == null would exclude every loose page, and comparing
        // loosely would exclude notebook pages against a loose other-pane.
        val exclusion = PaneExclusion(notebookId = null, pageId = "loose-1")
        assertTrue(exclusion.allows(page("loose-2", notebookId = null)))
    }
}
