package com.ethran.notable.editor.canvas.rectify

import androidx.compose.ui.geometry.Offset
import com.ethran.notable.editor.utils.Pen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind "hold the pen still and the stroke becomes a straight line", tested gesture by
 * gesture on a fake clock and a fake panel.
 *
 * Each test is named for the behaviour it pins. The ones citing a commit are characterization
 * tests: they failed before that commit fixed the bug, and exist so an edit cannot quietly undo it.
 *
 * The dwell threshold comes from [thresholds], never from what production ships, so retuning
 * `RectifyThresholds.triggerTimeMs` does not move this suite.
 */
class PenStrokeRectifierTest {

    private val thresholds = RectifyThresholds(
        triggerTimeMs = 600,
        distanceThresholdPx = 20f,
        bandMinIntervalMs = 120,
        bandMinMovePx = 18f,
        bandPaddingPx = 4,
        // Off, so the dwell, throttle and geometry tests see exactly the angle the pen drew. The
        // snapping tests build their own engine with it on rather than moving this suite.
        snapEnabled = false,
    )

    private val clock = FakeClock()
    private val renderer = FakeRectifyRenderer()

    private fun rectifier() = PenStrokeRectifier(
        now = { clock.now },
        renderer = renderer,
        thresholds = thresholds,
        surfaceSize = { SCREEN_W to SCREEN_H },
    )

    /** Advances the fake clock, so a dwell or a throttle interval really passes. */
    private fun advance(ms: Long) = clock.advance(ms)

    /** A gesture that has just entered rectify: down at 100,100, one far move, then held still. */
    private fun rectifying(
        r: PenStrokeRectifier = rectifier(),
        downAt: Pair<Float, Float> = 100f to 100f,
    ): PenStrokeRectifier {
        r.penDown(downAt.first, downAt.second)
        advance(10)
        r.move(400f, 400f)
        r.holdAtRest(Point2D(405f, 405f))
        return r
    }

    /**
     * Holds the pen at a fixed position, stepping the clock past `triggerTimeMs`.
     *
     * The position must not change between moves: a move updates the detector's reference point,
     * so anything but an identical position re-times the dwell and it never fires. The first move
     * at [at] only starts the clock; a later one reads it as a dwell.
     *
     * [at] must land within [RectifyThresholds.distanceThresholdPx] of the previous move, so it
     * reads as rest rather than travel.
     */
    private fun PenStrokeRectifier.holdAtRest(
        at: Point2D,
        stepMs: Long = 50L,
    ) {
        // The move that starts the dwell clock, then enough of them to pass the trigger time.
        advance(stepMs)
        move(at)
        while (clock.now < T0 + thresholds.triggerTimeMs + 100L) {
            advance(stepMs)
            move(at)
        }
    }

    // --- Entering rectify ---------------------------------------------------

    @Test
    fun `a pen held still past the dwell time enters rectify`() {
        val r = rectifier()
        r.penDown(100f, 100f)
        advance(10)
        r.move(400f, 400f)
        assertFalse("moving pen must not rectify", r.isActive)

        r.holdAtRest(Point2D(405f, 405f))
        assertTrue("pen at rest past the dwell must rectify", r.isActive)
    }

    @Test
    fun `a pen held still for less than the dwell time stays freehand`() {
        val r = rectifier()
        r.penDown(100f, 100f)
        advance(10)
        r.move(400f, 400f)

        // Stops short of triggerTimeMs after the clock starts.
        advance(50); r.move(405f, 405f) // starts the dwell clock
        advance(100); r.move(405f, 405f)
        advance(100); r.move(405f, 405f)
        advance(100); r.move(405f, 405f)
        assertFalse("a brief pause must not rectify", r.isActive)
    }

    @Test
    fun `a dwell only rectifies in a mode that draws freehand`() {
        val r = rectifier()
        r.penDown(100f, 100f)
        advance(10)
        r.moveWithoutRectify(400f, 400f)
        while (clock.now < T0 + 900L) {
            advance(50)
            r.moveWithoutRectify(405f, 405f)
        }
        assertFalse("the line tool must keep its own gesture", r.isActive)
    }

    @Test
    fun `moving past the dwell threshold restarts the dwell clock`() {
        val r = rectifier()
        r.penDown(100f, 100f)
        advance(10)
        r.move(400f, 400f)

        // Rest long enough to start the dwell clock, but not to fire.
        advance(50); r.move(405f, 405f)
        advance(100); r.move(405f, 405f)
        advance(100); r.move(405f, 405f)

        // A real move: the pen is travelling again, so the pause does not carry over.
        advance(10); r.move(900f, 900f)
        advance(100); r.move(905f, 905f)
        advance(100); r.move(905f, 905f)
        assertFalse("a pause before a move must not count toward the one after it", r.isActive)
    }

    // --- 1f2697c0: the dwell must not survive its gesture -------------------

    @Test
    fun `a dwell measured in one gesture never fires in the next`() {
        val r = rectifier()
        rectifying(r)
        assertTrue(r.isActive)
        r.penUp(405f, 405f)

        // A new stroke that starts exactly where the last one ended.
        r.penDown(405f, 405f)
        advance(50); r.move(406f, 406f)
        advance(100); r.move(406f, 406f)
        advance(100); r.move(406f, 406f)
        advance(100); r.move(406f, 406f)
        advance(100); r.move(406f, 406f)
        advance(100); r.move(406f, 406f)

        assertFalse(
            "the previous stroke's last point must not seed a dwell",
            r.isActive,
        )
    }

