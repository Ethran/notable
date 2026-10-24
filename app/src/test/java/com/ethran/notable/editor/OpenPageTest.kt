package com.ethran.notable.editor

import com.ethran.notable.data.db.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class OpenPageTest {

    private fun loaded(id: String, background: String) =
        OpenPage(id, Page(id = id, background = background), pageNumber = 0)

    @Test
    fun load_for_the_shown_page_is_published() {
        val shown = OpenPage("a")
        val result = shown.withLoaded(loaded("a", "lined"))
        assertEquals("lined", result.backgroundName)
    }

    @Test
    fun load_for_a_page_the_view_has_left_is_dropped() {
        val shown = OpenPage("c")
        assertSame(shown, shown.withLoaded(loaded("b", "lined")))
    }

    @Test
    fun late_read_of_a_left_page_does_not_replace_the_current_one() {
        // The view reads page b again, and shows page c before that read finishes.
        val ref = AtomicReference(loaded("b", "blank"))
        ref.set(loaded("c", "squared"))
        ref.updateAndGet { it.withLoaded(loaded("b", "lined")) }

        assertEquals("c", ref.get().pageId)
        assertEquals("squared", ref.get().backgroundName)
    }
}
