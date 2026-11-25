package com.ethran.notable.editor.canvas

import android.graphics.Rect
import android.view.View
import com.ethran.notable.editor.utils.ERASER_SWATH_WIDTH
import com.ethran.notable.editor.utils.Eraser
import com.onyx.android.sdk.device.Device
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import io.shipbook.shipbooksdk.ShipBook

private val log = ShipBook.getLogger("RawDrawing")

/**
 * The raw-drawing calls of one Onyx `TouchHelper`, as the SDK (onyxsdk-pen 1.5.9) implements them:
 * - [setRenderEnabled] and [setInputEnabled] do nothing unless a session is open and their own
 *   flag differs from the value;
 * - [setDrawingEnabled] is render, then input, then [resetPenDefaults], which runs every time;
 * - [openSession] clears both flags; after [closeSession] every setter is a no-op;
 * - [closeSession] also drops the pen manager's host view (`EpdPenManager.quitDrawing`), and
 *   `openRawDrawing` does not restore it, so [openSession] binds the host view again first.
 * The style setters apply whether input and render are on or not.
 *
 * Only [RawDrawingController] calls these, and only [TouchHelperPenDevice] calls the SDK.
 */
interface PenDevice {
    /** `setRawDrawingEnabled`. */
    fun setDrawingEnabled(enabled: Boolean)

    /** `isRawDrawingRenderEnabled =`. */
    fun setRenderEnabled(enabled: Boolean)

    /** `setRawInputReaderEnable`. */
    fun setInputEnabled(enabled: Boolean)

    /** `resetPenDefaultRawDrawing`: brush channel on, eraser channel off (device-wide). */
    fun resetPenDefaults()

    /**
     * Binds the host view, then opens a session over the current drawing area; render and input
     * start off. Works the same for the first session and after [closeSession].
     */
    fun openSession()

    /** `closeRawDrawing`: every setter is a no-op until the next [openSession]. */
    fun closeSession()
}

/** A [PenDevice] for surfaces without an Onyx pen: there is no firmware layer to drive. */
object NoPenDevice : PenDevice {
    override fun setDrawingEnabled(enabled: Boolean) = Unit
    override fun setRenderEnabled(enabled: Boolean) = Unit
    override fun setInputEnabled(enabled: Boolean) = Unit
    override fun resetPenDefaults() = Unit
    override fun openSession() = Unit
    override fun closeSession() = Unit
}

/**
 * [PenDevice] over a real [TouchHelper]: the only place that calls the SDK's raw-drawing switches
 * and sets the firmware's live pen style. Logs every switch, so the log shows exactly what the
 * firmware was told and in which order.
 *
 * The style setters are for the input handler, which knows the tool. Each reset
 * ([PenDevice.resetPenDefaults]) clears the eraser channel, so the style is set again after it.
 *
 * @param hostView and [callback]: what the helper was created with, bound again on every
 *   [openSession] (as Onyx's pen demo does before each `openRawDrawing`).
 * @param area the drawing area for a new session: (limit, exclude).
 */
