package com.ethran.notable.editor.canvas

import android.os.SystemClock
import com.ethran.notable.utils.checkMainThread
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

private val rawDrawingLog = ShipBook.getLogger("RawDrawing")

/** An idle canvas retries a failed write this often, this many times in a row. */
private const val RECOVERY_MS = 500L
private const val RECOVERY_ATTEMPTS = 5

/**
 * Owns one canvas surface: what it shows (the page or a preview [P]) and whether the Onyx pen
 * draws. Events only record what is wanted and call [applyState]. Apart from opening and closing
 * the session, only [applyState] writes the surface or switches the pen, so these rules hold
 * whatever the order of events:
 * - The pen is on or off as a whole (input and firmware render together) and no page write runs
 *   under it. A write while the pen is on turns it off; it comes back [postSettleMillis] after
 *   the last post. With the pen already off (a popup closing), it comes back right after the post.
 * - An erase commit pushes the erased area and the strokes only the firmware shows, exits raw
 *   drawing, and keeps the pen off for [eraseSettleMillis].
 * - While a preview is shown ([showPreview] until [endPreview] by the same owner) the page is not
 *   written and the pen is off; afterwards the whole page is shown.
 * - A failed write keeps its area stale and the pen off. It is retried every [RECOVERY_MS], at most
 *   [RECOVERY_ATTEMPTS] times, then the pen comes back anyway. A new surface, a new preview owner
 *   and the end of a preview start a fresh run of retries. A post after a failure settles like a
 *   write under the pen.
 * - Without a session nothing is written; a new session shows the whole page. Main thread only.
 *
 * @param drawingWanted whether the user wants the pen to draw now (`ToolbarUiState.isDrawing`).
 * @param configurePen eraser channel and pen style; run after every [PenDevice.resetPenDefaults].
 * @param postToSurface draws an area (null: all) of the page or a preview and posts it; returns
 *   whether it was posted. Forced: the post reaches the panel while raw drawing owns the screen.
 */
