package com.ethran.notable.editor.state

import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.gestures.MAX_ZOOM
import com.ethran.notable.gestures.MIN_ZOOM
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for [ViewportState.calculateZoomLevel] — pinch-zoom snapping and clamping.
 *
 * Replaces the earlier `PageViewZoomTest`, which characterised the previous behaviour: snap targets
 * derived from the `SCREEN_WIDTH` / `SCREEN_HEIGHT` globals, i.e. from the *display*. Those tests
 * were written to be replaced, and this is that replacement — the targets now come from the
 * viewport's own aspect ratio.
 *
 * Two benefits beyond correctness for split views: these construct a ViewportState directly rather
 * than standing up a whole PageView, and they no longer mutate process-wide globals, so they cannot
 * corrupt whatever test runs next.
 */
@RunWith(AndroidJUnit4::class)
class ViewportZoomTest {

    private companion object {
        /** Landscape, roughly a BOOX Go 10.3. Ratio 4:3. */
        const val LANDSCAPE_W = 1872
        const val LANDSCAPE_H = 1404

        /** The same panel rotated. Ratio 3:4. */
        const val PORTRAIT_W = 1404
        const val PORTRAIT_H = 1872

        /** A half-width pane of the landscape panel. Ratio 2:3 — unrelated to the display. */
        const val PANE_W = 936
        const val PANE_H = 1404

        const val PAGE = "page-1"
        const val TOLERANCE = 0.0001f
    }

    private lateinit var dataManager: PageDataManager

    @Before
    fun setUp() {
        dataManager = mockk(relaxed = true)
        every { dataManager.getPageScroll(any()) } returns Offset.Zero
        every { dataManager.getPageZoom(any()) } returns 1f
    }

    @After
    fun tearDown() {
        GlobalAppSettings.update(AppSettings(version = 1))
    }

    private fun viewport(width: Int, height: Int) =
        ViewportState(dataManager, width, height) { PAGE }

    private fun setContinuousZoom(enabled: Boolean) {
        GlobalAppSettings.update(AppSettings(version = 1, continuousZoom = enabled))
    }

    // ------------------------------------------------- the point of the change

    /**
     * The reason this moved off the globals. A half-width pane has its own shape, and its snap
     * target must describe that shape rather than the panel it happens to sit on.
     */
    @Test
    fun snapTargetFollowsTheViewportNotTheDisplay() {
        setContinuousZoom(false)
        val fullScreen = viewport(LANDSCAPE_W, LANDSCAPE_H)
        val pane = viewport(PANE_W, PANE_H)

        val fullTarget = fullScreen.calculateZoomLevel(0.5f, 1.0f)
        val paneTarget = pane.calculateZoomLevel(-0.5f, 1.0f)

        assertEquals(LANDSCAPE_W.toFloat() / LANDSCAPE_H, fullTarget, TOLERANCE)
        assertEquals(PANE_W.toFloat() / PANE_H, paneTarget, TOLERANCE)
        assertNotEquals(
            "a narrow pane must not inherit the display's ratio",
            fullTarget,
            paneTarget,
        )
    }

    /** A tall, narrow pane behaves like a portrait viewport, whatever the device orientation. */
    @Test
    fun tallPaneUsesPortraitBranchEvenOnALandscapeDevice() {
        setContinuousZoom(false)
        val pane = viewport(PANE_W, PANE_H)   // 936x1404 — taller than wide

        assertEquals(PANE_W.toFloat() / PANE_H, pane.calculateZoomLevel(-0.5f, 1.0f), TOLERANCE)
        assertEquals(1.0f, pane.calculateZoomLevel(0.5f, 1.0f), TOLERANCE)
    }

    /** Resizing the viewport moves the snap target with it — what a draggable divider needs. */
    @Test
    fun resizingTheViewportChangesTheSnapTarget() {
        setContinuousZoom(false)
        val v = viewport(LANDSCAPE_W, LANDSCAPE_H)
        assertEquals(LANDSCAPE_W.toFloat() / LANDSCAPE_H, v.calculateZoomLevel(0.5f, 1.0f), TOLERANCE)

        v.resize(PANE_W, PANE_H)
        assertEquals(PANE_W.toFloat() / PANE_H, v.calculateZoomLevel(-0.5f, 1.0f), TOLERANCE)
    }

    // ------------------------------------------------- discrete mode

    @Test
    fun discreteLandscapePinchInSnapsToOne() {
        setContinuousZoom(false)
        val v = viewport(LANDSCAPE_W, LANDSCAPE_H)
        assertEquals(1.0f, v.calculateZoomLevel(-0.5f, 1.33f), TOLERANCE)
    }

