package com.ethran.notable.editor

import android.content.Context
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.model.SheetSize
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.model.FitMode
import com.ethran.notable.testing.A4_SHEET
import com.ethran.notable.testing.A5_SHEET
import com.ethran.notable.testing.A6_SHEET
import com.ethran.notable.testing.PageViewFixture
import com.ethran.notable.testing.TestDatabaseFactory
import com.ethran.notable.testing.createPageViewForTest
import com.ethran.notable.testing.seedLooseSheetPage
import org.junit.Rule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sheet fit: a per-device, per-orientation setting that decides the zoom a page with a sheet
 * opens at. It is stored as an **intent** rather than a zoom: the view derives
 * `zoom = viewWidth / sheetWidth` from its own size, so one setting holds after a rotation or a
 * resize.
 */
@RunWith(AndroidJUnit4::class)
class FitModeViewportTests {


    private lateinit var db: AppDatabase
    private val fixtures = mutableListOf<PageViewFixture>()
    private val screen = SCREEN_WIDTH to SCREEN_HEIGHT

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = TestDatabaseFactory.createInMemory(context)
        // A portrait screen, so a portrait view of the sizes these tests use really is portrait.
        SCREEN_WIDTH = 1200
        SCREEN_HEIGHT = 1600
    }

    @After
    fun tearDown() {
        for (fixture in fixtures) fixture.close()
        fixtures.clear()
        db.close()
        SCREEN_WIDTH = screen.first
        SCREEN_HEIGHT = screen.second
    }

    private fun createPageView(
        context: Context,
        sheet: SheetSize?,
        viewWidth: Int,
        viewHeight: Int,
        fitPortrait: FitMode = FitMode.REAL,
        fitLandscape: FitMode = FitMode.REAL,
    ): PageViewFixture = createPageViewForTest(
        context = context,
        sheet = sheet,
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        fitPortrait = fitPortrait,
        fitLandscape = fitLandscape,
    ).also { fixtures += it }

    /** The zoom that makes [sheetWidth] span [viewWidth] — what FIT_TO_WIDTH prescribes. */
    private fun fittedZoom(viewWidth: Int, sheetWidth: Float): Float = viewWidth / sheetWidth

    // ---------------------------------------------------------------------
    // Opening a page: the fit decides the zoom
    // ---------------------------------------------------------------------

    /**
     * Under FIT_TO_WIDTH a page opens with its sheet spanning the viewport exactly: the zoom is the
     * viewport over the sheet's true width, and there is no side slack left to centre.
     */
    @Test(timeout = 60_000)
    fun fitToWidth_opensWithTheSheetSpanningTheViewport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = A5_SHEET.width.toFloat()
        val viewWidth = (sheetWidth * 1.5f).toInt()
        val fixture = createPageView(
            context = context,
            sheet = A5_SHEET,
            viewWidth = viewWidth,
            viewHeight = (viewWidth * 1.4f).toInt(),
            fitPortrait = FitMode.FIT_TO_WIDTH,
        )
        fixture.awaitInitialization()

        assertEquals(
            "the sheet is scaled to the viewport width, not snapped to it",
            fittedZoom(viewWidth, sheetWidth), fixture.page.zoom, 0.01f,
        )
        assertEquals("a fitted sheet leaves no slack at the sides", 0f, fixture.page.scroll.x, 0.01f)
        assertEquals(
            "the view publishes the fit it applies, so the toolbar ticks what is really in force",
            FitMode.FIT_TO_WIDTH, fixture.page.appliedFitMode.value,
        )
    }

    /** REAL, the default, opens at 1x. */
    @Test(timeout = 60_000)
    fun realSize_opensUnzoomedWithTheSheetCentred() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = A5_SHEET.width.toFloat()
        val viewWidth = (sheetWidth * 1.5f).toInt()
        val fixture = createPageView(
            context = context,
            sheet = A5_SHEET,
            viewWidth = viewWidth,
            viewHeight = (viewWidth * 1.4f).toInt(),
        )
        fixture.awaitInitialization()

        assertEquals("REAL keeps the sheet at its stored size", 1f, fixture.page.zoom, 0.001f)
        assertEquals(
            "and centred in the width, at its true width rather than the viewport's",
            -(viewWidth - sheetWidth) / 2f, fixture.page.scroll.x, 0.01f,
        )
        assertEquals(FitMode.REAL, fixture.page.appliedFitMode.value)
    }

    /**
     * Fitting is allowed to scale a sheet **down**: a sheet wider than the view is worth showing
     * whole across it, and clamping the fit at 1x would leave the reader panning sideways to read a
     * line. The zoom stays inside the bounds the gestures allow either way.
     */
    @Test(timeout = 60_000)
    fun fitToWidth_shrinksASheetWiderThanTheViewport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = A4_SHEET.width.toFloat()
        val viewWidth = (sheetWidth * 0.5f).toInt()
        val fixture = createPageView(
            context = context,
            sheet = A4_SHEET,
            viewWidth = viewWidth,
            viewHeight = (viewWidth * 2f).toInt(),
            fitPortrait = FitMode.FIT_TO_WIDTH,
        )
        fixture.awaitInitialization()

        assertTrue("the sheet is wider than the view, so fitting scales it down", fixture.page.zoom < 1f)
        assertEquals(
            "to exactly the viewport over the sheet width",
            fittedZoom(viewWidth, sheetWidth), fixture.page.zoom, 0.01f,
        )
    }

    // ---------------------------------------------------------------------
    // Orientation: two preferences, applied by the view
    // ---------------------------------------------------------------------

    /**
     * The two orientations are independent, and the view — not the page — picks which one applies.
     */
    @Test(timeout = 60_000)
    fun orientationsApplyTheirOwnFit() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = A5_SHEET.width.toFloat()
        val portraitWidth = (sheetWidth * 1.5f).toInt()
        val fixture = createPageView(
            context = context,
            sheet = A5_SHEET,
            viewWidth = portraitWidth,
            viewHeight = (portraitWidth * 1.4f).toInt(),
            fitPortrait = FitMode.FIT_TO_WIDTH,
            fitLandscape = FitMode.REAL,
        )
        fixture.awaitInitialization()

        assertEquals(fittedZoom(portraitWidth, sheetWidth), fixture.page.zoom, 0.01f)

        // A landscape view of the same page takes the landscape fit: 1x, sheet centred in the new
        // width. The fit is applied for the new size rather than the old zoom being kept or dropped.
        val landscapeWidth = (sheetWidth * 2f).toInt()
        val landscapeHeight = (sheetWidth * 0.8f).toInt()
        fixture.onMain { fixture.page.updateDimensions(landscapeWidth, landscapeHeight) }
        assertEquals("the landscape fit is the other one", FitMode.REAL, fixture.page.appliedFitMode.value)
        assertEquals("REAL in landscape: the sheet at its true size", 1f, fixture.page.zoom, 0.001f)
        assertEquals(
            "centred in the new width",
            -(landscapeWidth - sheetWidth) / 2f, fixture.page.scroll.x, 0.01f,
        )

        // And back to portrait it is fitted again — at the *current* width's zoom.
        val backWidth = (sheetWidth * 1.2f).toInt()
        fixture.onMain { fixture.page.updateDimensions(backWidth, (backWidth * 1.6f).toInt()) }
        assertEquals(FitMode.FIT_TO_WIDTH, fixture.page.appliedFitMode.value)
        assertEquals(fittedZoom(backWidth, sheetWidth), fixture.page.zoom, 0.01f)
    }

    /**
     * A fitted sheet stays fitted through a resize instead of collapsing to 1x: the zoom that fitted
     * the old size fits no page at the new one, so the new size's fit is applied — which is the whole
     * point of storing an intent rather than a zoom.
     */
    @Test(timeout = 60_000)
    fun fitToWidth_staysFittedThroughAResize() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = A5_SHEET.width.toFloat()
        val viewWidth = (sheetWidth * 1.5f).toInt()
        val fixture = createPageView(
            context = context,
            sheet = A5_SHEET,
            viewWidth = viewWidth,
            viewHeight = (viewWidth * 1.4f).toInt(),
            fitPortrait = FitMode.FIT_TO_WIDTH,
        )
        fixture.awaitInitialization()

        val newWidth = (sheetWidth * 1.2f).toInt()
        fixture.onMain { fixture.page.updateDimensions(newWidth, (newWidth * 1.6f).toInt()) }

        assertEquals(
            "the new size's fit, not the old zoom and not 1x",
            fittedZoom(newWidth, sheetWidth), fixture.page.zoom, 0.01f,
        )
        assertEquals("and still no slack at the sides", 0f, fixture.page.scroll.x, 0.01f)
    }

    /**
     * The re-fitted zoom has to be put on the screen canvas too, not only on the viewport.
     *
     * The canvas is the only thing that scales what gets drawn: [PageView]'s bitmap is blitted to
     * the surface 1:1 by `CanvasRefreshManager.drawCanvasToView`, so a canvas left unscaled paints
     * the sheet at 1x into a bitmap the viewport claims is fitted. Every other path that swaps in a
     * screen bitmap re-applies the zoom to its canvas — `showPrepared`, `updateScroll`'s shift and
     * both zoom steps — and a resize that recreates the canvas must do the same, or a rotation of a
     * fitted page shows the sheet at the wrong size while `zoom` reports the fit. The viewport
     * alone is not enough: the strokes, images and background are all drawn in page units through
     * this canvas.
     */
    @Test(timeout = 60_000)
    fun fitToWidth_reAppliesTheRefitZoomToTheScreenCanvas() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = A5_SHEET.width.toFloat()
        val viewWidth = (sheetWidth * 1.5f).toInt()
        val fixture = createPageView(
            context = context,
            sheet = A5_SHEET,
            viewWidth = viewWidth,
            viewHeight = (viewWidth * 1.4f).toInt(),
            fitPortrait = FitMode.FIT_TO_WIDTH,
        )
        fixture.awaitInitialization()

        val newWidth = (sheetWidth * 1.2f).toInt()
        fixture.onMain { fixture.page.updateDimensions(newWidth, (newWidth * 1.6f).toInt()) }

        val refitZoom = fittedZoom(newWidth, sheetWidth)
        assertTrue("the resize really did change the zoom", refitZoom != 1f)

        // The zoom is on the viewport…
        assertEquals(refitZoom, fixture.page.zoom, 0.01f)

        // …and it must be the transform of the canvas the page is drawn into.
        @Suppress("DEPRECATION") // Canvas.getMatrix() is the only public way to read the transform.
        val values = FloatArray(9).also { fixture.page.windowedCanvas.matrix.getValues(it) }
        assertEquals(
            "the screen canvas carries the re-fitted zoom, or the sheet is drawn at 1x",
            refitZoom, values[Matrix.MSCALE_X], 0.01f,
        )
        assertEquals(
            "on both axes",
            refitZoom, values[Matrix.MSCALE_Y], 0.01f,
        )
        assertEquals(
            "the canvas is the screen-sized one the surface blits 1:1",
            newWidth, fixture.page.windowedBitmap.width,
        )
    }

    /** The same for a rotation: the landscape fit is applied to the canvas, not just the viewport. */
    @Test(timeout = 60_000)
    fun fitToWidth_reAppliesTheZoomToTheCanvasOnARotation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = A5_SHEET.width.toFloat()
        val portraitWidth = (sheetWidth * 1.5f).toInt()
        val fixture = createPageView(
            context = context,
            sheet = A5_SHEET,
            viewWidth = portraitWidth,
            viewHeight = (portraitWidth * 1.4f).toInt(),
            fitPortrait = FitMode.FIT_TO_WIDTH,
            fitLandscape = FitMode.FIT_TO_WIDTH,
        )
        fixture.awaitInitialization()

        // A landscape view of the same page: the wider view prescribes a bigger fitted zoom.
        val landscapeWidth = (sheetWidth * 2f).toInt()
        val landscapeHeight = (sheetWidth * 0.8f).toInt()
        fixture.onMain { fixture.page.updateDimensions(landscapeWidth, landscapeHeight) }

        val landscapeZoom = fittedZoom(landscapeWidth, sheetWidth)
        assertEquals(FitMode.FIT_TO_WIDTH, fixture.page.appliedFitMode.value)
        assertEquals(landscapeZoom, fixture.page.zoom, 0.01f)
        @Suppress("DEPRECATION") // Canvas.getMatrix() is the only public way to read the transform.
        val values = FloatArray(9).also { fixture.page.windowedCanvas.matrix.getValues(it) }
        assertEquals(
            "the rotated view draws the sheet at its fitted width, not at 1x",
            landscapeZoom, values[Matrix.MSCALE_X], 0.01f,
        )
    }

    /** A REAL sheet still drops to 1x on a resize, exactly as it always did. */
    @Test(timeout = 60_000)
    fun realSize_dropsTheZoomOnAResize() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = A5_SHEET.width.toFloat()
        val viewWidth = (sheetWidth * 2f).toInt()
        val fixture = createPageView(
            context = context,
            sheet = A5_SHEET,
            viewWidth = viewWidth,
            viewHeight = (viewWidth * 0.6f).toInt(),
        )
        fixture.awaitInitialization()
        fixture.onMain { fixture.page.simpleUpdateZoom(fixture.pageId, -0.5f) }

        val newWidth = (sheetWidth * 1.2f).toInt()
        fixture.onMain { fixture.page.updateDimensions(newWidth, (newWidth * 0.9f).toInt()) }

        assertEquals("REAL: the zoom of the old size is dropped", 1f, fixture.page.zoom, 0.001f)
        assertEquals(
            "and the sheet is centred in the new width at its true width",
            -(newWidth - sheetWidth) / 2f, fixture.page.scroll.x, 0.01f,
        )
    }

    // ---------------------------------------------------------------------
    // Changing the fit: applied to the shown page
    // ---------------------------------------------------------------------

    /** Choosing a fit moves the page to the viewport it prescribes, at once. */
    @Test(timeout = 60_000)
    fun setFitMode_takesThePageToTheFittedViewport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = A5_SHEET.width.toFloat()
        val viewWidth = (sheetWidth * 1.5f).toInt()
        val fixture = createPageView(
            context = context,
            sheet = A5_SHEET,
            viewWidth = viewWidth,
            viewHeight = (viewWidth * 1.4f).toInt(),
        )
        fixture.awaitInitialization()

        fixture.onMain { fixture.page.setFitMode(fixture.pageId, FitMode.FIT_TO_WIDTH) }

        assertEquals(fittedZoom(viewWidth, sheetWidth), fixture.page.zoom, 0.01f)
        assertEquals("a fitted sheet leaves no slack at the sides", 0f, fixture.page.scroll.x, 0.01f)
        assertEquals(FitMode.FIT_TO_WIDTH, fixture.page.appliedFitMode.value)
    }

    /** Back to REAL: 1x on screen, the sheet centred. */
    @Test(timeout = 60_000)
    fun setFitMode_backToRealSize() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = A5_SHEET.width.toFloat()
        val viewWidth = (sheetWidth * 1.5f).toInt()
        val fixture = createPageView(
            context = context,
            sheet = A5_SHEET,
            viewWidth = viewWidth,
            viewHeight = (viewWidth * 1.4f).toInt(),
            fitPortrait = FitMode.FIT_TO_WIDTH,
        )
        fixture.awaitInitialization()

        fixture.onMain { fixture.page.setFitMode(fixture.pageId, FitMode.REAL) }

        assertEquals(1f, fixture.page.zoom, 0.001f)
        assertEquals(
            "centred at its own width",
            -(viewWidth - sheetWidth) / 2f, fixture.page.scroll.x, 0.01f,
        )
        assertEquals(FitMode.REAL, fixture.page.appliedFitMode.value)
    }

    /**
     * An infinite page has no sheet to fit, so a fit is a no-op there rather than a crash, which is
     * also why the toolbar button is hidden for it. A page of no notebook is fitted like any other:
     * the fit is the device's, not the notebook's.
     */
    @Test(timeout = 60_000)
    fun setFitMode_skipsAnInfinitePage_butFitsALoosePageWithASheet() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val infinite = createPageView(context = context, sheet = null, viewWidth = 1200, viewHeight = 1600)
        infinite.awaitInitialization()
        infinite.onMain { infinite.page.setFitMode(infinite.pageId, FitMode.FIT_TO_WIDTH) }
        assertEquals("an infinite canvas is never fitted", 1f, infinite.page.zoom, 0.001f)

        val viewWidth = (A5_SHEET.width * 1.5f).toInt()
        val loose = createPageView(context = context, sheet = A5_SHEET, viewWidth = viewWidth, viewHeight = 1600)
        loose.awaitInitialization()
        val loosePageId = seedLooseSheetPage(loose.data.db, A5_SHEET)
        loose.showPage(loosePageId)
        loose.onMain { loose.page.setFitMode(loosePageId, FitMode.FIT_TO_WIDTH) }
        assertEquals(fittedZoom(viewWidth, A5_SHEET.width.toFloat()), loose.page.zoom, 0.01f)
    }

    // ---------------------------------------------------------------------
    // needsResetView: the zoom isn't the one the fit prescribes
    // ---------------------------------------------------------------------

    /**
     * Under REAL the prescribed zoom is 1x, so the button behaves as it always did: hidden until the
     * reader pinches, gone again once `Reset view` puts the page back.
     */
    @Test(timeout = 60_000)
    fun needsResetView_underRealSize_comparesAgainstOneTimes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = A5_SHEET.width.toFloat()
        val fixture = createPageView(
            context = context,
            sheet = A5_SHEET,
            viewWidth = (sheetWidth * 1.5f).toInt(),
            viewHeight = 1600,
        )
        fixture.awaitInitialization()

        assertFalse("unzoomed is what REAL prescribes", fixture.page.needsResetView.first())
        fixture.onMain { fixture.page.simpleUpdateZoom(fixture.pageId, -0.5f) }
        assertTrue("the reader has pinched away from it", fixture.page.needsResetView.first())
        fixture.onMain { fixture.page.resetView(fixture.pageId) }
        assertFalse("and reset brings it back", fixture.page.needsResetView.first())
    }

    /**
     * Under FIT_TO_WIDTH the predicate is "not the fit's zoom", not "not 1x": a page sitting at its
     * fitted zoom shows no button although its zoom isn't 1, and reset returns it to the **fitted**
     * zoom rather than fighting the setting the user just chose.
     */
    @Test(timeout = 60_000)
    fun needsResetView_underFitToWidth_comparesAgainstTheFitNotOneTimes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = A5_SHEET.width.toFloat()
        val viewWidth = (sheetWidth * 1.5f).toInt()
        val fixture = createPageView(
            context = context,
            sheet = A5_SHEET,
            viewWidth = viewWidth,
            viewHeight = (viewWidth * 1.4f).toInt(),
            fitPortrait = FitMode.FIT_TO_WIDTH,
        )
        fixture.awaitInitialization()

        assertTrue("the fitted zoom is not 1x…", fixture.page.zoom != 1f)
        assertFalse("…and it is what the fit prescribes, so there is nothing to reset",
            fixture.page.needsResetView.first())

        fixture.onMain { fixture.page.simpleUpdateZoom(fixture.pageId, -0.5f) }
        assertTrue("a pinch away from the fit offers the reset", fixture.page.needsResetView.first())

        fixture.onMain { fixture.page.resetView(fixture.pageId) }
        assertFalse(fixture.page.needsResetView.first())
        assertEquals(
            "reset goes back to the fit, not to 1x",
            fittedZoom(viewWidth, sheetWidth), fixture.page.zoom, 0.01f,
        )
    }

    /**
     * A page the reader left at a hand-chosen zoom stays there when they come back to it: the fit
     * says how a page *starts*, it is not a leash — which is what makes the reset button worth
     * having once the reader wanders.
     */
    @Test(timeout = 60_000)
    fun fitToWidth_leavesAPageWhereTheReaderLeftIt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = A5_SHEET.width.toFloat()
        val viewWidth = (sheetWidth * 1.5f).toInt()
        val fixture = createPageView(
            context = context,
            sheet = A5_SHEET,
            viewWidth = viewWidth,
            viewHeight = (viewWidth * 1.4f).toInt(),
            fitPortrait = FitMode.FIT_TO_WIDTH,
        )
        fixture.awaitInitialization()
        val otherPageId = seedLooseSheetPage(fixture.data.db, A5_SHEET)

        fixture.onMain { fixture.page.simpleUpdateZoom(fixture.pageId, -0.5f) }
        val zoomed = fixture.page.zoom

        fixture.showPage(otherPageId)
        fixture.showPage(fixture.pageId)

        assertEquals("the page comes back at the zoom it was left at", zoomed, fixture.page.zoom, 0.001f)
        assertTrue(fixture.page.needsResetView.first())
    }
}
