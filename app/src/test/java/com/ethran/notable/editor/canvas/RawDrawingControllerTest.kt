package com.ethran.notable.editor.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Whatever the order of inputs, refreshes, erase commits and session rebuilds, the pen state the
 * firmware ends up with is the one wanted, and it never freezes the screen on the way there.
 */
class RawDrawingControllerTest {

    /** Behaves like TouchHelper in onyxsdk-pen 1.5.8 (see [PenDevice]). */
    private class FakePenDevice : PenDevice {
        var created = false
        var render = false
        var input = false

        /** The pen style was set after the last setRawDrawingEnabled, which resets it. */
        var armed = false

        override fun setRawDrawingEnabled(enabled: Boolean) {
            setRenderEnabled(enabled)
            setInputEnabled(enabled)
            armed = false
        }

        override fun setRenderEnabled(enabled: Boolean) {
            if (created && render != enabled) render = enabled
        }

        override fun setInputEnabled(enabled: Boolean) {
            if (created && input != enabled) input = enabled
        }

        override fun openSession() {
            render = false
            input = false
            created = true
        }

        override fun closeSession() {
            created = false
        }
    }

    /** Virtual time; tasks run in time order, then in the order they were scheduled. */
    private class FakeTimer : MainTimer {
        private class Task(val at: Long, val seq: Long, val action: () -> Unit) {
            var cancelled = false
        }

        var now = 0L
            private set
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

    private class Harness {
        val device = FakePenDevice()
        val timer = FakeTimer()
        var wanted = true
        val controller = RawDrawingController(
            device = device,
            wanted = { wanted },
            armPen = { device.armed = true },
            drawPage = {},
            forcePage = {},
            timer = timer,
            renderResumeMillis = { 300 },
            eraseSettleMillis = { area -> if (area) 500 else 150 },
            checkThread = {},
        )

        /** Holds whenever the state was reconciled or not. */
        fun assertNeverFrozen(step: String) {
            assertFalse("$step: render on with input off (frozen screen)", device.render && !device.input)
            assertEquals("$step: record matches the device", device.input, controller.enabled)
            assertTrue("$step: enabled without the pen style", !device.input || device.armed)
        }

        /** Holds once the latest change of [wanted] was reconciled. */
        fun assertOffIsImmediate(step: String) {
            if (!wanted) {
                assertFalse("$step: input on while not wanted", device.input)
                assertFalse("$step: render on while not wanted", device.render)
            }
        }

        fun assertConverged(step: String) {
            val expected = device.created && wanted
            assertEquals("$step: input converges", expected, device.input)
            assertEquals("$step: render converges", expected, device.render)
        }
    }

    @Test
    fun randomSchedulesNeverFreezeAndConverge() {
        repeat(500) { seed ->
            val random = Random(seed)
            val h = Harness()
            h.controller.rebuildSession()
            // The collector reconciles one dispatch after a change; other work can run in between.
            var reconcilePending = false
            val trace = mutableListOf<String>()
            repeat(60) {
                val op = when (random.nextInt(9)) {
                    0, 1 -> {
                        h.wanted = !h.wanted
                        reconcilePending = true
                        "wanted=${h.wanted}"
                    }
                    2 -> { h.controller.postPage(null); "postPage" }
                    3 -> {
                        val area = random.nextBoolean()
                        h.controller.commitErase(null, area)
                        "commitErase(area=$area)"
                    }
                    4 -> { h.controller.rebuildSession(); "rebuildSession" }
                    5 -> {
                        if (random.nextInt(4) == 0) {
                            h.controller.closeSession(); "closeSession"
                        } else "noop"
                    }
                    else -> {
                        val ms = random.nextLong(0, 800)
                        h.timer.advanceBy(ms)
                        "advance($ms)"
                    }
                }
                if (reconcilePending && random.nextInt(3) != 0) {
                    h.controller.reconcile()
                    reconcilePending = false
                }
                trace += op
                val step = "seed $seed, after ${trace.joinToString()}"
                h.assertNeverFrozen(step)
                if (!reconcilePending) h.assertOffIsImmediate(step)
            }
            h.controller.reconcile()
            h.timer.advanceUntilIdle()
            val step = "seed $seed, at the end of ${trace.joinToString()}"
            h.assertNeverFrozen(step)
            h.assertConverged(step)
        }
    }

    @Test
    fun rebuildingTheSessionWhileDrawingKeepsThePenOn() {
        val h = Harness()
        h.controller.rebuildSession()
        h.controller.rebuildSession() // toolbar toggled
        h.assertConverged("after a rebuild")
        assertTrue(h.device.input)
    }

    @Test
    fun aRefreshBeforeThePenTurnsOffDoesNotTurnTheRenderBackOn() {
        val h = Harness()
        h.controller.rebuildSession()
        h.controller.postPage(null)
        h.wanted = false
        h.controller.reconcile()
        h.timer.advanceUntilIdle()
        assertFalse(h.device.render)
        assertFalse(h.device.input)
    }

    @Test
    fun theSettleOfAnEraseKeepsThePenOffUntilItEnds() {
        val h = Harness()
        h.controller.rebuildSession()
        h.controller.commitErase(null, areaErase = false)
        h.controller.reconcile() // e.g. a toolbar toggle mid-settle
        assertFalse(h.device.input)
        h.timer.advanceBy(150)
        assertTrue(h.device.input)
    }
}
