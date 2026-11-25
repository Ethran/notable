package com.ethran.notable.editor.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The controller's rules, checked at every write and every SDK call against fakes of the panel
 * and the SDK. A random test drives the same events production sends (pen intent, refreshes,
 * erases, previews and their ends, surface teardown, failed writes, late callbacks) in any order;
 * the scenario tests pin the sequences reviews and device tests asked about.
 */
class RawDrawingControllerTest {

    /**
     * Behaves like TouchHelper in onyxsdk-pen 1.5.9 (see [PenDevice]), and fails on the call orders
     * that misbehave on a panel: input on before the pen is configured, the render on without
     * input (a frozen screen), the pen defaults reset with input on.
     */
    private class FakePenDevice : PenDevice {
        var created = false
        var render = false
        var input = false

        /** The pen style was set after the last [resetPenDefaults]. */
        var armed = false

        /** Called before input changes, with the new value. */
        var beforeInputChange: (Boolean) -> Unit = {}

        override fun setDrawingEnabled(enabled: Boolean) {
            setRenderEnabled(enabled)
            setInputEnabled(enabled)
            resetPenDefaults()
        }

        override fun setRenderEnabled(enabled: Boolean) {
            if (!created || render == enabled) return
            if (enabled) assertTrue("render on without input (frozen screen)", input)
            render = enabled
        }

        override fun setInputEnabled(enabled: Boolean) {
            if (!created || input == enabled) return
            if (enabled) assertTrue("input on before the pen is configured", armed)
            beforeInputChange(enabled)
            input = enabled
        }

        override fun resetPenDefaults() {
            assertFalse("pen defaults reset with input on", input && created)
            armed = false
        }

        override fun openSession() {
            render = false
            input = false
            created = true
        }

        override fun closeSession() {
            created = false
            render = false
            input = false
        }
    }

    /** Virtual time; tasks run in time order, then in the order they were scheduled. */
    private class FakeTimer : MainTimer {
        private class Task(val at: Long, val seq: Long, val action: () -> Unit) {
            var cancelled = false
        }

        var now = 0L
            private set
        override val nowMillis get() = now
        private var seq = 0L
        private val tasks = mutableListOf<Task>()

        override fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit {
            val task = Task(now + delayMillis, seq++, action)
            tasks += task
            return { task.cancelled = true }
        }

        fun advanceBy(millis: Long) = runUntil(now + millis)

        fun advanceUntilIdle() = runUntil(Long.MAX_VALUE)