class TouchHelperPenDevice(
    private val helper: TouchHelper,
    private val hostView: View,
    private val callback: RawInputCallback,
    private val area: () -> Pair<Rect, Rect>,
) : PenDevice {
    override fun setDrawingEnabled(enabled: Boolean) =
        timed("setRawDrawingEnabled($enabled)") { helper.setRawDrawingEnabled(enabled) }

    override fun setRenderEnabled(enabled: Boolean) =
        timed("setRawDrawingRenderEnabled($enabled)", verbose = true) {
            helper.isRawDrawingRenderEnabled = enabled
        }

    override fun setInputEnabled(enabled: Boolean) =
        timed("setRawInputReaderEnable($enabled)") { helper.setRawInputReaderEnable(enabled) }

    override fun resetPenDefaults() =
        timed("resetPenDefaultRawDrawing") { helper.resetPenDefaultRawDrawing() }

    override fun openSession() {
        val (limit, exclude) = area()
        timed("openRawDrawing(limit=$limit, exclude=$exclude)") {
            helper.debugLog(false)
            // closeRawDrawing cleared the pen manager's host view; the limit rect set here
            // replaces the visible rect bindHostView sets.
            helper.bindHostView(hostView, callback)
            helper.setLimitRect(mutableListOf(limit)).setExcludeRect(listOf(exclude)).openRawDrawing()
        }
    }

    override fun closeSession() = timed("closeRawDrawing") { helper.closeRawDrawing() }

    /** The live stroke: firmware [style], [width] in screen pixels, [color]. */
    fun setStroke(style: Int, width: Float, color: Int) =
        timed("setStroke(style=$style, width=$width)") {
            helper.setStrokeStyle(style)?.setStrokeWidth(width)?.setStrokeColor(color)
        }

    /**
     * The live stroke as the lasso eraser's dotted line: the DASH style with its dash parameters
     * ([SELECT_ERASER_PARAMS]; device-wide, not per helper).
     */
    fun setDashedStroke(width: Float, color: Int) {
        setStroke(SELECT_ERASER_STYLE, width, color)
        timed("setStrokeParameters($SELECT_ERASER_STYLE)") {
            Device.currentDevice().setStrokeParameters(SELECT_ERASER_STYLE, SELECT_ERASER_PARAMS)
        }
    }

    /** See [enableNativeEraser]. */
    fun enableEraserTrack(eraser: Eraser) =
        timed("enableEraserTrack($eraser)") { enableNativeEraser(helper, eraser) }

    /**
     * The eraser track's colour. The eraser channel carries no colour of its own; the firmware
     * paints the track with the global stroke colour, so this is set when an erase begins.
     */
    fun setEraserTrackColor() =
        timed("setStrokeColor(eraser track)") { helper.setStrokeColor(ERASER_INDICATOR_COLOR) }

    /** See [configureCalligraphyLiveAngle]. */
    fun setCalligraphyAngle(angleDegrees: Float, strokeWidth: Float) =
        timed("setCalligraphyAngle($angleDegrees)") {
            configureCalligraphyLiveAngle(angleDegrees, strokeWidth)
        }
}

/**
 * Runs one SDK call and logs it with its duration. Some of these calls are slow on a BOOX (setting
 * up the surface took 50+ ms on a Note 4c), and the log shows which and how often.
 */
private inline fun timed(call: String, verbose: Boolean = false, block: () -> Unit) {
    val start = System.nanoTime()
    block()
    val message = "$call: %.1f ms".format((System.nanoTime() - start) / 1e6)
    if (verbose) log.v(message) else log.d(message)
}

/**
 * Firmware stroke style dedicated to the native eraser track (the drag-erase indicator). Not a
 * public [TouchHelper] constant (those stop at 7). Its width is bound to
 * [Device.setStrokeParameters], independent of the pen's stroke config. This is the ONLY reliable
 * style: with STROKE_STYLE_MARKER the eraser channel falls back to the global setStrokeWidth (thin
 * pen) and the first-erase bug returns.
 */
private const val ERASER_STROKE_STYLE = 8

/**
 * Native eraser indicator thickness. Bound to [ERASER_SWATH_WIDTH] so the indicator is drawn at
 * exactly the diameter `handleErase` deletes.
 */
private const val ERASER_STROKE_WIDTH = ERASER_SWATH_WIDTH

/**
 * Style 8's 2nd/3rd SurfaceFlinger shape params. These are per-style SHAPE params (edge/smooth),
 * NOT colour — confirmed on a device: sweeping param[1] from 0.01..0.8 changes the track only
 * subtly and never its colour. The track colour is set separately via setStrokeColor
 * ([TouchHelperPenDevice.setEraserTrackColor]).
 */
private const val ERASER_STROKE_PARAM1 = 0.5f
private const val ERASER_STROKE_PARAM2 = 1f // effect unknown; the value found to work

