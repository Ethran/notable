package com.ethran.notable.editor.canvas.rectify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The soft snap onto the screen axes: near a guide the line leans to it, past the tolerance the pen
 * is obeyed exactly.
 *
 * Endpoints are built by [at] as a ray from the origin at a stated angle and length, so an expected
 * endpoint is that ray and the assertions read as geometry rather than as magic numbers.
 */
class AngleSnappingTest {

    private val thresholds = RectifyThresholds(
        snapEnabled = true,
        snapStepDeg = 45,
        snapToleranceDeg = 5,
        snapStrength = 1f,
    )

    private val origin = Point2D(0f, 0f)

    /** A point [distance] from the origin at [degrees], screen-space so +y is downward. */
    private fun at(degrees: Float, distance: Float = 100f): Point2D {
        val radians = degrees * Math.PI.toFloat() / 180f
        return Point2D(
            x = kotlin.math.cos(radians) * distance,
            y = kotlin.math.sin(radians) * distance,
        )
    }

    /** Snaps [b] about the origin, defaulting to the shipped 45-degree step so tests read as intent. */
    private fun snap(b: Point2D, t: RectifyThresholds = thresholds): Point2D =
        snapBand(origin, b, t)

    private fun angleOf(p: Point2D): Float = atan2(p.y, p.x) * 180f / Math.PI.toFloat()

    private fun lengthOf(p: Point2D): Float = sqrt(p.x * p.x + p.y * p.y)

    // --- Inside tolerance: the line leans to the guide ---------------------

    @Test
    fun `a line 2 degrees off 45 is pulled onto it`() {
        assertEquals(45f, angleOf(snap(at(43f))), 0.01f)
    }

    @Test
    fun `a line 2 degrees off horizontal is pulled flat`() {
        assertEquals(0f, angleOf(snap(at(-2f))), 0.01f)
    }

    @Test
    fun `every multiple of the step is a guide, including vertical`() {
        for (guide in intArrayOf(0, 45, 90, 135, 180, -135, -90, -45)) {
            val snapped = snap(at(guide.toFloat() + 1.5f))
            assertEquals("guide at $guide", guide.toFloat(), angleOf(snapped), 0.02f)
        }
    }

    @Test
    fun `wrapping is measured the short way round`() {
        // 179 and -179 are both 1 degree from the flat guide, which atan2 reports as plus or minus
        // 180 depending on the sign of y. Either sign is the same line, so what matters is that the
        // miss is measured as 1 degree rather than 359 and the line still lands flat.
        for (raw in floatArrayOf(179f, -179f, 179.9f, -179.9f)) {
            val angle = angleOf(snap(at(raw)))
            assertTrue("$raw must land flat, got $angle", abs(abs(angle) - 180f) < 0.05f)
        }
    }

    // --- Outside tolerance: the pen is obeyed exactly ----------------------

    @Test
    fun `a line beyond the tolerance is left exactly as the pen drew it`() {
        // Midway between guides is where no guide can be near: 22.5 sits 22.5 from both 0 and 45,
        // well clear of a 5-degree window.
        val raw = at(22.5f)
        assertEquals(22.5f, angleOf(snap(raw)), 0.001f)
        // The soft part of the snap: nothing is nudged once outside tolerance, or the pen could never
        // draw a deliberate shallow angle.
        assertSame("outside tolerance the point must be returned untouched", raw, snap(raw))
    }

    @Test
    fun `the tolerance is a hard edge, not a fading pull`() {
        assertEquals("inside sits on the guide", 45f, angleOf(snap(at(44.9f))), 0.02f)
        assertEquals("outside is untouched", 50.1f, angleOf(snap(at(50.1f))), 0.001f)
    }

    @Test
    fun `strength below one lands partway to the guide`() {
        val partway = thresholds.copy(snapStrength = 0.5f)
        assertEquals("half of the 3 degree miss is closed", 43.5f, angleOf(snap(at(42f), partway)), 0.02f)
    }

    @Test
    fun `snapping can be switched off entirely`() {
        val raw = at(44f)
        assertSame(raw, snap(raw, thresholds.copy(snapEnabled = false)))
    }

    @Test
    fun `a wider step offers fewer guides`() {
        // The same instance on both sides of assertSame: at() builds a new Point2D each call, and
        // Point2D is a data class, so two calls compare equal but are not the same object — which is
        // exactly the distinction this test is about, the point being handed back untouched.
        val ninety = thresholds.copy(snapStepDeg = 90)
        val offStep = at(48f)
        assertSame("48 is nowhere near a 90-degree step", offStep, snap(offStep, ninety))
        assertEquals(90f, angleOf(snap(at(88f), ninety)), 0.02f)
    }

    // --- Only the axes are guides -----------------------------------------

    @Test
    fun `an angle with no axis near it is left alone`() {
        // The gesture's own dwell direction used to be a guide and was removed, so a heading that
        // finds no axis in range is now simply what the pen drew. 30 is 15 from 45 and 30 from 0,
        // and 20 is 20 from 0 and 25 from 45. Each input is bound once so assertSame compares the
        // instance the function was handed, not an equal-looking second one.
        val diagonal = at(30f)
        assertSame("no guide near 30, so the pen is obeyed", diagonal, snap(diagonal))
        val shallow = at(20f)
        assertSame("no guide near 20 either", shallow, snap(shallow))
    }

    // --- Length never moves, only direction --------------------------------

    @Test
    fun `snapping keeps the pen's distance from A`() {
        val raw = at(43f, distance = 250f)
        assertEquals("the line must not shorten as it straightens", 250f, lengthOf(snap(raw)), 0.01f)
    }

    @Test
    fun `snapping works from a pen-down point that is not the origin`() {
        // The angle must be taken from A, not from the origin: 100,102 from a 300,400 anchor is 45.6
        // degrees, so 0.6 past the guide rather than the 51.8 degrees a mis-anchored rule would give.
        val a = Point2D(300f, 400f)
        val snapped = snapBand(a, Point2D(a.x + 100f, a.y + 102f), thresholds)
        assertEquals(
            "45 degrees from A, not from the origin",
            45f,
            angleOf(Point2D(snapped.x - a.x, snapped.y - a.y)),
            0.02f,
        )
        assertEquals("A itself is untouched", 300f, a.x, 0f)
        assertEquals("A itself is untouched", 400f, a.y, 0f)
    }

    @Test
    fun `a zero-length band snaps to nothing and does not divide by zero`() {
        val a = Point2D(120f, 120f)
        val snapped = snapBand(a, Point2D(120f, 120f), thresholds)
        assertEquals("no direction to snap, so the endpoint stands", 120f, snapped.x, 0f)
        assertEquals("no direction to snap, so the endpoint stands", 120f, snapped.y, 0f)
    }

    @Test
    fun `pressure and tilt survive the rotation`() {
        // The commit reads these, so a rotation that dropped them would change the stroke's weight.
        val raw = Point2D(x = 100f, y = -4f, pressure = 0.7f, tiltX = 11, tiltY = 12)
        val snapped = snap(raw)
        assertEquals(0.7f, snapped.pressure, 0f)
        assertEquals(11, snapped.tiltX)
        assertEquals(12, snapped.tiltY)
    }
}