    @Test
    fun `jitter around the previous stroke's last point does not rectify early`() {
        val r = rectifier()
        rectifying(r)
        r.penUp(405f, 405f)

        // A second stroke starting at the old end point and jittering there. Before 1f2697c0 the
        // previous gesture's last point was still the detector's reference, so this sat inside the
        // threshold from its very first move and rectified almost at once, swallowing the stroke.
        // The clock starts no earlier than the first move, so the stroke cannot rectify before
        // roughly T0 + 50 + triggerTimeMs; crossing a time short of that proves nothing was
        // inherited.
        r.penDown(405f, 405f)
        val crossAt = T0 + thresholds.triggerTimeMs - 100
        var moves = 0
        while (clock.now < crossAt && moves < 60) {
            advance(50)
            r.move(405f + (moves % 2), 405f + (moves % 2))
            moves++
        }
        assertTrue("the clock must have passed the trigger time", clock.now > T0 + thresholds.triggerTimeMs)
        assertFalse("no dwell may be inherited from the previous stroke", r.isActive)
    }

    @Test
    fun `pen down clears the dwell detector`() {
        val r = rectifier()
        r.penDown(100f, 100f)
        advance(10)
        r.move(400f, 400f)
        advance(50); r.move(405f, 405f) // starts a dwell clock

        r.penDown(700f, 700f)
        advance(10); r.move(701f, 701f)

        // With the detector cleared, this rest must start its clock over from now.
        advance(100); r.move(701f, 701f)
        advance(100); r.move(701f, 701f)
        advance(100); r.move(701f, 701f)
        assertFalse("pen down must not inherit a dwell", r.isActive)
    }

    @Test
    fun `pen up clears the dwell detector`() {
        val r = rectifier()
        r.penDown(100f, 100f)
        advance(10); r.move(400f, 400f)
        advance(50); r.move(405f, 405f) // starts a dwell clock
        r.penUp(405f, 405f)

        r.penDown(405f, 405f)
        advance(10); r.move(406f, 406f)
        advance(100); r.move(406f, 406f)
        advance(100); r.move(406f, 406f)
        advance(100); r.move(406f, 406f)
        assertFalse("pen up must not leave a dwell running", r.isActive)
    }

    // --- b12c2a88: the firmware layer is cleared before the band ------------

    @Test
    fun `entering rectify clears the firmware layer before the first band`() {
        val r = rectifying()
        assertTrue("expected a clear and a band", renderer.calls.size >= 2)
        assertTrue(
            "the firmware layer must be buried before the band, or the band is painted back out",
            renderer.calls.first() is FakeRectifyRenderer.ClearFirmwareLayer,
        )
        assertTrue(
            "the band follows the clear",
            renderer.calls[1] is FakeRectifyRenderer.DrawBand,
        )
        assertEquals(
            "the clear covers the whole screen",
            RectBounds.ofSize(SCREEN_W, SCREEN_H),
            renderer.cleared.single(),
        )
    }

    @Test
    fun `a gesture that never rectifies touches the panel not at all`() {
        val r = rectifier()
        r.penDown(100f, 100f)
        advance(10)
        r.move(400f, 400f)
        advance(10); r.move(700f, 700f)
        r.penUp(700f, 700f)
        assertEquals("freehand must not push anything", emptyList<Any>(), renderer.calls)
    }

    // --- Entering rectify turns the firmware's own drawing off --------------

    @Test
    fun `entering rectify turns off the firmware live drawing and pen up turns it back on`() {
        val r = rectifying()
        assertFalse("the firmware must stop drawing its wobble over the band",
            renderer.rawDrawingRenderEnabled)

        r.penUp(405f, 405f)
        assertTrue("pen up must restore the firmware's live drawing",
            renderer.rawDrawingRenderEnabled)
    }

    @Test
    fun `a first push that reaches no panel does not rectify and wipes nothing`() {
        renderer.pushArea = null
        val r = rectifying()
        assertFalse("nothing can be drawn, so the gesture must not rectify", r.isActive)
        assertTrue("the firmware's drawing must be handed back", renderer.rawDrawingRenderEnabled)
        assertEquals(
            "the band never landed on the panel, so there is nothing to wipe",
            0,
            renderer.wipes,
        )
        assertNull("and there is nothing to commit", r.penUp(405f, 405f))
    }

    @Test
    fun `abandon re-enables firmware drawing and wipes the band`() {
        val r = rectifying()
        assertTrue("abandon is only interesting on a live rectify", r.isActive)
        r.abandon()
        assertFalse(r.isActive)
        assertTrue(renderer.rawDrawingRenderEnabled)
        assertEquals(1, renderer.wipes)
        assertNull("an abandoned gesture commits nothing", r.penUp(405f, 405f))
    }

    @Test
    fun `abandon with nothing rectifying does nothing`() {
        val r = rectifier()
        r.penDown(100f, 100f)
        r.abandon()
        assertEquals("a freehand gesture must not wipe the panel", emptyList<Any>(), renderer.calls)
    }

    // --- Commit -------------------------------------------------------------

    @Test
    fun `pen up commits a straight line from the pen-down point to the last move`() {
        val r = rectifying()
        advance(PUSHER_GAP)
        r.move(600f, 200f)

        val commit = r.penUp(601f, 201f)
        assertNotNull("a rectified gesture must commit", commit)
        assertEquals("A is where the pen went down", Point2D(100f, 100f), commit!!.a)
        assertEquals("B is the pen's last move, not the up point", Point2D(600f, 200f), commit.b)
        assertEquals("the commit carries the page the pen went down on", "page-a", commit.pageId)
        assertNotNull("the band area is carried so the panel need not be pushed again",
            commit.dirty)
    }

    @Test
    fun `pen up with no recorded move falls back to the up point`() {
        val r = rectifying()
        // The band never reached a second move, so B is whatever the up event reports.
        renderer.calls.clear()
        val commit = r.penUp(405f, 405f)
        assertEquals(Point2D(100f, 100f), commit!!.a)
        assertEquals(Point2D(405f, 405f), commit.b)
    }