/**
 * Colour of the native eraser track. The eraser channel carries no colour of its own; the firmware
 * draws the track using the global setStrokeColor state (SET_STROKE_COLOR). Applied when an erase
 * begins. Tune here for a lighter/darker grey.
 */
private val ERASER_INDICATOR_COLOR = android.graphics.Color.BLACK

/**
 * Firmware DASH stroke style ([TouchHelper.STROKE_STYLE_DASH]) used for the LASSO/SELECT eraser
 * track: a dotted outline.
 *
 * Params, found by experiment on a BOOX; none is verified:
 * - [0] = 5: probably the line thickness.
 * - [1] = 9, [2] = 9, [3] = 0: unknown. Probably dash length, gap length and phase (the shape of
 *   Android's DashPathEffect). A sweep of each on a device would settle it.
 */
private const val SELECT_ERASER_STYLE = 5
private val SELECT_ERASER_PARAMS = floatArrayOf(5f, 9f, 9f, 0f)

/**
 * Enables the firmware's native eraser-stroke rendering for pen side-button erasing.
 * MUST be called after every [PenDevice.resetPenDefaults] (which turns it off); the input handler's
 * armPen does.
 * Wrapped in try/catch because the Onyx SDK is unstable across devices/firmware.
 *
 * Configures the eraser style's parameters PROACTIVELY via [Device.setStrokeParameters]: the
 * eraser track width is then bound to the eraser STYLE's own parameters, NOT the pen's current
 * setStrokeWidth. This decouples the indicator from the active pen so the side-button eraser
 * renders at the correct thickness on the FIRST erase after drawing with a thin pen (set when an
 * erase begins, the width would land after the firmware's stroke snapshot). Must be the
 * dedicated style 8: with STROKE_STYLE_MARKER the eraser channel ignores these params and falls
 * back to the pen's global width.
 */
private fun enableNativeEraser(touchHelper: TouchHelper, eraser: Eraser) {
    try {
        when (eraser) {
            // Lasso/select erase: a dotted outline (firmware DASH style 5).
            Eraser.SELECT -> {
                Device.currentDevice()
                    .setStrokeParameters(SELECT_ERASER_STYLE, SELECT_ERASER_PARAMS)
                touchHelper.setEraserRawDrawingEnabled(true, SELECT_ERASER_STYLE)
            }
            // Drag/pen erase: the wide marker track (style 8), width from its own params.
            Eraser.PEN -> {
                Device.currentDevice().setStrokeParameters(
                    ERASER_STROKE_STYLE,
                    floatArrayOf(ERASER_STROKE_WIDTH, ERASER_STROKE_PARAM1, ERASER_STROKE_PARAM2)
                )
                touchHelper.setEraserRawDrawingEnabled(true, ERASER_STROKE_STYLE)
            }
        }
    } catch (t: Throwable) {
        log.w("setEraserRawDrawingEnabled not supported on this device: ${t.message}")
    }
}

/** Firmware square-pen (calligraphy) stroke style — [TouchHelper.STROKE_STYLE_SQUARE_PEN]. */
private const val SQUARE_PEN_STYLE = 7

/**
 * Configures the firmware's LIVE square-pen (calligraphy) nib angle so the ink drawn while writing
 * matches Notable's dry re-render (NeoSquarePenWrapper, +45°). Without this the firmware uses its
 * default nib orientation and the stroke visibly rotates on pen-up. The style's params:
 * params[1] = width (≤10), params[2] = angle.
 */
private fun configureCalligraphyLiveAngle(angleDegrees: Float, strokeWidth: Float) {
    try {
        val params = Device.currentDevice().getStrokeParameters(SQUARE_PEN_STYLE) ?: return
        if (params.size < 3) return
        params[1] = strokeWidth.coerceAtMost(10f)
        params[2] = angleDegrees
        Device.currentDevice().setStrokeParameters(SQUARE_PEN_STYLE, params)
    } catch (t: Throwable) {
        log.w("configureCalligraphyLiveAngle not supported on this device: ${t.message}")
    }
}
