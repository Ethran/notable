package com.ethran.notable.editor

import android.content.Context
import androidx.compose.ui.unit.IntOffset
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.ui.SnackState
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Characterisation tests for [PageView.alreadyDrawnRectAfterShift], the helper that decides which
 * part of the cached window survives a scroll and therefore which strip must be redrawn.
 *
 * Pins current behaviour before this moves to a PageRenderer. Scrolling on e-ink is
 * "blit the old bitmap, redraw only the newly exposed strip", so an off-by-one here shows up as
 * a stale or torn band on screen — a defect no other test in the suite would catch.
 *
 * The function is pure (it reads only its arguments), but it is an instance method, so a PageView
 * has to be built. That is cheap: PageView's init block does all of its real work inside
 * `coroutineScope.launch(Dispatchers.IO)`, so construction returns immediately and a relaxed
 * PageDataManager mock is enough. Nothing here touches the database.
 *
 * Instrumented rather than unit-tested because android.graphics.Rect is a no-op stub on the JVM
 * unit-test classpath — see GeometryExtensionsTest and CLAUDE.md.
 */
@RunWith(AndroidJUnit4::class)
class PageViewViewportTest {

    private companion object {
        const val SCREEN_W = 1000
        const val SCREEN_H = 800
    }

    private lateinit var page: PageView

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        page = PageView(
            context = context,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            pageDataManager = mockk<PageDataManager>(relaxed = true),
            initialPageId = "characterisation-test-page",
            viewWidth = SCREEN_W,
            viewHeight = SCREEN_H,
            snackManager = SnackState(),
        )
    }

    private fun shift(dx: Int, dy: Int) =
        page.alreadyDrawnRectAfterShift(IntOffset(dx, dy), SCREEN_W, SCREEN_H)

    /** No movement retains the whole window — nothing needs redrawing. */
    @Test
    fun zeroMovementRetainsWholeWindow() {
        val r = shift(0, 0)
        assertEquals(0, r.left)
        assertEquals(0, r.top)
        assertEquals(SCREEN_W, r.right)
        assertEquals(SCREEN_H, r.bottom)
    }

    /**
     * Positive y movement (content pushed down) retains the top band; the newly exposed strip is
     * at the bottom, so the retained rect loses `dy` from its bottom edge.
     */
    @Test
    fun positiveYMovementRetainsTopBand() {
        val r = shift(0, 50)
        assertEquals(0, r.left)
        assertEquals(0, r.top)
        assertEquals(SCREEN_W, r.right)
        assertEquals(SCREEN_H - 50, r.bottom)
    }

    /** Negative y movement retains the bottom band; the exposed strip is at the top. */
    @Test
    fun negativeYMovementRetainsBottomBand() {
        val r = shift(0, -50)
        assertEquals(0, r.left)
        assertEquals(50, r.top)
        assertEquals(SCREEN_W, r.right)
        assertEquals(SCREEN_H, r.bottom)
    }

    /** Horizontal movement behaves symmetrically to vertical. */
    @Test
    fun positiveXMovementRetainsLeftBand() {
        val r = shift(50, 0)
        assertEquals(0, r.left)
        assertEquals(0, r.top)
        assertEquals(SCREEN_W - 50, r.right)
        assertEquals(SCREEN_H, r.bottom)
    }

    @Test
    fun negativeXMovementRetainsRightBand() {
        val r = shift(-50, 0)
        assertEquals(50, r.left)
        assertEquals(0, r.top)
        assertEquals(SCREEN_W, r.right)
        assertEquals(SCREEN_H, r.bottom)
    }

    /** Diagonal movement applies both axes independently. */
    @Test
    fun diagonalMovementRetainsCorner() {
        val r = shift(30, 40)
        assertEquals(0, r.left)
        assertEquals(0, r.top)
        assertEquals(SCREEN_W - 30, r.right)
        assertEquals(SCREEN_H - 40, r.bottom)
    }

    /** A shift of exactly the screen height retains nothing — the rect collapses to zero height. */
    @Test
    fun movementOfFullScreenHeightRetainsNothing() {
        val r = shift(0, SCREEN_H)
        assertEquals(0, r.top)
        assertEquals(0, r.bottom)
        assertTrue("expected an empty rect", r.isEmpty)
    }

    /**
     * Overshoot is NOT clamped: a movement larger than the screen produces bottom < top, i.e. a
     * rect with negative height rather than an empty one.
     *
     * Recorded as current behaviour, not endorsed. Callers are protected by [android.graphics.Rect.isEmpty]
     * being true for inverted rects, so nothing is drawn — but anyone moving this into a
     * PageRenderer should know the raw output can be inverted, and that "fixing" it by clamping
     * would be a behaviour change rather than a tidy-up.
     */
    @Test
    fun overshootProducesInvertedRectNotClampedToEmpty() {
        val r = shift(0, SCREEN_H + 200)
        assertEquals(0, r.top)
        assertEquals(-200, r.bottom)
        assertTrue("inverted rects still report empty", r.isEmpty)
    }
}