    @Test
    fun `pen up with no up point still commits the last move`() {
        val r = rectifying()
        // The SDK's up event carries a point in practice; the A-only fallback is only reachable
        // when both B and the up point are missing, which no gesture produces.
        val commit = r.penUp()
        assertEquals(Point2D(100f, 100f), commit!!.a)
        assertEquals(Point2D(405f, 405f), commit.b)
    }

    @Test
    fun `pen up on a gesture that never rectified commits nothing`() {
        val r = rectifier()
        r.penDown(100f, 100f)
        advance(10); r.move(400f, 400f)
        assertNull("a freehand gesture is drawn from the stroke list instead",
            r.penUp(400f, 400f))
    }

    @Test
    fun `pen up ends the gesture so a second pen up commits nothing`() {
        val r = rectifying()
        assertNotNull(r.penUp(405f, 405f))
        assertNull("the gesture is over", r.penUp(405f, 405f))
    }

    // --- The band follows the pen, throttled --------------------------------

    @Test
    fun `each band goes from the pen-down point to the current pen position`() {
        val r = rectifying()
        val first = renderer.bands.last()
        assertEquals(Point2D(100f, 100f), first.a)

        advance(PUSHER_GAP)
        r.move(600f, 200f)
        val second = renderer.bands.last()
        assertEquals(Point2D(100f, 100f), second.a)
        assertEquals("the far end follows the pen", Point2D(600f, 200f), second.b)
    }

    /**
     * A gesture that entered rectify on the very move that passed the dwell time, so that move is
     * the only band so far and the throttle's interval runs from a known instant.
     *
     * [holdAtRest] keeps moving while it advances the clock, so a gesture reaching rectify through
     * it can push a second band partway through its own loop, leaving the throttle's window at a
     * drifting offset. For the tests that count pushes, the dwell is crossed in one move instead.
     */
    private fun rectifyOnFirstQualifyingMove(): PenStrokeRectifier {
        val r = rectifier()
        r.penDown(100f, 100f)
        advance(10)
        r.move(400f, 400f) // travel, so the reference is 400,400

        advance(50)
        r.move(405f, 405f) // inside the threshold: starts the dwell clock, no rectify
        assertTrue("the first resting move must only start the clock", !r.isActive)

        val startedAt = clock.now
        clock.now = startedAt + thresholds.triggerTimeMs + 1
        r.move(405f, 405f) // the dwell is now long enough: enters rectify and pushes once
        assertTrue("a dwell past the trigger time must rectify", r.isActive)
        assertEquals("entering rectify pushes exactly one band", 1, renderer.bands.size)
        return r
    }

    /**
     * The moment the last band was pushed, which the throttle arms itself against.
     *
     * Only valid straight after a push, which is all these tests need: [rectifyOnFirstQualifyingMove]
     * ends by pushing, so the engine's clock was at [FakeClock.now] when it stamped the push. Each
     * test then sets the clock relative to that instant, so it never depends on where the dwell
     * loop left the time.
     */
    private val pushTime: Long get() = clock.now

    /**
     * Once the push interval is armed, the band is pushed at most once per
     * [RectifyThresholds.bandMinIntervalMs], because each push is one EPD transaction and pushing
     * every move point hangs the panel.
     *
     * Each step is measured from the moment the previous push happened, so no step depends on where
     * the dwell loop left the clock. The entering push is excluded: it leaves the interval unarmed,
     * which is production's behaviour and is pinned as the test's first step.
     */
    @Test
    fun `the band is pushed at most once per throttle interval`() {
        val r = rectifyOnFirstQualifyingMove()

        // The band pushed on entering rectify does not arm the interval: production stamps the
        // interval only from a push made inside the move handler, so the entering push leaves it at
        // zero and the next move pushes whatever the clock says. Established here so the rest of the
        // test can measure against an interval that is actually running.
        val armedAt = pushTime
        clock.now = armedAt + 1
        r.move(600f, 200f)
        assertEquals("the entering push leaves the interval unarmed", 2, renderer.bands.size)

        // From here the interval is armed by that push, and holds.
        val pushedAt = pushTime
        clock.now = pushedAt + 1
        r.move(650f, 250f)
        assertEquals("pushing every move point hangs the panel", 2, renderer.bands.size)

        clock.now = pushedAt + thresholds.bandMinIntervalMs + 1
        r.move(700f, 300f)
        assertEquals("once past the interval it pushes", 3, renderer.bands.size)

        // And that push armed the interval again.
        val thirdPushAt = pushTime
        clock.now = thirdPushAt + 1
        r.move(750f, 350f)
        assertEquals("the interval arms again on every push", 3, renderer.bands.size)

        clock.now = thirdPushAt + thresholds.bandMinIntervalMs + 1
        r.move(800f, 400f)
        assertEquals("a fourth push once past that interval", 4, renderer.bands.size)
    }

    @Test
    fun `the band is not pushed again until the pen has moved enough`() {
        val r = rectifyOnFirstQualifyingMove()
        val pushedAt = pushTime

        // Enough time, but the pen barely moved from where the band was last drawn.
        clock.now = pushedAt + thresholds.bandMinIntervalMs + 1
        r.move(410f, 410f)
        assertEquals("a nudge is not worth an EPD transaction", 1, renderer.bands.size)

        clock.now = pushedAt + 2 * thresholds.bandMinIntervalMs + 2
        r.move(600f, 200f)
        assertEquals("a real move pushes", 2, renderer.bands.size)
    }

    @Test
    fun `bands widen from the area already pushed so one push wipes the last band`() {
        val r = rectifyOnFirstQualifyingMove()
        val firstDirty = renderer.bands.last().dirty

        clock.now = pushTime + thresholds.bandMinIntervalMs + 1
        r.move(900f, 700f)
        val secondDirty = renderer.bands.last().dirty

        assertTrue(
            "the second push must cover the first, or the old band stays on the panel",
            secondDirty.left <= firstDirty.left && secondDirty.top <= firstDirty.top &&
                secondDirty.right >= firstDirty.right && secondDirty.bottom >= firstDirty.bottom,
        )
    }

