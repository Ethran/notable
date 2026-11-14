package com.ethran.notable.editor.canvas

import android.graphics.Rect
import android.os.Looper
import com.ethran.notable.BuildConfig
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
     * The panel shows the current page: an erase commit pushed it, and no input change or session
     * rebuild has happened since. The next enable then skips drawing the page again.
     */
    private var panelCurrent = false

    /**
     * The surface was created or resized, or the toolbar moved: recreates the session over the new
     * drawing area, then turns the pen back on if wanted.
     */
    fun rebuildSession() {
        checkThread()
        stopRenderResume()
        device.setDrawingEnabled(false)
        device.closeSession()
        device.openSession()
        sessionOpen = true
        enabled = false // openSession cleared the SDK's flags
        panelCurrent = false
        apply()
    }

    /** The surface was destroyed. */
    fun closeSession() {
        checkThread()
        stopRenderResume()
        device.setDrawingEnabled(false)
        device.closeSession()
        sessionOpen = false
        enabled = false
    }

    /** Applies [wanted]. Call after any of its inputs changes. */
    fun reconcile() {
        checkThread()
        // An input changed (a popup, focus, a selection): what the panel shows may be stale.
        panelCurrent = false
        apply()
    }

    private fun apply() {
        val want = sessionOpen && erasing == 0 && wanted()
        if (want == enabled) return
        stopRenderResume()
        if (want) {
            // The panel shows the page before the firmware takes the screen.
            if (!panelCurrent) drawPage(null)
            device.setDrawingEnabled(true)
            armPen()
        } else {
            // Freeze the firmware render, show the page, then drop the firmware layer: the
            // newest strokes never blink.
            device.setRenderEnabled(false)
            drawPage(null)
            device.setDrawingEnabled(false)
        }
        enabled = want
        panelCurrent = false
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
        device.setDrawingEnabled(false)
        enabled = false
        erasing++
        panelCurrent = true
        timer.schedule(eraseSettleMillis(areaErase)) {
            erasing--
            apply()
        }
    }

    private fun stopRenderResume() {
        cancelRenderResume?.invoke()
        cancelRenderResume = null
    }
}

/** Runs actions later on the main thread. */
fun interface MainTimer {
    /** Runs [action] after [delayMillis]; the returned function cancels it. */
    fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit
}

/** A [MainTimer] on [scope], which must run on the main thread; cancelled with it. */
class ScopeMainTimer(private val scope: CoroutineScope) : MainTimer {
    override fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit {
        val job = scope.launch {
            delay(delayMillis)
            action()
        }
        return { job.cancel() }
    }
}

/** Fails in debug builds, logs in release, when called off the main thread. */
fun checkRawDrawingMainThread() {
    if (Looper.myLooper() == Looper.getMainLooper()) return
    val message = "RawDrawingController called off the main thread"
    if (BuildConfig.DEBUG) error(message)
    else ShipBook.getLogger("RawDrawing").e(message)
}