        private fun runUntil(time: Long) {
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= time }
                    .minWithOrNull(compareBy({ it.at }, { it.seq })) ?: break
                tasks.remove(next)
                now = maxOf(now, next.at)
                next.action()
            }
            tasks.removeAll { it.cancelled }
            if (time != Long.MAX_VALUE) now = maxOf(now, time)
        }
    }

    /** One write to the surface, with the pen state at that moment. */
    private data class Write(
        val at: Long,
        val area: ScreenArea?,
        val preview: String?,
        val force: Boolean,
        val ok: Boolean,
        val input: Boolean,
        val render: Boolean,
    ) {
        val wholePage get() = preview == null && area == null
    }

    private class Harness {
        val timer = FakeTimer()
        val device = FakePenDevice()
        var wanted = true

        /** Whether the surface takes posts. */
        var surfaceValid = true

        /** The preview QuickNav shows; null: the page owns the surface. */
        var preview: String? = null
        val writes = mutableListOf<Write>()

        /** Page changes not yet known to be on the panel (null: the whole page). */
        val pending = mutableListOf<ScreenArea?>()

        /**
         * When the page was last written under the pen while it was still wanted: the pen was
         * turned off for that write, and must stay off for the settle.
         */
        private var refreshOffAt: Long? = null

        val controller = RawDrawingController<String>(
            device = device,
            wanted = { wanted },
            armPen = { device.armed = true },
            writeSurface = { area, preview, force ->
                writes += Write(timer.now, area, preview, force, surfaceValid, device.input, device.render)
                assertTrue("write without a session", device.created)
                if (preview == null) {
                    assertEquals("page written over a preview", null, this.preview)
                } else {
                    assertEquals("a preview not asked for", this.preview, preview)
                }
                if (!force) assertFalse("plain post under the firmware render", device.render)
                if (force) assertFalse("forced push with input on", device.input)
                if (preview == null && !force && device.input && wanted) refreshOffAt = timer.now
                if (surfaceValid && preview == null) pending.removeAll { covers(area, it) }
                surfaceValid
            },
            timer = timer,
            postSettleMillis = { SETTLE },
            eraseSettleMillis = { if (it) 500L else 150L },
            checkThread = {},
            log = {},
        )

        init {
            device.beforeInputChange = { on ->
                if (on) {
                    refreshOffAt?.let {
                        assertTrue("pen back ${timer.now - it} ms after a refresh", timer.now - it >= SETTLE)
                    }
                    refreshOffAt = null
                }
            }
        }

        // The events production sends, each noting the page change it makes.

        fun rebuildSession() {
            pending += null
            controller.rebuildSession()
        }

        fun closeSession() = controller.closeSession()

        fun reconcile() {
            pending += null
            controller.reconcile()
        }

        fun showPage(area: ScreenArea?) {
            pending += area
            controller.showPage(area)
        }

        fun commitErase(area: ScreenArea, areaErase: Boolean) {
            pending += area
            controller.commitErase(area, areaErase)
        }

        /** A stroke handled; the firmware shows it if the pen is still on, else the app does. */
        fun strokeDrawn(area: ScreenArea) {
            if (!controller.enabled) pending += area
            controller.strokeDrawn(area)
        }

        /** The QuickNav scrub under way (or the last one). */
        var scrub = 1

        /** Who showed [preview]. */
        var previewOwner: Int? = null

        fun showPreview(token: String, scrub: Int = this.scrub) {
            preview = token
            previewOwner = scrub
            controller.showPreview(scrub, token)
        }

        /** [scrub] ended; its preview ends only if it still owns the surface. */
        fun endPreview(scrub: Int = this.scrub) {
            val ends = preview != null && previewOwner == scrub
            if (!ends) {
                assertFalse("ended someone else's preview", controller.endPreview(scrub))
                return
            }
            preview = null
            previewOwner = null
            pending += null
            assertTrue(controller.endPreview(scrub))
        }

        fun pageWrites() = writes.filter { it.preview == null }

        /** Holds after every event. */
        fun assertInvariants(step: String) {
            assertEquals("$step: input and render together", device.input, device.render)
            assertEquals("$step: record matches the device", device.input, controller.enabled)
            assertTrue("$step: enabled without the pen style", !device.input || device.armed)
            if (preview != null) assertFalse("$step: pen on over a preview", device.input)
        }

        fun assertConverged(step: String) {
            val expected = device.created && wanted && preview == null
            assertEquals("$step: pen converges", expected, device.input)
            assertTrue("$step: page changes never shown: $pending", pending.isEmpty() || !device.created)
        }

        private fun covers(written: ScreenArea?, change: ScreenArea?) = when {
            written == null -> true
            change == null -> false
            else -> written.left <= change.left && written.top <= change.top &&
                written.right >= change.right && written.bottom >= change.bottom
        }
    }

    private fun area(random: Random): ScreenArea {
        val x = random.nextInt(0, 90)
        val y = random.nextInt(0, 90)
        return ScreenArea(x, y, x + random.nextInt(1, 10), y + random.nextInt(1, 10))
    }

    private val box = ScreenArea(10, 10, 20, 20)
    private val otherBox = ScreenArea(50, 50, 60, 60)

    @Test
    fun randomSchedulesKeepTheRulesAndShowEveryChange() {
        repeat(1000) { seed ->
            val random = Random(seed)
            val h = Harness()
            h.rebuildSession()
            // The collector reconciles one dispatch after a change; other work can run in between.
            var reconcilePending = false
            val trace = mutableListOf<String>()
            repeat(60) {
                val op = when (random.nextInt(36)) {
                    in 0..5 -> {
                        h.wanted = !h.wanted
                        reconcilePending = true
                        "wanted=${h.wanted}"
                    }
                    in 6..9 -> {
                        val a = if (random.nextBoolean()) null else area(random)
                        h.showPage(a)
                        "showPage($a)"
                    }
                    in 10..12 -> {
                        val a = area(random)
                        val areaErase = random.nextBoolean()
                        h.commitErase(a, areaErase)
                        "commitErase($a, $areaErase)"
                    }
                    22, 23 -> {
                        // Also a late one, handled after the pen went off.
                        val a = area(random)
                        h.strokeDrawn(a)
                        "strokeDrawn($a)"
                    }
                    13, 14 -> { h.rebuildSession(); "rebuildSession" }
                    15 -> { h.closeSession(); "closeSession" }
                    16, 17 -> {
                        h.surfaceValid = !h.surfaceValid
                        "surfaceValid=${h.surfaceValid}"
                    }
                    18, 19 -> {
                        // A new scrub, often before the end of the last one is handled.
                        h.scrub++
                        val token = "preview$it"
                        h.showPreview(token)
                        "showPreview(${h.scrub}, $token)"
                    }
                    20 -> { h.endPreview(); "endPreview(${h.scrub})" }
                    21 -> { h.endPreview(h.scrub - 1); "late endPreview(${h.scrub - 1})" }
                    else -> {
                        val ms = random.nextLong(0, 800)
                        h.timer.advanceBy(ms)
                        "advance($ms)"
                    }
                }
                if (reconcilePending && random.nextInt(3) != 0) {
                    h.reconcile()
                    reconcilePending = false
                }
                trace += op
                h.assertInvariants("seed $seed, after ${trace.joinToString()}")
            }
            // Scrubbing ends (whichever way), the surface works and is there, the collector catches up.
            h.previewOwner?.let { h.endPreview(it) }
            h.surfaceValid = true
            if (!h.device.created) h.rebuildSession()
            if (reconcilePending) h.reconcile()
            h.showPage(ScreenArea(0, 0, 1, 1)) // any later event writes what failed before
            h.timer.advanceUntilIdle()
            val step = "seed $seed, at the end of ${trace.joinToString()}"
            h.assertInvariants(step)
            h.assertConverged(step)
        }
    }

    // --- Pen on and off ---

    @Test
    fun openingASessionShowsThePageThenTurnsThePenOn() {
        val h = Harness()
        h.rebuildSession()
        assertTrue(h.writes.single().wholePage)
        assertTrue(h.device.input)
        assertTrue(h.device.render)
    }

    @Test
    fun rebuildingTheSessionWhileDrawingKeepsThePenOn() {
        val h = Harness()
        h.rebuildSession()
        h.rebuildSession() // toolbar toggled
        assertTrue(h.device.input)
        h.assertConverged("after a rebuild")
    }

    @Test
    fun turningThePenOffShowsThePageBeforeTheFirmwareLayerIsDropped() {
        val h = Harness()
        h.rebuildSession()
        val before = h.writes.size
        h.wanted = false
        h.reconcile()
        val w = h.writes.drop(before).single()
        assertTrue("the whole page", w.wholePage)
        assertTrue("posted while the firmware still read the pen", w.input)
        assertFalse("with its render frozen", w.render)
        assertFalse(h.device.input)
    }

    @Test
    fun closingAPopupBringsThePenBackAtOnce() {
        val h = Harness()
        h.rebuildSession()
        h.wanted = false // a popup opens
        h.reconcile()
        h.timer.advanceBy(1_000)
        h.wanted = true // and closes
        h.reconcile()
        assertTrue("the page is shown first", h.writes.last().wholePage)
        assertTrue("and the pen is back with no wait", h.device.input)
    }

    @Test
    fun thePenIsConfiguredBeforeItsInputComesOn() {
        val h = Harness()
        // The fake fails if input comes on unarmed or the pen is reset with input on.
        h.rebuildSession()
        h.wanted = false
        h.reconcile()
        h.wanted = true
        h.reconcile()
        assertTrue(h.device.input)
        assertTrue(h.device.armed)
    }

    // --- Refreshes while the pen draws ---

    @Test
    fun aRefreshTurnsThePenOffUntilThePostHasSettled() {
        val h = Harness()
        h.rebuildSession()
        h.showPage(box) // a scroll, the line tool, a selection
        val w = h.writes.last()
        assertTrue("the whole page: the firmware layer is dropped", w.wholePage)
        assertFalse(h.device.input)
        h.timer.advanceBy(SETTLE - 1)
        assertFalse("off while the post settles", h.device.input)
        h.timer.advanceBy(1)
        assertTrue(h.device.input)
    }

    @Test
    fun refreshesDuringTheHoldExtendIt() {
        val h = Harness()
        h.rebuildSession()
        h.showPage(null) // a scroll starts
        h.timer.advanceBy(200)
        h.showPage(null) // and goes on
        h.timer.advanceBy(SETTLE - 1)
        assertFalse(h.device.input)
        h.timer.advanceBy(1)
        assertTrue(h.device.input)
    }

    @Test
    fun theEndOfAHoldFollowsTheLatestIntent() {
        val h = Harness()
        h.rebuildSession()
        h.showPage(null) // the pen is held off
        h.wanted = false // a popup opened; its collector has not reconciled yet
        h.timer.advanceUntilIdle()
        assertFalse("not back over the popup", h.device.input)
        h.reconcile()
        h.wanted = true
        h.reconcile()
        h.assertConverged("after the popup closed")
    }

    @Test
    fun aWriteThatFailsOnceIsRecoveredOnAnIdleCanvas() {
        val h = Harness()
        h.rebuildSession()
        h.surfaceValid = false
        h.showPage(box)
        h.timer.advanceBy(SETTLE) // the hold ends; the write fails again
        assertFalse("the pen waits for the page", h.device.input)
        h.surfaceValid = true
        h.timer.advanceBy(500) // no editor event: the recovery wake
        assertTrue(h.pageWrites().last().let { it.ok && it.wholePage })
        h.timer.advanceUntilIdle()
        h.assertConverged("after the recovery")
    }

    @Test
    fun aFailedRefreshIsWrittenByTheNextEvent() {
        val h = Harness()
        h.rebuildSession()
        h.surfaceValid = false
        h.showPage(box)
        h.surfaceValid = true
        h.showPage(otherBox) // before the recovery wake
        assertTrue(h.pageWrites().last().let { it.ok && it.wholePage })
        h.timer.advanceUntilIdle()
        h.assertConverged("after the next event")
    }

    // --- Erase ---

    @Test
    fun theSettleOfAnEraseKeepsThePenOffUntilItEnds() {
        val h = Harness()
        h.rebuildSession()
        h.commitErase(box, areaErase = false)
        assertFalse(h.device.input)
        h.timer.advanceBy(149)
        assertFalse(h.device.input)
        h.timer.advanceBy(1)
        assertTrue(h.device.input)
    }

    @Test
    fun anEraseCommitPushesOnlyTheErasedArea() {
        val h = Harness()
        h.rebuildSession()
        val writes = h.writes.size
        h.commitErase(box, areaErase = true)
        h.timer.advanceUntilIdle()
        val push = h.writes.drop(writes).single()
        assertEquals(box, push.area)
        assertTrue("forced, with input off", push.force)
        assertTrue(h.device.input)
    }

    @Test
    fun aFailedErasePushIsWrittenWhenTheSettleEnds() {
        val h = Harness()
        h.rebuildSession()
        h.surfaceValid = false
        h.commitErase(box, areaErase = false)
        h.surfaceValid = true
        h.timer.advanceBy(150)
        assertEquals(box, h.pageWrites().last().area)
        assertTrue(h.pageWrites().last().ok)
        h.timer.advanceUntilIdle()
        h.assertConverged("after the failed erase")
    }

    @Test
    fun aFailedErasePushIsWrittenByTheNextEventWhileThePenStaysOff() {
        val h = Harness()
        h.rebuildSession()
        h.surfaceValid = false
        h.commitErase(box, areaErase = false)
        h.surfaceValid = true
        h.wanted = false // a popup opens during the settle
        h.reconcile()
        h.timer.advanceUntilIdle()
        assertTrue("shown with the pen off", h.pending.isEmpty())
        assertFalse(h.device.input)
    }

    // --- Strokes only the firmware shows ---

    @Test
    fun anEraseCommitAlsoPushesTheStrokesOnlyTheFirmwareShows() {
        val h = Harness()
        h.rebuildSession()
        h.strokeDrawn(otherBox)
        h.commitErase(box, areaErase = false)
        assertEquals(box.union(otherBox), h.writes.last().area)
    }

    @Test
    fun droppingTheFirmwareLayerForgetsItsStrokes() {
        val h = Harness()
        h.rebuildSession()
        h.strokeDrawn(otherBox)
        h.showPage(null) // the pen goes off: the page is posted with the stroke, the layer dropped
        h.timer.advanceUntilIdle()
        h.commitErase(box, areaErase = false)
        assertEquals("only the erased area", box, h.writes.last().area)
    }

    @Test
    fun aStrokeHandledAfterThePenWentOffIsPostedByTheApp() {
        val h = Harness()
        h.rebuildSession()
        h.wanted = false // a popup opened right after the stroke, before its points were handled
        h.reconcile()
        h.strokeDrawn(box)
        assertEquals(box, h.writes.last().area)
        assertTrue(h.pending.isEmpty())
    }

    // --- QuickNav previews ---

    @Test
    fun nothingWritesThePageWhileAPreviewOwnsTheSurface() {
        val h = Harness()
        h.rebuildSession()
        h.showPreview("p1")
        assertFalse("pen off for the preview", h.device.input)
        val writes = h.writes.size
        h.commitErase(box, areaErase = false) // a late erase
        h.showPage(null) // a background redraw
        h.surfaceValid = false
        h.timer.advanceUntilIdle()
        h.surfaceValid = true
        assertEquals("no page write over the preview", writes, h.writes.size)
        h.endPreview()
        h.timer.advanceUntilIdle()
        assertTrue("the end of the preview shows every change", h.pending.isEmpty())
        h.assertConverged("after the preview")
    }

    @Test
    fun scrubbingToAnotherPageShowsThatPageWhenTheScrubEnds() {
        val h = Harness()
        h.rebuildSession()
        h.showPreview("p1")
        h.showPage(null) // the chosen page is switched in and redrawn before the scrub's end lands
        assertEquals("withheld while the preview owns the surface", "p1", h.writes.last().preview)
        h.endPreview()
        assertTrue(h.pageWrites().last().let { it.ok && it.wholePage })
        h.assertConverged("on the new page")
    }

    @Test
    fun aLateEndOfAnEarlierScrubDoesNotEndTheNextOne() {
        val h = Harness()
        h.rebuildSession()
        h.showPreview("p1", scrub = 1)
        h.showPreview("p2", scrub = 2) // the next scrub starts before the first one's end lands
        val writes = h.writes.size
        h.endPreview(scrub = 1) // ignored: scrub 2 owns the surface
        assertEquals(writes, h.writes.size)
        assertFalse(h.device.input)
        h.endPreview(scrub = 2)
        assertTrue(h.pageWrites().last().wholePage)
        h.assertConverged("after scrub 2")
    }

    @Test
    fun aFailedEndOfPreviewKeepsThePenOffUntilThePageIsShown() {
        val h = Harness()
        h.rebuildSession()
        h.showPreview("p1")
        h.surfaceValid = false
        h.endPreview()
        assertFalse("the pen waits for the page", h.device.input)
        h.surfaceValid = true
        h.showPage(box) // the next event
        assertTrue("the whole page, not just box", h.pageWrites().last().let { it.ok && it.wholePage })
        h.assertConverged("after the page was shown")
    }

    @Test
    fun theEndOfAPreviewStartsAFreshRunOfRecovery() {
        val h = Harness()
        h.rebuildSession()
        h.surfaceValid = false
        h.showPreview("p1") // fails, and so do all its recovery wakes
        h.timer.advanceUntilIdle()
        h.endPreview() // its first write fails too
        h.surfaceValid = true
        h.timer.advanceBy(500)
        assertTrue("recovered without another event", h.pageWrites().last().let { it.ok && it.wholePage })
    }

    @Test
    fun aNewSurfaceStartsAFreshRunOfRecovery() {
        val h = Harness()
        h.rebuildSession()
        h.surfaceValid = false
        h.showPage(box) // fails, and so do all its recovery wakes
        h.timer.advanceUntilIdle()
        h.closeSession()
        h.rebuildSession() // its first write fails too
        h.surfaceValid = true
        h.timer.advanceBy(500)
        assertTrue("recovered without another event", h.pageWrites().last().let { it.ok && it.wholePage })
    }

    @Test
    fun aFailedPreviewIsReplacedByTheNextOne() {
        val h = Harness()
        h.rebuildSession()
        h.surfaceValid = false
        h.showPreview("p1")
        h.surfaceValid = true
        h.showPreview("p2") // scrubbing goes on
        assertEquals("p2", h.writes.last().preview)
        assertTrue(h.writes.last().ok)
    }

    @Test
    fun aRebuiltSurfaceShowsThePreviewAgain() {
        val h = Harness()
        h.rebuildSession()
        h.showPreview("p1")
        h.closeSession()
        h.rebuildSession()
        assertEquals("p1", h.writes.last().preview)
        assertFalse(h.device.input)
    }

    private companion object {
        const val SETTLE = 300L
    }
}