    // --- 2a370696: the highlighter stays translucent ------------------------

    @Test
    fun `the highlighter band is drawn translucent`() {
        val r = rectifier()
        r.penDown(100f, 100f, pen = Pen.MARKER, strokeWidthPx = 40f)
        advance(10); r.move(400f, 400f)
        r.holdAtRest(Point2D(405f, 405f))

        val spec = renderer.bands.last().spec
        assertTrue("a solid highlighter bar over the page is the bug", spec.darken)
    }

    @Test
    fun `a normal pen band is drawn solid`() {
        val r = rectifier()
        r.penDown(100f, 100f, pen = Pen.BALLPEN)
        advance(10); r.move(400f, 400f)
        r.holdAtRest(Point2D(405f, 405f))

        assertFalse("only the highlighter lets the page through",
            renderer.bands.last().spec.darken)
    }

    // --- the band carries the gesture's average pressure --------------------

    /** A device-like raw maximum; the JVM's own `rawInputMaxPressure` is 1, so tests need their own. */
    private val rawMax = 4096f

    /** Folds [readings] left to right, as the engine does over one gesture. */
    private fun meanOf(vararg readings: Float): Float {
        var mean = rawMax
        var count = 0
        readings.forEach {
            mean = foldBandPressure(mean, count, it, rawMax)
            if (it > 0f) count++
        }
        return mean
    }

    @Test
    fun `the band pressure is the mean of the gesture's readings`() {
        assertEquals(2048f, meanOf(1024f, 3072f), 0f)
        assertEquals(2048f, meanOf(1024f, 2048f, 3072f), 0f)
    }

    @Test
    fun `the band pressure ignores a zero reading rather than diluting`() {
        // A hard press lifted lightly must not be dragged down by the near-zero lift.
        assertEquals(3072f, meanOf(3072f, 0f), 0f)
        assertEquals(3072f, meanOf(0f, 3072f), 0f)
        assertEquals(3072f, meanOf(3072f, 0f, 0f, 0f), 0f)
    }

    @Test
    fun `the band pressure falls back to full when no reading is usable`() {
        // A gesture reporting no pressure at all must still draw, at full weight.
        assertEquals(rawMax, meanOf(), 0f)
        assertEquals(rawMax, meanOf(0f, 0f), 0f)
    }

    @Test
    fun `the band pressure keeps the raw range rather than normalising`() {
        // The regression: clamping a raw reading to [0,1] collapses every gesture onto full pressure.
        assertEquals(205f, meanOf(205f, 205f), 0f)
        assertEquals(rawMax, meanOf(rawMax * 2f, rawMax * 2f), 0f)
        assertEquals(rawMax, meanOf(-1f, -1f), 0f)
    }

    @Test
    fun `a light tap and light lift do not outweigh the stroke between them`() {
        // Four pressed samples against one tap and one lift must keep the mean near the pressed level.
        val tap = 200f
        val lift = 150f
        val pressed = 3500f

        // Folded from a cold start, as the engine does at pen-down; meanOf starts from full pressure.
        var mean = 0f
        var count = 0
        listOf(tap, pressed, pressed, pressed, pressed, lift).forEach {
            mean = foldBandPressure(mean, count, it, rawMax, fullPressure = 0f)
            if (it > 0f) count++
        }

        // Well above the old rule's answer — mean(200, 150) = 175 — and below the pressed level.
        assertTrue(
            "the mean must sit far above the two-endpoint answer of 175: was $mean",
            mean > 1750f,
        )
        assertTrue("and below the pressed level: was $mean", mean < pressed)
    }

    /** A pencil gesture that rectifies, pressing at [pressureA] on pen-down and resting at [pressureB]. */
    private fun rectifyPressingAt(pressureA: Float, pressureB: Float): PenStrokeRectifier {
        val r = rectifier()
        r.onPenDown(
            Point2D(100f, 100f, pressure = pressureA),
            "page-a", Pen.PENCIL, 6f, 6f, 0xFF000000.toInt(),
        )
        advance(10)
        r.onMove(Point2D(400f, 400f, pressure = pressureB), canRectify = true)
        r.holdAtRest(Point2D(405f, 405f, pressure = pressureB))
        return r
    }

    @Test
    fun `the pencil band is stamped at the gesture's pressure, not at full`() {
        // Pins that the band stops hard-coding full pressure; both ends read 0.6 here.
        val r = rectifyPressingAt(pressureA = 0.6f, pressureB = 0.6f)

        assertEquals(
            "the charcoal stamp must render at the pen's weight, not full pressure",
            0.6f,
            renderer.bands.last().spec.pressure,
            0f,
        )
    }

    @Test
    fun `the pencil band averages the gesture rather than the pen-down tap`() {
        val r = rectifyPressingAt(pressureA = 0.2f, pressureB = 0.6f)
        val band = renderer.bands.last().spec.pressure

        assertTrue(
            "the 0.2 tap must not set the band's weight: was $band",
            band > 0.2f,
        )
        assertTrue("and it must stay within the pressed range: was $band", band <= 0.6f)
    }

    @Test
    fun `the band is not diluted by a rest that reports no pressure`() {
        val r = rectifier()
        r.onPenDown(
            Point2D(100f, 100f, pressure = 0.6f),
            "page-a", Pen.PENCIL, 6f, 6f, 0xFF000000.toInt(),
        )
        advance(10)
        r.onMove(Point2D(400f, 400f, pressure = 0.6f), canRectify = true)
        // Resting moves report no pressure, and must not pull the level toward nothing.
        r.holdAtRest(Point2D(405f, 405f))

        assertEquals(0.6f, renderer.bands.last().spec.pressure, 0f)
    }

