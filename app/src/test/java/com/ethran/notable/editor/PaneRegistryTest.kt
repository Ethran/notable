package com.ethran.notable.editor

import com.ethran.notable.editor.canvas.PaneEventBus
import com.ethran.notable.editor.state.History
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The three ways to address a pane from code that holds none.
 *
 * They exist as separate accessors because the single `CanvasEventBus.active` pointer they replaced
 * was used to mean all three, and was only ever correct for one of them — see [PaneRegistry] and
 * STATE-PLAN §2.1. These tests pin the distinction, since the whole value of the change is that
 * "every pane", "the pane showing this page" and "the focused pane" answer differently.
 */
class PaneRegistryTest {

    private lateinit var paneOne: Pane
    private lateinit var paneTwo: Pane

    private fun pane(pageId: String): Pane {
        val bus = PaneEventBus()
        val page = mockk<PageView>(relaxed = true) {
            every { currentPageId } returns pageId
            every { events } returns bus
        }
        return Pane(page, mockk<History>(relaxed = true))
    }

    @Before
    fun setUp() {
        paneOne = pane("page-one")
        paneTwo = pane("page-two")
        PaneRegistry.clear()
    }

    @After
    fun tearDown() {
        // Global, so a test that left panes published would leak into the next one.
        PaneRegistry.clear()
    }

    @Test
    fun `with no editor every address is inert`() {
        // Emitting before an editor exists must be a no-op, not a crash: the activity's lifecycle
        // callbacks fire whether or not the editor is on screen.
        assertTrue(PaneRegistry.all.isEmpty())
        assertTrue(PaneRegistry.showing("page-one").isEmpty())
        assertEquals(emptyList<PaneEventBus>(), PaneRegistry.showing("anything"))
    }

    @Test
    fun `all reaches every pane, not just the focused one`() {
        // The resume-redraw bug: addressing one pane left the other blank until something else
        // forced it to repaint.
        PaneRegistry.publish(listOf(paneOne, paneTwo), focused = paneOne)

        assertEquals(listOf(paneOne.events, paneTwo.events), PaneRegistry.all)
    }

    @Test
    fun `showing addresses by document, regardless of focus`() {
        // The background-file bug: a change to the page in the *unfocused* pane must reach that
        // pane, and must not reach the focused one.
        PaneRegistry.publish(listOf(paneOne, paneTwo), focused = paneOne)

        assertEquals(listOf(paneTwo.events), PaneRegistry.showing("page-two"))
        assertNotSame(PaneRegistry.focused, PaneRegistry.showing("page-two").single())
    }

    @Test
    fun `showing a page that is not open reaches nobody`() {
        // The common case for a background file changing on disk.
        PaneRegistry.publish(listOf(paneOne), focused = paneOne)

        assertTrue(PaneRegistry.showing("page-elsewhere").isEmpty())
    }

    @Test
    fun `showing follows a pane to its new page`() {
        // Page ids are read live rather than snapshotted at publish: recording them would go stale
        // the moment a pane changed page, which is the staleness this work exists to remove.
        val movingPage = mockk<PageView>(relaxed = true)
        val bus = PaneEventBus()
        every { movingPage.events } returns bus
        every { movingPage.currentPageId } returns "before"
        val movingPane = Pane(movingPage, mockk(relaxed = true))

        PaneRegistry.publish(listOf(movingPane), focused = movingPane)
        assertEquals(listOf(bus), PaneRegistry.showing("before"))

        every { movingPage.currentPageId } returns "after"

        assertTrue(PaneRegistry.showing("before").isEmpty())
        assertEquals(listOf(bus), PaneRegistry.showing("after"))
    }

    @Test
    fun `focused follows the published focus`() {
        PaneRegistry.publish(listOf(paneOne, paneTwo), focused = paneTwo)

        assertSame(paneTwo.events, PaneRegistry.focused)
    }

    @Test
    fun `clearing stops addressing panes that are gone`() {
        PaneRegistry.publish(listOf(paneOne, paneTwo), focused = paneOne)

        PaneRegistry.clear()

        assertTrue(PaneRegistry.all.isEmpty())
        assertTrue(PaneRegistry.showing("page-one").isEmpty())
        // Still safe to emit into — the editor closing must not make lifecycle callbacks crash.
        assertNotSame(paneOne.events, PaneRegistry.focused)
    }
}
