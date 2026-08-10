package com.ethran.notable.ui.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The picker's scrubber, as a value.
 *
 * These were four separate fields on [QuickNavUiState] that could only be overwritten, never
 * cleared — which is how a page count outlived the notebook it described and read "0/3". The
 * behaviour worth pinning is that resolving an index is total: every caller gets null rather than
 * an index into a list that has moved on.
 */
class ScrubberStateTest {

    private val scrubber = ScrubberState(
        pageIds = listOf("p1", "p2", "p3"),
        index = 1,
        favouriteIndexes = listOf(0, 2),
    )

    @Test
    fun `count follows the page list`() {
        // Not stored alongside it: a count that can disagree with the list is the original bug.
        assertEquals(3, scrubber.count)
    }

    @Test
    fun `an in-range position resolves to its page`() {
        assertEquals("p1", scrubber.pageAt(0))
        assertEquals("p3", scrubber.pageAt(2))
    }

    @Test
    fun `an out-of-range position resolves to nothing`() {
        // Gesture callbacks can deliver a stale index after the list changes underneath them.
        assertNull(scrubber.pageAt(3))
        assertNull(scrubber.pageAt(-1))
    }

    @Test
    fun `an empty scrubber resolves nothing rather than throwing`() {
        val empty = ScrubberState(pageIds = emptyList(), index = 0)

        assertEquals(0, empty.count)
        assertNull(empty.pageAt(0))
    }
}
