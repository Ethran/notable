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
    fun loads_finishing_out_of_order_leave_the_last_page_shown() {
        // Two quick page turns, a -> b -> c, as PageView.changePage runs them: each sets an
        // unloaded snapshot, then publishes its load. c's load finishes before b's.
        val ref = AtomicReference(loaded("a", "blank"))
        ref.set(OpenPage("b"))
        ref.set(OpenPage("c"))
        ref.updateAndGet { it.withLoaded(loaded("c", "squared")) }
        ref.updateAndGet { it.withLoaded(loaded("b", "lined")) }

        assertEquals("c", ref.get().pageId)
        assertEquals("squared", ref.get().backgroundName)
    }
}
