package com.ethran.notable.editor.canvas

import android.os.SystemClock
import com.ethran.notable.utils.checkMainThread
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Sole owner of one canvas surface: what it shows (the page or a preview [P]) and, with an Onyx
 * pen, whether the pen draws. Every write to the surface and every raw-drawing switch happens in
 * [pump].
 *
 * It reconciles, it does not execute commands. Events only record what is wanted (an area of the
 * page to show, a stroke drawn, a preview, an erase to commit) and call [pump]; timers only call
 * [pump]. [pump]
 * compares that with what was written and what the SDK was told, and takes every step the rules
 * allow, in one fixed order. So the rules hold whatever the order of events, late callbacks and
 * failed writes:
 * - The pen is on or off as a whole: input and the firmware render together. A page write never
 *   runs under the pen. While the pen is on, a write turns it off
 *   (render frozen, whole page posted, firmware layer dropped) and it comes back once the post
 *   has had [postSettleMillis] to reach the panel; writes meanwhile extend that. A stroke begun in
 *   that window is not drawn. (A firmware render held with input on misses strokes, and a render
 *   back before the post settles loses the post with the firmware's ink: both measured on a BOOX.)
 * - An erase commit blocks input, pushes the erased area and every stroke only the firmware shows
 *   ([strokeDrawn]), drops the layer, and keeps the pen off for its own settle. The pen is
 *   configured before its input comes on.
 * - While a preview owns the surface ([showPreview] until [endPreview] by the same owner, a
 *   QuickNav scrub), nothing writes the page and the pen is off; page changes wait, and the end
 *   of the preview shows the whole page. Delayed work of an earlier owner cannot end it.
 * - A write that fails leaves its area stale and the pen off. Any later event writes it, and so
 *   does a recovery wake ([RECOVERY_MS], at most [RECOVERY_ATTEMPTS] in a row); after those the
 *   pen comes back anyway. A new surface and the end of a preview start a fresh run of them.
 * - Without a session (no surface) nothing is written; a new session shows the whole page.
 * - Main thread only ([checkThread]); timers run on the editor's scope ([timer]).
 *
 * Without an Onyx pen, [device] is [NoPenDevice] and [wanted] is false: the same surface rules.
 *
 * @param wanted whether the pen should draw now (`ToolbarUiState.isDrawing`).
 * @param armPen configures the eraser channel and pen style; run after every
 *   [PenDevice.resetPenDefaults], which clears them.
 * @param writeSurface draws an area (null: all of it) of the page, or of a preview, to the
 *   surface synchronously, and says whether that content was drawn and posted. When forced, the
 *   post goes through to the panel while raw drawing still owns the screen (enablePost).
 * @param postSettleMillis how long a post needs to reach the panel before the pen comes back.
 * @param eraseSettleMillis how long the panel settles after an erase commit, by area erase or not.
 * @param log one line per [pump] that changed something: its trigger, the steps, the state left.
 */
class RawDrawingController<P : Any>(
    private val device: PenDevice,
    private val wanted: () -> Boolean,
    private val armPen: () -> Unit,
    private val writeSurface: (area: ScreenArea?, preview: P?, force: Boolean) -> Boolean,
    private val timer: MainTimer,
    private val postSettleMillis: () -> Long,
    private val eraseSettleMillis: (areaErase: Boolean) -> Long,
    private val checkThread: () -> Unit = { checkMainThread("RawDrawingController") },
    private val log: (String) -> Unit = { rawDrawingLog.d(it) },
) {
    // --- What the SDK was told ---

    /** Counts the sessions opened, so the log tells one session from the next. */
    private var session = 0
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
     * Strokes drawn since the firmware layer was last dropped: only the firmware shows them, so an
     * erase commit pushes them with the erased area before dropping the layer. null: none.
     */
    private var firmwareInk: ScreenArea? = null

    /** An erase to commit; see [commitErase]. */
    private var pendingErase: PendingErase? = null

    // --- Timing ---

    /** The pen stays off until this: a post or an erase commit is settling. */
    private var penOffUntil = 0L

    /** Failed writes in a row since the last successful one, one per [pump] at most. */
    private var failures = 0

    /** A write failed in the current [pump]: the surface is not taking posts, so no more tries. */
    private var failedThisPump = false
    private var cancelWake: (() -> Unit)? = null

    private val steps = mutableListOf<String>()

    // --- Events ---

    /**
     * The surface was created or resized, or the toolbar moved: recreates the session over the new
     * drawing area. The whole page (or the preview) is shown again, then the pen comes back on.
     */
    fun rebuildSession() {
        checkThread()
        device.setDrawingEnabled(false)
        device.closeSession()
        device.openSession()
        session++
        sessionOpen = true
        enabled = false // openSession cleared the SDK's flags
        newSurface()
        steps += "session opened"
        pump("session rebuilt")
    }

    /** The surface was destroyed. Nothing is written until [rebuildSession]. */
    fun closeSession() {
        checkThread()
        device.setDrawingEnabled(false)
        device.closeSession()
        sessionOpen = false
        enabled = false
        newSurface()
        steps += "session closed"
        pump("session closed")
    }

    /** Applies [wanted]. Call after any of its inputs changes. */
    fun reconcile() {
        checkThread()
        // An input changed (a popup, focus, a selection): what the panel shows may be stale.
        markPage(Stale.Whole)
        pump("reconcile")
    }

    /** Shows [area] of the page (null: all of it), now or as soon as the page owns the surface. */
    fun showPage(area: ScreenArea?) {
        checkThread()
        markPage(Stale.of(area))
        pump("show page")
    }

    /**
     * [owner] (a QuickNav scrub) shows [preview] over the whole surface until it calls
     * [endPreview]; the pen is off meanwhile. A later owner takes the surface over. Returns
     * whether the preview is on the surface now (if not, it is retried).
     */
    fun showPreview(owner: Int, preview: P): Boolean {
        checkThread()
        this.preview = preview
        previewOwner = owner
        previewShown = false
        pump("show preview")
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
        pump("end preview")
        return true
    }

    /**
     * Commits an erase over [area] (null: all of it), run by [pump]: block
     * input, push the erased area while frozen, drop the firmware layer (eraser track, scribble
     * ink), and keep the pen off while the panel settles. Without the pen on there is no layer to
     * drop: the area is just shown.
     */
    fun commitErase(area: ScreenArea?, areaErase: Boolean) {
        checkThread()
        if (enabled) {
            pendingErase = PendingErase(area, eraseSettleMillis(areaErase))
        } else {
            markPage(Stale.of(area))
        }
        pump("erase commit")
    }

    /**
     * A pen stroke over [area] is in the page bitmap. With the pen on, the firmware shows it until
     * its layer is dropped; otherwise (the pen went off before the stroke was handled, so the page
     * was posted without it) it is shown like any page change.
     */
    fun strokeDrawn(area: ScreenArea) {
        checkThread()
        if (enabled) {
            firmwareInk = firmwareInk?.union(area) ?: area
        } else {
            markPage(Stale.Part(area))
            pump("stroke")
        }
    }

    // --- The pump: every write and every pen switch ---

    /**
     * Takes every step the current state allows, in order: erase commit, pen off, surface content,
     * pen on; then wakes itself for the next deadline.
     */
    private fun pump(trigger: String) {
        cancelWake?.invoke()
        cancelWake = null
        failedThisPump = false
        if (sessionOpen) {
            pendingErase?.let { commitPendingErase(it) }
            if (enabled && (!penWanted() || pageStale != null)) {
                // A write needs the pen off; if it is still wanted, it comes back after the post.
                val back = penWanted()
                turnPenOff()
                if (back) holdPenFor(postSettleMillis())
            }
            writeContent()
            if (!enabled && penWanted() && pageShown()) turnPenOn()
            scheduleWake()
        }
        if (steps.isNotEmpty()) {
            log("session $session ($trigger): ${steps.joinToString("; ")} -> ${state()}")
            steps.clear()
        }
    }

    /** The pen should draw now: wanted, the page owns the surface, nothing is settling. */
    private fun penWanted() = preview == null && timer.nowMillis >= penOffUntil && wanted()

    /** The page is on the panel, or recovery gave up on it (the pen should not stay off). */
    private fun pageShown() = pageStale == null || failures > RECOVERY_ATTEMPTS

    private fun commitPendingErase(erase: PendingErase) {
        pendingErase = null
        if (!enabled) return markPage(Stale.of(erase.area)) // the pen went off first: just show it
        val area = firmwareInk?.let { ink -> erase.area?.union(ink) } ?: erase.area
        device.setInputEnabled(false)
        val pushed = write(area, null, force = true)
        if (!pushed) markPage(Stale.of(area))
        dropFirmwareLayer()
        holdPenFor(erase.settleMillis)
        steps += "erase commit ${area ?: "whole"} " +
            "(${if (pushed) "pushed" else "push FAILED"}), settle ${erase.settleMillis} ms"
    }

    /** Freezes the render, shows the whole page, then drops the firmware layer: no stroke blinks. */
    private fun turnPenOff() {
        device.setRenderEnabled(false)
        if (preview == null) {
            markPage(Stale.Whole)
            writePage()
        }
        dropFirmwareLayer()
        steps += "pen off"
    }

    /** Raw drawing off: the firmware layer and every stroke only it showed are gone. */
    private fun dropFirmwareLayer() {
        device.setDrawingEnabled(false)
        enabled = false
        firmwareInk = null
    }

    private fun turnPenOn() {
        device.resetPenDefaults()
        armPen()
        device.setInputEnabled(true)
        device.setRenderEnabled(true)
        enabled = true
        steps += "pen on"
    }

    /** Writes what the surface still lacks; the pen is off by now. */
    private fun writeContent() {
        val shown = preview
        if (shown == null) {
            writePage()
        } else if (!previewShown && !failedThisPump) {
            previewShown = write(null, shown, force = false)
            steps += "preview ${if (previewShown) "shown" else "FAILED"}"
        }
    }

    /** A plain post of the stale page area. A post during a hold extends it. */
    private fun writePage() {
        val stale = pageStale ?: return
        if (failedThisPump) return
        if (!write(stale.area, null, force = false)) {
            steps += "page post FAILED ${stale.area ?: "whole"}"
            return
        }
        pageStale = null
        if (timer.nowMillis < penOffUntil) holdPenFor(postSettleMillis())
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

    /** Every write goes through here: counts failures for the recovery wake, one per pump. */
    private fun write(area: ScreenArea?, preview: P?, force: Boolean): Boolean {
        val ok = writeSurface(area, preview, force)
        if (ok) {
            failures = 0
        } else if (!failedThisPump) {
            failures++
            failedThisPump = true
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
            pump("timer")
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
            delay(delayMillis)
            action()
        }
        return { job.cancel() }
    }
}

private val rawDrawingLog = ShipBook.getLogger("RawDrawing")

/** An idle canvas retries a failed write this often, this many times in a row. */
private const val RECOVERY_MS = 500L
private const val RECOVERY_ATTEMPTS = 5