    @Test
    fun `the committed line carries the band's pressure`() {
        val r = rectifier()
        r.onPenDown(
            Point2D(100f, 100f, pressure = 0.2f),
            "page-a", Pen.PENCIL, 6f, 6f, 0xFF000000.toInt(),
        )
        advance(10)
        r.onMove(Point2D(400f, 400f, pressure = 0.6f), canRectify = true)
        r.holdAtRest(Point2D(405f, 405f, pressure = 0.6f))

        val band = renderer.bands.last().spec.pressure
        // Pen-up must commit exactly what the band was drawn at, or the line changes weight on refresh.
        assertEquals("pen-up must equal what the pen was shown at", band, r.penUp(405f, 405f)!!.pressure, 0f)
    }

    @Test
    fun `the commit keeps the stroke's weight when the pen lifts light`() {
        // A firm stroke that taps down light and lifts light must still commit at the firm weight.
        val r = rectifier()
        r.onPenDown(
            Point2D(100f, 100f, pressure = 0.05f),
            "page-a", Pen.BRUSH, 6f, 6f, 0xFF000000.toInt(),
        )
        advance(10)
        r.onMove(Point2D(300f, 300f, pressure = 0.9f), canRectify = true)
        advance(10)
        r.onMove(Point2D(400f, 400f, pressure = 0.9f), canRectify = true)
        r.holdAtRest(Point2D(405f, 405f, pressure = 0.9f))

        val committed = r.penUp(405f, 405f, pressure = 0.01f)!!.pressure

        assertTrue(
            "the light tap must not drag the commit toward nothing: was $committed",
            committed > 0.7f,
        )
    }

    @Test
    fun `the commit is not dragged down by a lift that reports no pressure`() {
        // A silent lift contributes nothing rather than pulling the level toward zero.
        val r = rectifier()
        r.onPenDown(
            Point2D(100f, 100f, pressure = 0.2f),
            "page-a", Pen.PENCIL, 6f, 6f, 0xFF000000.toInt(),
        )
        advance(10)
        r.onMove(Point2D(400f, 400f, pressure = 0.6f), canRectify = true)
        // The resting moves and the lift report no pressure, so they are skipped rather than counted.
        r.holdAtRest(Point2D(405f, 405f))

        // The running mean carries accumulated rounding, so a tolerance rather than exact equality.
        assertEquals(0.4f, r.penUp(405f, 405f)!!.pressure, 0.0001f)
    }

    // --- the band's nib is the pen's width, not the zoomed one --------------

    @Test
    fun `the band carries the pen's own width as its nib`() {
        // The nib must be the unscaled width, or the band only matches the stroke at zoom 1.
        val zoom = 2f
        val r = rectifier()
        r.penDown(100f, 100f, pen = Pen.PENCIL, strokeWidthPx = 12f, strokeWidth = 6f)
        advance(10); r.move(400f, 400f)
        r.holdAtRest(Point2D(405f, 405f))

        val spec = renderer.bands.last().spec
        assertEquals(
            "the nib is what the committed stroke keeps",
            6f,
            spec.strokeWidth,
            0f,
        )
        assertEquals(
            "the geometry is still measured in screen pixels",
            12f,
            spec.strokeWidthPx,
            0f,
        )
        assertTrue(
            "the dirty rect must cover the zoomed band, not the nib",
            spec.strokeWidthPx > spec.strokeWidth,
        )
        assertEquals(6f, rectifyBandStroke(spec, Point2D(0f, 0f), Point2D(10f, 10f), Offset.Zero, zoom).size, 0f)
    }

    @Test
    fun `the band's points match the committed line's count`() {
        // The band must use transformToLine's count and spread, or the grain differs per line length.
        val spec = rectifyBandPaint(Pen.PENCIL, 0xFF000000.toInt(), 12f, 6f)
        val stroke = rectifyBandStroke(
            spec = spec,
            a = Point2D(100f, 100f),
            b = Point2D(500f, 100f),
            scroll = Offset.Zero,
            zoom = 1f,
        )

        assertEquals(100, stroke.points.size)
        // Evenly spread, endpoints included, so the spread matches too and not just the count.
        assertEquals(100f, stroke.points.first().x, 0f)
        assertEquals(500f, stroke.points.last().x, 0f)
        // Checked with a tolerance rather than distinct(): 400/99 is not exact in Float.
        val gaps = stroke.points.zipWithNext { p, q -> q.x - p.x }
        val expected = (500f - 100f) / 99f
        assertTrue(
            "points must be evenly spread",
            gaps.all { kotlin.math.abs(it - expected) < 0.01f },
        )
    }

    @Test
    fun `the band's points land in page coordinates like a captured stroke`() {
        // The same screen-to-page mapping capture uses in TouchPoint.toStrokePoint.
        val spec = rectifyBandPaint(Pen.PENCIL, 0xFF000000.toInt(), 12f, 6f)
        val stroke = rectifyBandStroke(
            spec = spec,
            a = Point2D(200f, 300f),
            b = Point2D(600f, 300f),
            scroll = Offset(50f, 20f),
            zoom = 2f,
        )

        assertEquals(150f, stroke.points.first().x, 0f)   // 200 / 2 + 50
        assertEquals(170f, stroke.points.first().y, 0f)   // 300 / 2 + 20
        assertEquals(350f, stroke.points.last().x, 0f)    // 600 / 2 + 50
    }

    @Test
    fun `the band's points carry the averaged pressure, not a ramp`() {
        val spec = rectifyBandPaint(Pen.PENCIL, 0xFF000000.toInt(), 12f, 6f, pressure = 2048f)
        val stroke = rectifyBandStroke(
            spec = spec,
            a = Point2D(0f, 0f, pressure = 1024f),
            b = Point2D(100f, 0f, pressure = 3072f),
            scroll = Offset.Zero,
            zoom = 1f,
        )

        // Pins the absence of a ramp: no point may differ from another.
        assertEquals("every point at one level", 1, stroke.points.map { it.pressure }.distinct().size)
    }

