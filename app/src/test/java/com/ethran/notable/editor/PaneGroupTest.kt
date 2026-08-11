package com.ethran.notable.editor

import com.ethran.notable.editor.canvas.PaneEventBus
import com.ethran.notable.editor.state.History
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Focus behaviour of [PaneGroup], and the bus it publishes for app-level emitters.
 *
 * The app-level pane pointer was previously assigned in `PageView.init`, so with two panes it ended
 * up pinned to whichever view was *constructed last* and never followed focus. Every app-level
 * emitter that has no view in hand — resume refresh, the background selector, quick-nav scrubbing —
 * was addressing the wrong pane. These tests pin the corrected ownership, now via [PaneRegistry].
 *
 * No `android.graphics` types are asserted on here. Per CLAUDE.md they are all-zero stubs on the
 * unit-test classpath, so comparing two of them passes vacuously; [Pane.screenRect] is left alone.
 */
class PaneGroupTest {

    private lateinit var paneOne: Pane
    private lateinit var paneTwo: Pane

    /** A pane whose page reports [pageId], with its own signal bus. */
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
        // Cleared, so a publish leaked from another test cannot pass this one.
        PaneRegistry.clear()
    }

    @Test
    fun `panes must show distinct pages`() {
        val duplicate = pane("same-page")
        val other = pane("same-page")
        val thrown = runCatching { PaneGroup(listOf(duplicate, other)) }.exceptionOrNull()
        assertTrue(
            "expected IllegalArgumentException, got $thrown",
            thrown is IllegalArgumentException
        )
    }

    @Test
    fun `first pane is active on construction`() {
        val group = PaneGroup(listOf(paneOne, paneTwo))
        assertSame(paneOne, group.active)
    }

    @Test
    fun `construction publishes the first pane's bus`() {
        val group = PaneGroup(listOf(paneOne, paneTwo))
        assertSame(group.active.events, PaneRegistry.focused)
        assertSame(paneOne.events, PaneRegistry.focused)
    }

    @Test
    fun `focus moves the active pane and republishes its bus`() {
        val group = PaneGroup(listOf(paneOne, paneTwo))

        assertTrue(group.focus(paneTwo))

        assertSame(paneTwo, group.active)
        assertSame(paneTwo.events, PaneRegistry.focused)
        // The regression this guards: the bus staying on the other pane after focus moved.
        assertNotSame(paneOne.events, PaneRegistry.focused)
    }

    @Test
    fun `focusing the already-active pane changes nothing`() {
        val group = PaneGroup(listOf(paneOne, paneTwo))

        assertFalse(group.focus(paneOne))

        assertSame(paneOne, group.active)
        assertSame(paneOne.events, PaneRegistry.focused)
    }

    @Test
    fun `a pane outside the group cannot take focus`() {
        val group = PaneGroup(listOf(paneOne, paneTwo))
        val stranger = pane("page-three")

        assertFalse(group.focus(stranger))

        assertSame(paneOne, group.active)
        assertSame(paneOne.events, PaneRegistry.focused)
    }

    @Test
    fun `adding a pane keeps the focused one focused`() {
        val group = PaneGroup(listOf(paneOne))

        group.updatePanes(listOf(paneOne, paneTwo))

        assertEquals(listOf(paneOne, paneTwo), group.panes)
        assertSame(paneOne, group.active)
        assertSame(paneOne.events, PaneRegistry.focused)
    }

    @Test
    fun `removing the focused pane moves focus to a survivor`() {
        val group = PaneGroup(listOf(paneOne, paneTwo))
        group.focus(paneTwo)

        // Closing a split from the second pane: focus must not be left dangling on a pane that is
        // no longer on screen, or input and the toolbar would act on a discarded view.
        group.updatePanes(listOf(paneOne))

        assertSame(paneOne, group.active)
        assertSame(paneOne.events, PaneRegistry.focused)
    }

    @Test
    fun `removing an unfocused pane leaves focus alone`() {
        val group = PaneGroup(listOf(paneOne, paneTwo))

        group.updatePanes(listOf(paneOne))

        assertSame(paneOne, group.active)
    }

    @Test
    fun `updating to the same panes is a no-op`() {
        val group = PaneGroup(listOf(paneOne, paneTwo))
        group.focus(paneTwo)

        // Driven from a Compose update block, so this runs on every recomposition. It must not
        // reset focus back to the first pane each time.
        group.updatePanes(listOf(paneOne, paneTwo))

        assertSame(paneTwo, group.active)
    }

    @Test
    fun `updating to duplicate pages is rejected`() {
        val group = PaneGroup(listOf(paneOne))
        val clash = pane("page-one")

        val thrown = runCatching { group.updatePanes(listOf(paneOne, clash)) }.exceptionOrNull()

        assertTrue(
            "expected IllegalArgumentException, got $thrown",
            thrown is IllegalArgumentException
        )
        // The rejected update must not have been partially applied.
        assertEquals(listOf(paneOne), group.panes)
    }

    @Test
    fun `updating to no panes is rejected`() {
        val group = PaneGroup(listOf(paneOne, paneTwo))

        val thrown = runCatching { group.updatePanes(emptyList()) }.exceptionOrNull()

        assertTrue(
            "expected IllegalArgumentException, got $thrown",
            thrown is IllegalArgumentException
        )
        assertEquals(listOf(paneOne, paneTwo), group.panes)
    }

    @Test
    fun `a single pane is active and publishes its own bus`() {
        val group = PaneGroup(listOf(paneOne))
        assertSame(paneOne, group.active)
        assertSame(paneOne.events, PaneRegistry.focused)
    }
}
