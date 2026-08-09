package com.ethran.notable.editor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.gestures.MAX_ZOOM
import com.ethran.notable.gestures.MIN_ZOOM
import com.ethran.notable.ui.SnackState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Characterisation tests for [PageView.calculateZoomLevel] — the pinch-zoom snapping and clamping
 * rules.
 *
 * **These pin behaviour that is deliberately going to change.** The function derives its snap
 * targets from the `SCREEN_WIDTH` / `SCREEN_HEIGHT` globals, i.e. from the *device*, not from the
 * viewport being zoomed:
 *
 * ```
 * val portraitRatio = SCREEN_WIDTH.toFloat() / SCREEN_HEIGHT
 * ```
 *
 * That is harmless while a page fills the screen, but wrong once a pane is narrower than the
 * device: the snap targets would describe the display rather than what the user is looking at.
 * Step 2 of the refactor moves this onto a viewport-owned ViewportState. These tests record the
 * *current* global-derived answers so that change is visible and deliberate rather than silent —
 * they are expected to be updated in the same commit that makes the change, not preserved.
 *
 * Instrumented rather than unit-tested for the reasons in CLAUDE.md (android.graphics is stubbed
 * on the JVM unit-test classpath). Method names are camelCase because minSdk 29 forbids spaces in
 * DEX method names.
 */
@RunWith(AndroidJUnit4::class)
class PageViewZoomTest {

    private companion object {
        /** Landscape, roughly the proportions of a BOOX Go 10.3. portraitRatio = 4/3. */
        const val LANDSCAPE_W = 1872
        const val LANDSCAPE_H = 1404

        /** Same panel rotated. portraitRatio = 3/4. */
        const val PORTRAIT_W = 1404
        const val PORTRAIT_H = 1872

        const val TOLERANCE = 0.0001f
    }

    private lateinit var page: PageView
    private lateinit var scope: CoroutineScope
    private var originalWidth = 0
    private var originalHeight = 0