class RawDrawingController<P : Any>(
    private val pen: PenDevice,
    private val drawingWanted: () -> Boolean,
    private val configurePen: () -> Unit,
    private val postToSurface: (area: ScreenArea?, preview: P?, force: Boolean) -> Boolean,
    private val timer: MainTimer,
    private val postSettleMillis: () -> Long,
    private val eraseSettleMillis: (areaErase: Boolean) -> Long,
    private val checkThread: () -> Unit = { checkMainThread("RawDrawingController") },
    private val log: (String) -> Unit = { rawDrawingLog.d(it) },
) {
    // --- What the SDK was told ---

    private var sessionOpen = false

    /** The pen draws: input and render on (what the SDK was last told). */
    var enabled = false
        private set

    // --- What the surface should show, and what is still to write ---

    /** A preview owns the surface (QuickNav); null: the page does. */
    private var preview: P? = null

    /** Who showed [preview]: only they end it ([endPreview]). */
    private var previewOwner: Int? = null

    /** [preview] is on the surface. */
    private var previewShown = false

    /** Page area not known to be on the panel. null: none. */
    private var pageStale: Stale? = null

    /**
     * Strokes drawn since raw drawing was last exited: only the firmware shows them, so an erase
     * commit pushes them with the erased area before exiting. null: none.
     */
    private var firmwareInk: ScreenArea? = null

    /** An erase to commit; see [commitErase]. */
    private var pendingErase: PendingErase? = null

    // --- Timing ---

    /** The pen stays off until this: a post or an erase commit is settling. */
    private var penOffUntil = 0L

    /** Failed writes in a row since the last successful one, one per [applyState] at most. */
    private var failures = 0

    /** A write failed in the current pass: the surface is not taking posts, so no more tries. */
    private var failedThisPass = false
    private var cancelWake: (() -> Unit)? = null

    private val steps = mutableListOf<String>()

    // --- Events ---

    /**
     * The surface was created or resized, or the toolbar moved: recreates the session over the new
     * drawing area. The whole page (or the preview) is shown again, then the pen comes back on.
     */
    fun rebuildSession() {
        checkThread()
        pen.setDrawingEnabled(false)
        pen.closeSession()
        pen.openSession()
        sessionOpen = true
        enabled = false // openSession cleared the SDK's flags
        newSurface()
        steps += "session opened"
        applyState("session rebuilt")
    }

    /** The surface was destroyed. Nothing is written until [rebuildSession]. */
    fun closeSession() {
        checkThread()
        pen.setDrawingEnabled(false)
        pen.closeSession()
        sessionOpen = false
        enabled = false
        newSurface()
        steps += "session closed"
        applyState("session closed")
    }

    /**
     * One of [drawingWanted]'s inputs changed (a popup, focus, a selection): applies it, and shows
     * the whole page again, since what the panel shows may be stale.
     */
    fun drawingConditionsChanged() {
        checkThread()
        markPage(Stale.Whole)
        applyState("drawing conditions changed")
    }

    /** Shows [area] of the page (null: all of it), now or as soon as the page owns the surface. */
    fun showPage(area: ScreenArea?) {
        checkThread()
        markPage(Stale.of(area))
        applyState("show page")
    }

    /**
     * [owner] (a QuickNav scrub) shows [preview] over the whole surface until it calls
     * [endPreview]; the pen is off meanwhile. A later owner takes the surface over. Returns
     * whether the preview is on the surface now (if not, it is retried).
     */
    fun showPreview(owner: Int, preview: P): Boolean {
        checkThread()
        // A new owner gets its own retries; later previews of the same owner share them.
        if (owner != previewOwner) failures = 0
        this.preview = preview
        previewOwner = owner
        previewShown = false
        applyState("show preview")
        return previewShown
    }

    /**
     * [owner]'s preview ends and the whole page is shown again. Returns false, doing nothing, if
     * no preview of [owner] owns the surface (none was shown, or a later owner's is).
     */
    fun endPreview(owner: Int): Boolean {
        checkThread()
        if (preview == null || previewOwner != owner) return false
        preview = null
        previewOwner = null
        previewShown = false
        failures = 0
        markPage(Stale.Whole)
        applyState("end preview")
        return true
    }

    /**
     * Commits an erase over [area] (null: all of it), run by [applyState]: block input, push the
     * erased area while frozen, exit raw drawing (which clears the eraser track and scribble ink),
     * and keep the pen off while the panel settles. With the pen off there is nothing to clear:
     * the area is just shown.
     */
    fun commitErase(area: ScreenArea?, areaErase: Boolean) {
        checkThread()
        if (enabled) {
            pendingErase = PendingErase(area, eraseSettleMillis(areaErase))
        } else {
            markPage(Stale.of(area))
        }
        applyState("erase commit")
    }

    /**
     * A pen stroke over [area] is in the page bitmap. With the pen on, the firmware shows it until
     * raw drawing is exited; otherwise (the pen went off before the stroke was handled, so the
     * page was posted without it) it is shown like any page change.
     */
    fun strokeDrawn(area: ScreenArea) {
        checkThread()
        if (enabled) {
            firmwareInk = firmwareInk?.union(area) ?: area
        } else {
            markPage(Stale.Part(area))
            applyState("stroke")
        }
    }

    // --- Every write and every pen switch ---

    /**
     * Brings the surface and the pen to what the events asked for, in this order: commits a
     * pending erase, turns the pen off if a write needs it, writes what the surface lacks, turns
     * the pen back on if allowed. Then sets a timer for the next deadline (a hold ending, a retry).
     */
    private fun applyState(trigger: String) {
        cancelWake?.invoke()
        cancelWake = null
        failedThisPass = false
        if (sessionOpen) {
            pendingErase?.let { commitPendingErase(it) }
            if (enabled && (!penAllowed() || pageStale != null)) {
                // A write needs the pen off; if it is still allowed, it comes back after the post.
                val back = penAllowed()
                turnPenOff() // freezes, posts the whole page, exits raw drawing
                if (back) holdPenFor(postSettleMillis())
            }
            writeContent()
            // The page is on the panel, or recovery gave up on it (the pen should not stay off).
            val pageReadyOrRetriesExhausted = pageStale == null || failures > RECOVERY_ATTEMPTS
            if (!enabled && penAllowed() && pageReadyOrRetriesExhausted) turnPenOn()
            scheduleWake()
        }
        if (steps.isNotEmpty()) {
            log("$trigger: ${steps.joinToString("; ")} -> ${state()}")
            steps.clear()
        }
    }

    /** The pen may draw now: wanted, the page owns the surface, nothing is settling. */
    private fun penAllowed() = preview == null && timer.nowMillis >= penOffUntil && drawingWanted()

    private fun commitPendingErase(erase: PendingErase) {
        pendingErase = null
        if (!enabled) return markPage(Stale.of(erase.area)) // the pen went off first: just show it
        val area = firmwareInk?.let { ink -> erase.area?.union(ink) } ?: erase.area
        pen.setInputEnabled(false)
        val pushed = write(area, null, force = true)
        if (!pushed) markPage(Stale.of(area))
        exitRawDrawing()
        holdPenFor(erase.settleMillis)
        steps += "erase commit ${area ?: "whole"} " +
            "(${if (pushed) "pushed" else "push FAILED"}), settle ${erase.settleMillis} ms"
    }

    /** Freezes the render, shows the whole page, then exits raw drawing: no stroke blinks. */
    private fun turnPenOff() {
        pen.setRenderEnabled(false)
        if (preview == null) {
            markPage(Stale.Whole)
            writePage()
        }
        exitRawDrawing()
        steps += "pen off"
    }

    /**
     * `setRawDrawingEnabled(false)`: input and render off; the firmware layer and every stroke only
     * it showed are cleared.
     */
    private fun exitRawDrawing() {
        pen.setDrawingEnabled(false)
        enabled = false
        firmwareInk = null
    }

    private fun turnPenOn() {
        pen.resetPenDefaults()
        configurePen()
        pen.setInputEnabled(true)
        pen.setRenderEnabled(true)
        enabled = true
        steps += "pen on"
    }

    /** Writes what the surface still lacks; the pen is off by now. */
    private fun writeContent() {
        val shown = preview
        if (shown == null) {
            writePage()
        } else if (!previewShown && !failedThisPass) {
            previewShown = write(null, shown, force = false)
            steps += "preview ${if (previewShown) "shown" else "FAILED"}"
        }
    }

    /** A plain post of the stale page area. A post during a hold, or after a failure, holds. */
    private fun writePage() {
        val stale = pageStale ?: return
        if (failedThisPass) return
        // Read before the write, which resets it.
        val recovering = failures > 0
        if (!write(stale.area, null, force = false)) {
            steps += "page post FAILED ${stale.area ?: "whole"}"
            return
        }
        pageStale = null
        if (recovering || timer.nowMillis < penOffUntil) holdPenFor(postSettleMillis())
        steps += "page posted ${stale.area ?: "whole"}"
    }

    /** Keeps the pen off for at least [millis] from now. */
    private fun holdPenFor(millis: Long) {
        penOffUntil = max(penOffUntil, timer.nowMillis + millis)
    }

    /** A new surface (or none) shows nothing yet: the whole page, or the preview, is to write. */
    private fun newSurface() {
        previewShown = false
        pendingErase = null
        firmwareInk = null
        failures = 0
        markPage(Stale.Whole)
    }

    /** Every write goes through here: counts failures for the recovery wake, one per pass. */
    private fun write(area: ScreenArea?, preview: P?, force: Boolean): Boolean {
        val ok = postToSurface(area, preview, force)
        if (ok) {
            failures = 0
        } else if (!failedThisPass) {
            failures++
            failedThisPass = true
        }
        return ok
    }

    private fun markPage(stale: Stale) {
        pageStale = pageStale with stale
    }

    /** Wakes for the end of a hold, or to retry a failed write while recovery lasts. */
    private fun scheduleWake() {
        val now = timer.nowMillis
        val lacking = pageStale != null || (preview != null && !previewShown)
        val recovery = if (lacking && failures in 1..RECOVERY_ATTEMPTS) now + RECOVERY_MS else 0L
        val next = listOf(penOffUntil, recovery).filter { it > now }.minOrNull() ?: return
        cancelWake = timer.schedule(next - now) {
            cancelWake = null
            applyState("timer")
        }
    }

    private fun state() =
        "pen=${if (enabled) "on" else "off"}, open=$sessionOpen, " +
            "surface=${if (preview == null) "page" else if (previewShown) "preview" else "preview pending"}, " +
            "page stale=${pageStale ?: "none"}, failures=$failures"

    private class PendingErase(val area: ScreenArea?, val settleMillis: Long)
}