    // --- the pens that need their own wrapper stamp their band --------------

    @Test
    fun `the pencil band is drawn with the charcoal texture`() {
        val r = rectifier()
        r.penDown(100f, 100f, pen = Pen.PENCIL)
        advance(10); r.move(400f, 400f)
        r.holdAtRest(Point2D(405f, 405f))

        assertTrue(
            "a flat band makes the pencil look like a ballpoint until a canvas refresh",
            renderer.bands.last().spec.stamped,
        )
    }

    @Test
    fun `the brush band is drawn through its wrapper`() {
        assertTrue("the brush needs its pressure-shaped width", stampedBandFor(Pen.BRUSH))
    }

    @Test
    fun `the fountain band is drawn through its wrapper`() {
        assertTrue("the fountain needs its pressure-shaped nib", stampedBandFor(Pen.FOUNTAIN))
    }

    @Test
    fun `the charcoal band is drawn through its wrapper`() {
        assertTrue("charcoal V2 is textured like the pencil", stampedBandFor(Pen.CHARCOAL))
    }

    private fun stampedBandFor(pen: Pen): Boolean {
        clock.now = T0
        val panel = FakeRectifyRenderer()
        val r = PenStrokeRectifier(
            now = { clock.now },
            renderer = panel,
            thresholds = thresholds,
            surfaceSize = { SCREEN_W to SCREEN_H },
        )
        r.penDown(100f, 100f, pen = pen)
        advance(10); r.move(400f, 400f)
        r.holdAtRest(Point2D(405f, 405f))

        assertTrue("a $pen gesture must rectify to have a band to read", panel.bands.isNotEmpty())
        return panel.bands.last().spec.stamped
    }

    @Test
    fun `the ballpoint band is not drawn through a wrapper`() {
        assertFalse(
            "the ballpoint band is already a flat line, which is correct",
            stampedBandFor(Pen.BALLPEN),
        )
    }

    @Test
    fun `the highlighter band is not drawn through a wrapper`() {
        assertFalse(
            "the highlighter's translucent band comes from darken, not a wrapper (2a370696)",
            stampedBandFor(Pen.MARKER),
        )
    }

    @Test
    fun `the calligraphy band is not drawn through a wrapper`() {
        // Left flat: the firmware already draws the chisel nib live.
        assertFalse("calligraphy keeps its current flat band", stampedBandFor(Pen.CALLIGRAPHY))
    }

    @Test
    fun `every other band still keeps the flat ballpoint path`() {
        listOf(Pen.REDBALLPEN, Pen.GREENBALLPEN, Pen.BLUEBALLPEN, Pen.DASHED).forEach { pen ->
            assertFalse("$pen must keep the flat band", stampedBandFor(pen))
        }
    }

    @Test
    fun `the charcoal pencil band still asks for a solid band`() {
        // Stamped and darken are separate rules: the pencil is not translucent.
        val r = rectifier()
        r.penDown(100f, 100f, pen = Pen.PENCIL)
        advance(10); r.move(400f, 400f)
        r.holdAtRest(Point2D(405f, 405f))

        assertFalse("the pencil is not a highlighter", renderer.bands.last().spec.darken)
    }

    @Test
    fun `the band width is the pen width scaled for zoom`() {
        val r = rectifier()
        r.penDown(100f, 100f, strokeWidthPx = 12f, color = 0xFF00FF00.toInt())
        advance(10); r.move(400f, 400f)
        r.holdAtRest(Point2D(405f, 405f))

        val spec = renderer.bands.last().spec
        assertEquals(12f, spec.strokeWidthPx, 0f)
        assertEquals(0xFF00FF00.toInt(), spec.color)
    }

    // --- Soft angle snapping ------------------------------------------------

    /** The same engine with snapping on: 45-degree guides, 5 degrees of tolerance, full strength. */
    private fun snappingRectifier(
        panel: FakeRectifyRenderer = FakeRectifyRenderer(),
    ): Pair<PenStrokeRectifier, FakeRectifyRenderer> {
        val r = PenStrokeRectifier(
            now = { clock.now },
            renderer = panel,
            thresholds = thresholds.copy(
                snapEnabled = true,
                snapStepDeg = 45,
                snapToleranceDeg = 5,
                snapStrength = 1f,
            ),
            surfaceSize = { SCREEN_W to SCREEN_H },
        )
        return r to panel
    }