    @Test
    fun discreteLandscapeSpreadSnapsToViewportRatio() {
        setContinuousZoom(false)
        val v = viewport(LANDSCAPE_W, LANDSCAPE_H)
        assertEquals(LANDSCAPE_W.toFloat() / LANDSCAPE_H, v.calculateZoomLevel(0.5f, 1.0f), TOLERANCE)
    }

    /** A zero delta counts as pinching in — the branch is `scaleDelta <= 0f`, not `< 0f`. */
    @Test
    fun discreteZeroDeltaCountsAsPinchIn() {
        setContinuousZoom(false)
        val v = viewport(LANDSCAPE_W, LANDSCAPE_H)
        assertEquals(1.0f, v.calculateZoomLevel(0f, 5.0f), TOLERANCE)
    }

    @Test
    fun discretePortraitInvertsTheBranches() {
        setContinuousZoom(false)
        val v = viewport(PORTRAIT_W, PORTRAIT_H)
        val ratio = PORTRAIT_W.toFloat() / PORTRAIT_H
        assertEquals(ratio, v.calculateZoomLevel(-0.5f, 1.0f), TOLERANCE)
        assertEquals(1.0f, v.calculateZoomLevel(0.5f, ratio), TOLERANCE)
    }

    /** Discrete mode never consults currentZoom. */
    @Test
    fun discreteIgnoresCurrentZoom() {
        setContinuousZoom(false)
        val v = viewport(LANDSCAPE_W, LANDSCAPE_H)
        assertEquals(
            v.calculateZoomLevel(-0.5f, 0.2f),
            v.calculateZoomLevel(-0.5f, 8.0f),
            TOLERANCE,
        )
    }

    // ------------------------------------------------- continuous mode

    /** newZoom = currentZoom * (1 + scaleDelta * ZOOM_SENSITIVITY), with ZOOM_SENSITIVITY = 0.4. */
    @Test
    fun continuousAppliesSensitivityDamping() {
        setContinuousZoom(true)
        val v = viewport(LANDSCAPE_W, LANDSCAPE_H)
        assertEquals(2.4f, v.calculateZoomLevel(0.5f, 2.0f), TOLERANCE)
    }

    @Test
    fun continuousClampsToMaxZoom() {
        setContinuousZoom(true)
        val v = viewport(LANDSCAPE_W, LANDSCAPE_H)
        assertEquals(MAX_ZOOM, v.calculateZoomLevel(10f, 9.0f), TOLERANCE)
    }

    @Test
    fun continuousClampsToMinZoom() {
        setContinuousZoom(true)
        val v = viewport(LANDSCAPE_W, LANDSCAPE_H)
        assertEquals(MIN_ZOOM, v.calculateZoomLevel(-2f, 0.2f), TOLERANCE)
    }

    /** Within ZOOM_SNAP_THRESHOLD (0.02) of the nearer target, the result snaps. */
    @Test
    fun continuousSnapsToOneWhenWithinThreshold() {
        setContinuousZoom(true)
        val v = viewport(LANDSCAPE_W, LANDSCAPE_H)
        assertEquals(1.0f, v.calculateZoomLevel(0f, 1.01f), TOLERANCE)
    }

    @Test
    fun continuousDoesNotSnapOutsideThreshold() {
        setContinuousZoom(true)
        val v = viewport(LANDSCAPE_W, LANDSCAPE_H)
        assertEquals(1.05f, v.calculateZoomLevel(0f, 1.05f), TOLERANCE)
    }

    /** Snapping is to whichever target is nearer, so it also snaps upward. */
    @Test
    fun continuousSnapsToViewportRatioWhenNearer() {
        setContinuousZoom(true)
        val v = viewport(LANDSCAPE_W, LANDSCAPE_H)
        val ratio = LANDSCAPE_W.toFloat() / LANDSCAPE_H
        assertEquals(ratio, v.calculateZoomLevel(0f, ratio - 0.01f), TOLERANCE)
    }

    /** And the snap target it reaches is the pane's, not the display's. */
    @Test
    fun continuousSnapsToThePanesRatioNotTheDisplays() {
        setContinuousZoom(true)
        val pane = viewport(PANE_W, PANE_H)
        val ratio = PANE_W.toFloat() / PANE_H
        assertEquals(ratio, pane.calculateZoomLevel(0f, ratio - 0.01f), TOLERANCE)
    }
}
