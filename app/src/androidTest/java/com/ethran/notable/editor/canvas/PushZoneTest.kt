package com.ethran.notable.editor.canvas

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.editor.canvas.rectify.RectBounds
import com.ethran.notable.editor.canvas.rectify.toRect
import com.ethran.notable.editor.canvas.rectify.toRectBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which region a firmware push covers, and when there is no region at all.
 *
 * This is the arithmetic behind [CanvasRefreshManager.drawOverFirmware], split out because the rest
 * of that method needs a `SurfaceHolder` and the EPD. It matters to rectify because a null result
 * is what tells the engine the band could not reach the panel, so the gesture must not rectify.
 *
 * Runs on device because `Rect.union`/`intersect` are inert in the JVM source set — the same reason
 * `WebDavXmlAndroidTest` lives here.
 */
@RunWith(AndroidJUnit4::class)
class PushZoneTest {

    private val viewWidth = 1000
    private val viewHeight = 800

    private fun zoneOf(dirty: RectBounds, inset: Int): RectBounds? =
        pushZoneFor(dirty.toRect(), inset, viewWidth, viewHeight)?.toRectBounds()

    @Test
    fun emptyDirtyRegion_pushesNothing() {
        assertNull(zoneOf(RectBounds.EMPTY, inset = 24))
    }

    @Test
    fun zone_isInflatedByTheInset() {
        assertEquals(
            RectBounds(76, 76, 324, 324),
            zoneOf(RectBounds(100, 100, 300, 300), inset = 24),
        )
    }

    @Test
    fun zeroInset_pushesTheRegionAsGiven() {
        assertEquals(
            RectBounds(100, 100, 300, 300),
            zoneOf(RectBounds(100, 100, 300, 300), inset = 0),
        )
    }

    @Test
    fun invertedEdges_pushNothing_untilBuiltInOrder() {
        // Pinned rather than assumed: a rect whose edges run backwards does not become a pushable
        // zone here. bandBounds builds its rect from min/max of the two endpoints, so it never hands
        // drawOverFirmware edges in that order; this records that the pipeline relies on that.
        assertNull(zoneOf(RectBounds(300, 300, 100, 100), inset = 0))
    }

    @Test
    fun zone_isClippedToTheView() {
        assertEquals(
            RectBounds(0, 0, 120, 120),
            zoneOf(RectBounds(-50, -50, 100, 100), inset = 20),
        )
        assertEquals(
            RectBounds(896, 696, 1000, 800),
            zoneOf(RectBounds(920, 720, 980, 780), inset = 24),
        )
    }

    @Test
    fun regionEntirelyOffView_pushesNothing() {
        assertNull(zoneOf(RectBounds(2000, 2000, 2100, 2100), inset = 24))
    }

    @Test
    fun regionTouchingOnlyTheViewEdge_pushesNothing() {
        // An inset-0 push along the boundary has no overlap: the intersect needs positive area.
        assertNull(zoneOf(RectBounds(1000, 100, 1100, 200), inset = 0))
    }

    @Test
    fun rect_roundTripsThroughRectBoundsUnchanged() {
        val rect = Rect(7, 8, 300, 400)
        assertEquals(rect, rect.toRectBounds().toRect())
        assertEquals(RectBounds(7, 8, 300, 400), rect.toRectBounds())
    }
}