    /** Angle of the line from [a] to [b], in degrees, screen-space so +y runs downward. */
    private fun lineAngle(a: Point2D, b: Point2D): Double =
        kotlin.math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()) * 180.0 / Math.PI

    /** Down at 100,100 travelling near 45 degrees, then held still until it rectifies. */
    private fun PenStrokeRectifier.rectifyNearFortyFive() {
        penDown(100f, 100f)
        advance(10); move(250f, 227f)   // atan2(127, 150) = 40.2 degrees
        holdAtRest(Point2D(252f, 229f))
    }

    @Test
    fun `a band already travelling on 45 is held there`() {
        // Enters rectify travelling at exactly 45.0 degrees.
        val (r, panel) = snappingRectifier()
        r.penDown(100f, 100f)
        advance(10); r.move(250f, 250f)   // exactly 45 degrees
        r.holdAtRest(Point2D(251f, 251f))
        assertTrue("the gesture must have rectified to have a band", panel.bands.isNotEmpty())

        val band = panel.bands.last()
        assertEquals("the band sits on 45", 45.0, lineAngle(band.a, band.b), 0.5)
    }

    @Test
    fun `a pen pushed off the guide is followed off it`() {
        // The soft half, end to end: enter on 45, then drive the pen to a heading no guide is near.
        val (r, _) = snappingRectifier()
        r.penDown(100f, 100f)
        advance(10); r.move(250f, 250f)
        r.holdAtRest(Point2D(251f, 251f))

        advance(PUSHER_GAP)
        // atan2(300,100) = 71.6 degrees: 26.6 from 45 and 18.4 from 90, so nothing is in range and
        // the pen is obeyed exactly. Not a sticky detent to break out of.
        r.move(200f, 400f)
        val commit = r.penUp(200f, 400f)!!
        assertEquals("the pen is followed, not held", Point2D(200f, 400f), commit.b)
    }

    @Test
    fun `a pen a few degrees off 45 snaps to 45, whatever the dwell was`() {
        // The case a hand actually produces, and the one confirmed on device. The dwell fired at 40.2,
        // the pen then runs out to 44.3, which is 0.7 from the 45 axis and 4.1 from the dwell — so the
        // nearer guide, the axis, takes it. A hand draws a long line, and the far end of a line is
        // where it ended up, not where the pause happened; that is why axis snapping is what you feel.
        val (r, panel) = snappingRectifier()
        r.penDown(100f, 100f)
        advance(10); r.move(250f, 227f)   // dwell fires at 40.2 degrees
        r.holdAtRest(Point2D(252f, 229f))

        advance(PUSHER_GAP)
        r.move(300f, 295f)                // atan2(195, 200) = 44.3 degrees
        val band = panel.bands.last()
        assertEquals("the nearer axis wins", 45.0, lineAngle(band.a, band.b), 0.5)
    }

    @Test
    fun `the dwell beats an axis only when the pen stays within a degree of it`() {
        // The boundary, pinned so the trade-off is visible rather than accidental. The dwell fired at
        // The dwell fired at 40.2 and the pen has barely moved — atan2(189, 220) = 40.7, which is 4.3
        // from the 45 axis. Only the axes are guides now, so this is simply within tolerance of 45 and
        // snaps there, where the removed dwell guide used to hold it near its own heading.
        val (r, panel) = snappingRectifier()
        r.penDown(100f, 100f)
        advance(10); r.move(250f, 227f)
        r.holdAtRest(Point2D(252f, 229f))

        advance(PUSHER_GAP)
        r.move(320f, 289f)                // atan2(189, 220) = 40.7 degrees
        val band = panel.bands.last()
        assertEquals("no dwell guide to hold it, so 45 takes it", 45.0, lineAngle(band.a, band.b), 0.5)
    }

    @Test
    fun `an angle with no axis near it is left exactly as drawn`() {
        // The consequence of dropping the dwell guide: a deliberate diagonal is no longer rescued.
        // 30 degrees is 15 from 45 and 30 from 0, so nothing is in range and the pen is obeyed.
        val (r, panel) = snappingRectifier()
        r.penDown(100f, 100f)
        advance(10); r.move(400f, 273f)   // atan2(173, 300) = 29.98 degrees
        r.holdAtRest(Point2D(402f, 274f))

        val band = panel.bands.last()
        assertEquals("no guide near 30, so the pen keeps its heading", 30.0, lineAngle(band.a, band.b), 0.6)
    }

    @Test
    fun `a wobbling pen near 45 snaps to 45 even though the dwell was off-axis`() {
        // The realistic case, and the one that matters on the device: the dwell fires on a heading of
        // 40.2, but the pen keeps travelling and the band end lands at 44.4, which is 0.6 from the 45
        // axis and 4.2 from the dwell. The nearer guide wins, so 45 takes it — which is why 0/45/90
        // snapping is what a real gesture actually gets.
        val (r, panel) = snappingRectifier()
        r.penDown(100f, 100f)
        advance(10); r.move(250f, 227f)   // dwell fires at 40.2 degrees
        r.holdAtRest(Point2D(252f, 229f))

        advance(PUSHER_GAP)
        r.move(300f, 300f - 5)            // atan2(195, 200) = 44.3 degrees
        val band = panel.bands.last()
        assertEquals("the axis wins when it is nearer", 45.0, lineAngle(band.a, band.b), 0.5)
    }

    @Test
    fun `a wobbling pen near 0 snaps flat even though the dwell was tilted`() {
        val (r, panel) = snappingRectifier()
        r.penDown(100f, 100f)
        advance(10); r.move(400f, 128f)   // dwell fires at 3.96 degrees
        r.holdAtRest(Point2D(401f, 129f))

        advance(PUSHER_GAP)
        // Measured from A at 100,100: atan2(104-100, 600-100) = 0.46 degrees, which is 0.5 from
        // horizontal, so the axis takes it and flattens it.
        r.move(600f, 104f)
        val band = panel.bands.last()
        assertEquals("the horizontal axis takes it", 0.0, lineAngle(band.a, band.b), 0.5)
    }

    @Test
    fun `a wobbling pen near 90 snaps vertical even though the dwell was tilted`() {
        val (r, panel) = snappingRectifier()
        r.penDown(100f, 100f)
        advance(10); r.move(128f, 400f)   // dwell fires at 66 degrees
        r.holdAtRest(Point2D(129f, 401f))

        advance(PUSHER_GAP)
        r.move(108f, 700f)                // atan2(600, 8) = 89.2 degrees
        val band = panel.bands.last()
        assertEquals("the vertical axis takes it", 90.0, lineAngle(band.a, band.b), 0.5)
    }

    @Test
    fun `the committed line is snapped the same way the band was shown`() {
        val (r, panel) = snappingRectifier()
        r.penDown(100f, 100f)
        advance(10); r.move(250f, 250f)   // 45 degrees
        r.holdAtRest(Point2D(251f, 251f))
        advance(PUSHER_GAP)
        r.move(300f, 302f)                // 46 degrees, inside tolerance of the guide

        val commit = r.penUp(301f, 303f)!!
        val band = panel.bands.last()
        assertEquals(
            "the line must not change angle on refresh",
            lineAngle(band.a, band.b),
            lineAngle(commit.a, commit.b),
            0.5,
        )
        assertEquals("and it is on the guide", 45.0, lineAngle(commit.a, commit.b), 0.5)
    }

    @Test
    fun `a line past the tolerance commits exactly where the pen left it`() {
        val (r, _) = snappingRectifier()
        r.rectifyNearFortyFive()
        advance(PUSHER_GAP)
        // Past 50 degrees: outside tolerance of both 45 and 90, so the pen is obeyed exactly. This is
        // the soft half of the snap — nothing is sticky and there is nothing to break out of.
        r.move(200f, 400f)

        val commit = r.penUp(200f, 400f)!!
        assertEquals("A holds", Point2D(100f, 100f), commit.a)
        assertEquals("B is the pen, unnudged", Point2D(200f, 400f), commit.b)
    }

    @Test
    fun `snapping off leaves the band at the pen's angle`() {
        val panel = FakeRectifyRenderer()
        val r = PenStrokeRectifier(
            now = { clock.now },
            renderer = panel,
            thresholds = thresholds.copy(snapEnabled = false),
            surfaceSize = { SCREEN_W to SCREEN_H },
        )
        r.rectifyNearFortyFive()

        assertEquals("the pen's own end", Point2D(252f, 229f), panel.bands.last().b)
    }

    @Test
    fun `the dwell's own angle is a guide, so a straight diagonal holds it`() {
        val (r, panel) = snappingRectifier()
        // Deliberately straight at 30 degrees, which is 15 clear of the 45 guide.
        r.penDown(100f, 100f)
        advance(10); r.move(400f, 273f)   // atan2(173, 300) = 29.98 degrees
        r.holdAtRest(Point2D(402f, 274f))

        val band = panel.bands.last()
        assertEquals(
            "a straight diagonal snaps to itself, not to 45",
            30.0,
            lineAngle(band.a, band.b),
            0.6,
        )
    }

    @Test
    fun `snapping needs no gesture state, so it holds across every push`() {
        // With the guides reduced to the fixed screen axes there is nothing to carry between moves,
        // so every push of one gesture is judged the same way: 30 degrees stays unsnapped however far
        // the pen runs on, and a move into tolerance of 45 snaps there.
        val (r, panel) = snappingRectifier()
        r.penDown(100f, 100f)
        advance(10); r.move(400f, 273f)   // 30 degrees, no guide in range
        r.holdAtRest(Point2D(402f, 274f))
        assertEquals("left alone", 30.0, lineAngle(panel.bands.last().a, panel.bands.last().b), 0.6)

        advance(PUSHER_GAP)
        r.move(500f, 495f)  // atan2(395, 400) = 44.6, inside tolerance of 45
        assertEquals("and the axis takes it once it is near", 45.0, lineAngle(panel.bands.last().a, panel.bands.last().b), 0.5)
    }

    // --- Band geometry ------------------------------------------------------

    @Test
    fun `the band covers the line plus half the stroke and the padding`() {
        val r = rectifier()
        r.penDown(100f, 100f, strokeWidthPx = 10f) // padding = 5 + 4 = 9
        advance(10); r.move(300f, 300f)
        r.holdAtRest(Point2D(302f, 302f))

        // A is 100,100 and B ends at 302,302 (holdAtRest's resting point), with padding 5 + 4 = 9.
        // The lower-left is the origin rather than 91,91 because the first band is unioned into the
        // empty-at-origin rect, exactly as production seeds it from `Rect()`; drawOverFirmware
        // insets the zone anyway, and the band is a line drawn between A and B, so the extra
        // covered area is only pushed, never painted.
        assertEquals(
            RectBounds(0, 0, 311, 311),
            renderer.bands.last().dirty,
        )
    }

    @Test
    fun `a band is clipped to the screen`() {
        val r = rectifier()
        r.penDown(10f, 10f, strokeWidthPx = 100f)
        advance(10); r.move(SCREEN_W - 10f, SCREEN_H - 10f)
        r.holdAtRest(Point2D(SCREEN_W - 12f, SCREEN_H - 12f))

        val dirty = renderer.bands.last().dirty
        assertEquals(0, dirty.left)
        assertEquals(0, dirty.top)
        assertEquals(SCREEN_W, dirty.right)
        assertEquals(SCREEN_H, dirty.bottom)
    }

    // --- The SDK recycles its points ---------------------------------------

    @Test
    fun `the engine holds no reference to a point it was handed`() {
        val a = Point2D(100f, 100f)
        val b = Point2D(400f, 400f)
        val r = rectifier()
        r.onPenDown(a, "page-a", Pen.BALLPEN, 6f, 6f, 0xFF000000.toInt())
        advance(10)
        r.onMove(b, canRectify = true)
        r.holdAtRest(Point2D(405f, 405f))

        // Data classes compare by value, so this pins that A and B survived as values rather than
        // as positions that could have been overwritten: the SDK's TouchPoint is recycled while the
        // gesture runs, and pen-up reads A later still.
        val commit = r.penUp(405f, 405f)
        assertEquals(a, commit!!.a)
        assertEquals(Point2D(405f, 405f), commit.b)
    }

    @Test
    fun `a pen down with no position cannot rectify`() {
        val r = rectifier()
        r.onPenDown(null, "page-a", Pen.BALLPEN, 6f, 6f, 0xFF000000.toInt())
        advance(10)
        r.move(400f, 400f)
        r.holdAtRest(Point2D(405f, 405f))

        assertFalse("with no A there is no line to start, so stay freehand", r.isActive)
        assertEquals("and nothing is pushed", emptyList<Any>(), renderer.calls)
        assertNull("and nothing is committed", r.penUp(405f, 405f))
    }

    @Test
    fun `a rectify gesture commits the page it started on even after another page is named`() {
        val r = rectifier()
        r.penDown(100f, 100f, pageId = "page-a")
        advance(10); r.move(400f, 400f)
        r.holdAtRest(Point2D(405f, 405f))
        assertEquals("page-a", r.penUp(405f, 405f)!!.pageId)
    }
}