    @Before
    fun setUp() {
        originalWidth = SCREEN_WIDTH
        originalHeight = SCREEN_HEIGHT

        val dataManager = mockk<PageDataManager>(relaxed = true)
        // A relaxed mock returns a *mock Bitmap* rather than null, which would send init down the
        // cached-bitmap branch and blow up in Canvas(). Force the uncached path.
        every { dataManager.getCachedBitmap(any()) } returns null

        // PageView.init launches work on Dispatchers.IO that we neither need nor control. Without
        // a handler, a failure there is an *uncaught* coroutine exception that instrumentation
        // attributes to whichever test happens to be running when it fires — producing failures in
        // unrelated classes. Swallow it, and cancel the scope below.
        scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> }
        )

        page = PageView(
            context = ApplicationProvider.getApplicationContext<Context>(),
            coroutineScope = scope,
            pageDataManager = dataManager,
            initialPageId = "characterisation-test-page",
            viewWidth = LANDSCAPE_W,
            viewHeight = LANDSCAPE_H,
            snackManager = SnackState(),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        // SCREEN_WIDTH/SCREEN_HEIGHT are process-wide mutable vars; leaving them altered would
        // corrupt any test that runs afterwards in the same process.
        SCREEN_WIDTH = originalWidth
        SCREEN_HEIGHT = originalHeight
        GlobalAppSettings.update(AppSettings(version = 1))
    }

    private fun useLandscape() {
        SCREEN_WIDTH = LANDSCAPE_W
        SCREEN_HEIGHT = LANDSCAPE_H
    }

    private fun usePortrait() {
        SCREEN_WIDTH = PORTRAIT_W
        SCREEN_HEIGHT = PORTRAIT_H
    }

    private fun setContinuousZoom(enabled: Boolean) {
        GlobalAppSettings.update(AppSettings(version = 1, continuousZoom = enabled))
    }

    // ------------------------------------------------------------ discrete mode

    /**
     * Discrete mode ignores the magnitude of the pinch entirely and toggles between two levels:
     * 1.0 and the screen aspect ratio. Only the *sign* of scaleDelta matters.
     */
    @Test
    fun discreteLandscapePinchInSnapsToOne() {
        useLandscape()
        setContinuousZoom(false)
        val ratio = LANDSCAPE_W.toFloat() / LANDSCAPE_H
        assertEquals(1.0f, page.calculateZoomLevel(-0.5f, ratio), TOLERANCE)
    }

    @Test
    fun discreteLandscapeSpreadSnapsToScreenRatio() {
        useLandscape()
        setContinuousZoom(false)
        val ratio = LANDSCAPE_W.toFloat() / LANDSCAPE_H
        assertEquals(ratio, page.calculateZoomLevel(0.5f, 1.0f), TOLERANCE)
    }

    /** A zero delta counts as pinching in — the branch is `scaleDelta <= 0f`, not `< 0f`. */
    @Test
    fun discreteZeroDeltaCountsAsPinchIn() {
        useLandscape()
        setContinuousZoom(false)
        assertEquals(1.0f, page.calculateZoomLevel(0f, 5.0f), TOLERANCE)
    }

    /** Portrait inverts which target each direction selects. */
    @Test
    fun discretePortraitPinchInSnapsToScreenRatio() {
        usePortrait()
        setContinuousZoom(false)
        val ratio = PORTRAIT_W.toFloat() / PORTRAIT_H
        assertEquals(ratio, page.calculateZoomLevel(-0.5f, 1.0f), TOLERANCE)
    }

    @Test
    fun discretePortraitSpreadSnapsToOne() {
        usePortrait()
        setContinuousZoom(false)
        assertEquals(1.0f, page.calculateZoomLevel(0.5f, 0.75f), TOLERANCE)
    }

    /** Discrete mode does not consult currentZoom at all — the result is the same regardless. */
    @Test
    fun discreteIgnoresCurrentZoom() {
        useLandscape()
        setContinuousZoom(false)
        val fromLow = page.calculateZoomLevel(-0.5f, 0.2f)
        val fromHigh = page.calculateZoomLevel(-0.5f, 8.0f)
        assertEquals(fromLow, fromHigh, TOLERANCE)
    }

    // ------------------------------------------------------------ continuous mode

    /**
     * Continuous mode scales multiplicatively, damped by ZOOM_SENSITIVITY (0.4):
     * newZoom = currentZoom * (1 + scaleDelta * 0.4)
     */
    @Test
    fun continuousAppliesSensitivityDamping() {
        useLandscape()
        setContinuousZoom(true)
        // 2.0 * (1 + 0.5 * 0.4) = 2.4 — far from both snap targets, so returned unchanged.
        assertEquals(2.4f, page.calculateZoomLevel(0.5f, 2.0f), TOLERANCE)
    }

    @Test
    fun continuousClampsToMaxZoom() {
        useLandscape()
        setContinuousZoom(true)
        assertEquals(MAX_ZOOM, page.calculateZoomLevel(10f, 9.0f), TOLERANCE)
    }

    @Test
    fun continuousClampsToMinZoom() {
        useLandscape()
        setContinuousZoom(true)
        assertEquals(MIN_ZOOM, page.calculateZoomLevel(-2f, 0.2f), TOLERANCE)
    }

    /**
     * Within ZOOM_SNAP_THRESHOLD (0.02) of the nearer of {1.0, screenRatio}, the result snaps.
     * 1.01 is 0.01 from 1.0, so it snaps.
     */
    @Test
    fun continuousSnapsToOneWhenWithinThreshold() {
        useLandscape()
        setContinuousZoom(true)
        assertEquals(1.0f, page.calculateZoomLevel(0f, 1.01f), TOLERANCE)
    }

    /** 1.05 is 0.05 from 1.0, outside the threshold, so it is left alone. */
    @Test
    fun continuousDoesNotSnapOutsideThreshold() {
        useLandscape()
        setContinuousZoom(true)
        assertEquals(1.05f, page.calculateZoomLevel(0f, 1.05f), TOLERANCE)
    }

    /** The snap target is whichever of 1.0 / screenRatio is nearer, so it also snaps upward. */
    @Test
    fun continuousSnapsToScreenRatioWhenNearer() {
        useLandscape()
        setContinuousZoom(true)
        val ratio = LANDSCAPE_W.toFloat() / LANDSCAPE_H   // 1.3333
        assertEquals(ratio, page.calculateZoomLevel(0f, ratio - 0.01f), TOLERANCE)
    }
}