/** An area of the surface in screen pixels (Android's Rect, without the Android dependency). */
data class ScreenArea(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun union(other: ScreenArea) = ScreenArea(
        min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom)
    )

    override fun toString() = "[$left,$top][$right,$bottom]"
}

/** Part of the surface still to write: all of it, or one area. */
private sealed interface Stale {
    /** For the writer: null means all of it. */
    val area: ScreenArea?

    data object Whole : Stale {
        override val area: ScreenArea? get() = null
    }

    data class Part(override val area: ScreenArea) : Stale

    companion object {
        fun of(area: ScreenArea?): Stale = area?.let { Part(it) } ?: Whole
    }
}

/** This and [other] together; null is nothing. */
private infix fun Stale?.with(other: Stale): Stale = when {
    this == null -> other
    this is Stale.Part && other is Stale.Part -> Stale.Part(area.union(other.area))
    else -> Stale.Whole
}

/** Runs actions later on the main thread. */
interface MainTimer {
    /** The current time on the clock [schedule] counts in. */
    val nowMillis: Long

    /** Runs [action] after [delayMillis]; the returned function cancels it. */
    fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit
}

/** A [MainTimer] on [scope], which must run on the main thread; cancelled with it. */
class ScopeMainTimer(private val scope: CoroutineScope) : MainTimer {
    override val nowMillis get() = SystemClock.uptimeMillis()

    override fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit {
        val job = scope.launch {
            delay(delayMillis.milliseconds)
            action()
        }
        return { job.cancel() }
    }
}
