package com.ethran.notable.editor.canvas

import android.graphics.Rect

/**
 * The raw-drawing calls of one Onyx `TouchHelper`, as the SDK (onyxsdk-pen 1.5.8) implements them:
 * - [setRenderEnabled] and [setInputEnabled] do nothing unless a session is open and their own
 *   flag differs from the value;
 * - [setRawDrawingEnabled] is render, then input, then a reset of the pen defaults (eraser
 *   channel, stroke style), which runs every time;
 * - [openSession] clears both flags; after [closeSession] every setter is a no-op.
 *
 * Only [RawDrawingController] calls these.
 */
interface PenDevice {
    fun setRawDrawingEnabled(enabled: Boolean)
    fun setRenderEnabled(enabled: Boolean)
    fun setInputEnabled(enabled: Boolean)

    /** Opens a session over the current drawing area; render and input start off. */
    fun openSession()
    fun closeSession()
}

/** Runs actions later on the main thread. */
fun interface MainTimer {
    /** Runs [action] after [delayMillis]; the returned function cancels it. */
    fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit
}

/**
 * Sole owner of one surface's raw-drawing state. Everything that turns the pen, its render or its
 * input on or off goes through here, so there is one record of what the SDK was told ([enabled])
 * and one place that decides.
 *
 * Rules:
 * - It reconciles, it does not execute commands: [reconcile] applies [wanted] as read now, and
 *   every delayed action re-checks before it applies.
 * - Off is immediate; on waits for the holds (an erase settling, no session).
 * - Main thread only ([checkThread]).
 * - Its timers belong to the surface's scope ([timer]) and die with it.
 *
 * [enabled] lags [wanted] by one dispatch: the caller reconciles from a collector of the state.
 * Code that needs what the panel is doing reads [enabled].
 *
 * @param wanted whether the pen should draw now (`ToolbarUiState.isDrawing`).
 * @param armPen configures the eraser channel and pen style; run after every enable, since each
 *   enable resets them.
 * @param drawPage draws the page to the surface synchronously; a no-op without a surface.
 * @param forcePage like [drawPage], but forced through to the panel while raw drawing still owns
 *   the screen (enablePost).
 * @param renderResumeMillis how long a post needs before the firmware render comes back.
 * @param eraseSettleMillis how long the panel settles after an erase commit, by area erase or not.
 */
class RawDrawingController(
    private val device: PenDevice,
    private val wanted: () -> Boolean,
    private val armPen: () -> Unit,
    private val drawPage: (Rect?) -> Unit,
    private val forcePage: (Rect?) -> Unit,
    private val timer: MainTimer,
    private val renderResumeMillis: () -> Long,
    private val eraseSettleMillis: (areaErase: Boolean) -> Long,
    private val checkThread: () -> Unit,
) {
    private var sessionOpen = false

    /** What the SDK was last told: render and input on. */
    var enabled = false
        private set

    /** Erase commits still settling; the pen stays off until they end. */
    private var erasing = 0
    private var cancelRenderResume: (() -> Unit)? = null

    /**
     * The surface was created or resized, or the toolbar moved: recreates the session over the new
     * drawing area, then turns the pen back on if wanted.
     */
    fun rebuildSession() {
        checkThread()
        stopRenderResume()
        device.setRawDrawingEnabled(false)
        device.closeSession()
        device.openSession()
        sessionOpen = true
        enabled = false // openSession cleared the SDK's flags
        reconcile()
    }

    /** The surface was destroyed. */
    fun closeSession() {
        checkThread()
        stopRenderResume()
        device.setRawDrawingEnabled(false)
        device.closeSession()
        sessionOpen = false
        enabled = false
    }

    /** Applies [wanted]. Call after any of its inputs changes; holds call it when they end. */
    fun reconcile() {
        checkThread()
        val want = sessionOpen && erasing == 0 && wanted()
        if (want == enabled) return
        stopRenderResume()
        if (want) {
            // The panel shows the page before the firmware takes the screen.
            drawPage(null)
            device.setRawDrawingEnabled(true)
            armPen()
        } else {
            // Freeze the firmware render, show the page, then drop the firmware layer: the
            // newest strokes never blink.
            device.setRenderEnabled(false)
            drawPage(null)
            device.setRawDrawingEnabled(false)
        }
        enabled = want
    }

    /**
     * Shows [dirty] of the page (null: all of it). While the pen is on, the firmware render is
     * frozen so the post reaches the panel, and comes back after a settle if the pen is still on.
     * Repeated calls restart the settle.
     */
    fun postPage(dirty: Rect?) {
        checkThread()
        if (enabled) {
            device.setRenderEnabled(false)
            stopRenderResume()
            cancelRenderResume = timer.schedule(renderResumeMillis()) {
                cancelRenderResume = null
                if (enabled) device.setRenderEnabled(true)
            }
        }
        drawPage(dirty)
    }

    /**
     * kreader's erase commit: block input, push the erased page while frozen, drop the firmware
     * layer (eraser track, scribble ink), settle, and turn the pen back on if still wanted.
     */
    fun commitErase(dirty: Rect?, areaErase: Boolean) {
        checkThread()
        if (!enabled) {
            // Nothing to drop; show the erased page.
            drawPage(dirty)
            return
        }
        stopRenderResume()
        device.setInputEnabled(false)
        forcePage(dirty)
        device.setRawDrawingEnabled(false)
        enabled = false
        erasing++
        timer.schedule(eraseSettleMillis(areaErase)) {
            erasing--
            reconcile()
        }
    }

    private fun stopRenderResume() {
        cancelRenderResume?.invoke()
        cancelRenderResume = null
    }
}
