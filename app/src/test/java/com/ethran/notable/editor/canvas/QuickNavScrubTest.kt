package com.ethran.notable.editor.canvas

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Work queued for a QuickNav scrub (a preview request through its debounce, the scrub's end)
 * belongs to that scrub, and cannot act on the next one.
 */
class QuickNavScrubTest {

    @After
    fun noScrubLeft() {
        CanvasEventBus.activeScrub.value?.let(CanvasEventBus::endScrub)
    }

    @Test
    fun aPreviewRequestKeepsTheScrubItWasMadeIn() {
        CanvasEventBus.startScrub()
        val scrubA = CanvasEventBus.activeScrub.value!!
        val request = CanvasEventBus.previewRequest("page-1")!!
        CanvasEventBus.startScrub() // scrub B starts while A's request waits out the debounce
        assertEquals(scrubA, request.scrub)
        assertFalse("A's preview is dropped in B", CanvasEventBus.isCurrentScrub(request.scrub))
    }

    @Test
    fun aRequestOfTheScrubUnderWayIsShown() {
        CanvasEventBus.startScrub()
        val request = CanvasEventBus.previewRequest("page-1")!!
        assertTrue(CanvasEventBus.isCurrentScrub(request.scrub))
    }

    @Test
    fun noRequestWithoutAScrub() {
        CanvasEventBus.endScrub(CanvasEventBus.startScrub())
        assertNull(CanvasEventBus.previewRequest("page-1"))
    }

    @Test
    fun everyScrubHasItsOwnId() {
        val first = CanvasEventBus.startScrub()
        CanvasEventBus.endScrub(first)
        val second = CanvasEventBus.startScrub()
        assertNotEquals(first, second)
        assertEquals(second, CanvasEventBus.activeScrub.value)
    }

    @Test
    fun aLateEndOfAnEarlierScrubLeavesTheCurrentOne() {
        val first = CanvasEventBus.startScrub()
        val second = CanvasEventBus.startScrub()
        CanvasEventBus.endScrub(first) // e.g. an abandoned gesture's cleanup, run late
        assertEquals(second, CanvasEventBus.activeScrub.value)
        CanvasEventBus.endScrub(second)
        assertNull(CanvasEventBus.activeScrub.value)
    }
}
