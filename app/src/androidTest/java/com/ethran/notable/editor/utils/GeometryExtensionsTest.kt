package com.ethran.notable.editor.utils

import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Characterisation tests for the Rect geometry operators that underpin the editor's
 * screen<->page coordinate transforms.
 *
 * These pin *current* behaviour before [com.ethran.notable.editor.PageView]'s viewport logic is
 * extracted into a ViewportState. They are deliberately descriptive rather than prescriptive:
 * where the existing behaviour is surprising (truncation toward zero, asymmetric rounding between
 * translation and scaling), the test records what the code does today so a refactor that changes
 * it fails loudly rather than silently.
 *
 * PageView builds its transforms from these operators:
 *   toScreenCoordinates(rect) = (rect - scroll) * zoomLevel
 *   toPageCoordinates(rect)   = rect / zoomLevel + scroll
 *
 * **Why this is an instrumented test and not a unit test.** android.graphics.Rect is an Android
 * framework class. On this project's JVM unit-test classpath its constructor is a no-op that
 * leaves every field at 0, so geometry assertions there are meaningless — and, worse, assertions
 * that compare one Rect against another pass *vacuously* because both sides are all-zero. These
 * tests therefore run on a device, and assert only against literal ints.
 * See [rectConstructorIsFunctional], which guards the hazard explicitly.
 */
@RunWith(AndroidJUnit4::class)
class GeometryExtensionsTest {

    private fun rect(l: Int, t: Int, r: Int, b: Int) = Rect(l, t, r, b)

    /**
     * Asserts against literal ints, never against another Rect.
     *
     * Comparing two Rects is worthless as a safety net: where the Rect constructor is a no-op
     * stub, expected and actual are both all-zero and every assertion passes vacuously. Keeping
     * the literals inline means these tests cannot silently degrade into asserting nothing.
     */
    private fun assertRect(l: Int, t: Int, r: Int, b: Int, actual: Rect) {
        assertEquals("left", l, actual.left)
        assertEquals("top", t, actual.top)
        assertEquals("right", r, actual.right)
        assertEquals("bottom", b, actual.bottom)
    }

    /**
     * Sanity guard. android.graphics.Rect is an Android framework class; on a unit-test
     * classpath without Robolectric its constructor does not populate the fields, which makes
     * every geometry assertion in this file meaningless. Fail loudly and first if that happens.
     */
    @Test
    fun rectConstructorIsFunctional() {
        val r = Rect(1, 2, 3, 4)
        assertEquals("Rect constructor is a no-op stub — this suite cannot test geometry", 1, r.left)
        assertEquals(2, r.top)
        assertEquals(3, r.right)
        assertEquals(4, r.bottom)
    }

    // ---------------------------------------------------------------- translation

    @Test
    fun minusOffsetTranslatesAllFourEdges() {
        assertRect(5, 15, 25, 35, rect(10, 20, 30, 40) - Offset(5f, 5f))
    }

    @Test
    fun plusOffsetTranslatesAllFourEdges() {
        assertRect(15, 25, 35, 45, rect(10, 20, 30, 40) + Offset(5f, 5f))
    }

    /**
     * Translation rounds to *nearest* (roundToInt), unlike scaling which truncates.
     * This asymmetry is easy to break accidentally during extraction.
     */
    @Test
    fun minusOffsetRoundsToNearestNotTowardZero() {
        // 0.6 rounds up to 1
        assertRect(9, 19, 29, 39, rect(10, 20, 30, 40) - Offset(0.6f, 0.6f))
        // 0.4 rounds down to 0 — no movement
        assertRect(10, 20, 30, 40, rect(10, 20, 30, 40) - Offset(0.4f, 0.4f))
    }

    @Test
    fun translationByZeroIsIdentity() {
        assertRect(10, 20, 30, 40, rect(10, 20, 30, 40) - Offset.Zero)
        assertRect(10, 20, 30, 40, rect(10, 20, 30, 40) + Offset.Zero)
    }

    // ---------------------------------------------------------------- scaling

    /** `div(arg)` delegates to scaleRect(rect, arg), which divides each edge by arg. */
    @Test
    fun divScalesEdgesDown() {
        assertRect(5, 10, 15, 20, rect(10, 20, 30, 40) / 2f)
    }

    /**
     * `times(arg)` delegates to scaleRect(rect, 1 / arg) — the inversion is deliberate,
     * so multiplying by 2 doubles the edges. Pinned because the indirection invites
     * "simplification" in the wrong direction.
     */
    @Test
    fun timesScalesEdgesUp() {
        assertRect(20, 40, 60, 80, rect(10, 20, 30, 40) * 2f)
    }

    @Test
    fun scalingByOneIsIdentity() {
        assertRect(10, 20, 30, 40, rect(10, 20, 30, 40) * 1f)
        assertRect(10, 20, 30, 40, rect(10, 20, 30, 40) / 1f)
    }

    /** scaleRect uses toInt(), which truncates toward zero rather than flooring. */
    @Test
    fun divTruncatesPositiveCoordinatesTowardZero() {
        assertRect(5, 10, 15, 20, rect(11, 21, 31, 41) / 2f)
    }

    /**
     * The negative case is where truncation and flooring diverge: -11 / 2 is -5.5,
     * which truncates to -5 but would floor to -6. Page coordinates are normally
     * non-negative, so this is latent rather than active — but it must not change silently.
     */
    @Test
    fun divTruncatesNegativeCoordinatesTowardZeroNotFloor() {
        assertRect(-5, -10, 5, 10, rect(-11, -21, 11, 21) / 2f)
    }

    // ---------------------------------------------------------------- composition

    /**
     * The round trip PageView performs. At zoom 1.0 with an integral scroll it is exact.
     */
    @Test
    fun screenToPageRoundTripIsExactAtZoom1WithIntegralScroll() {
        val scroll = Offset(0f, 100f)
        val zoom = 1.0f
        val page = rect(10, 200, 60, 300)

        val screen = (page - scroll) * zoom
        val roundTripped = screen / zoom + scroll

        assertRect(10, 200, 60, 300, roundTripped)
    }

    /**
     * At non-integral zoom the round trip is lossy — truncation discards sub-pixel detail.
     * Recorded so that a refactor which changes the loss characteristics is visible.
     */
    @Test
    fun screenToPageRoundTripLosesPrecisionAtFractionalZoom() {
        val scroll = Offset(0f, 0f)
        val zoom = 1.5f
        val page = rect(0, 0, 101, 101)

        val screen = (page - scroll) * zoom      // 101 * 1.5 = 151.5 -> 151
        val roundTripped = screen / zoom + scroll // 151 / 1.5 = 100.67 -> 100

        assertEquals(151, screen.right)
        assertEquals(100, roundTripped.right)
    }
}
