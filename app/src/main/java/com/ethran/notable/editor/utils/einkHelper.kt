package com.ethran.notable.editor.utils

import android.graphics.Rect
import android.os.Build
import android.view.View
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.utils.logCallStack
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.EpdController.SCHEME_NORMAL
import com.onyx.android.sdk.api.device.epd.EpdController.SCHEME_SCRIBBLE
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.api.device.epd.UpdateOption
import com.onyx.android.sdk.device.Device
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.utils.DeviceInfoUtil
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private val log = ShipBook.getLogger("einkHelper")

fun setRecommendedMode() {
    EpdController.setAppScopeRefreshMode(UpdateOption.NORMAL)
    log.d("Changed to NORMAL mode")
}

fun isRecommendedRefreshMode(): Boolean {
    val updateOption: UpdateOption = Device.currentDevice().appScopeRefreshMode
    return updateOption == UpdateOption.NORMAL || updateOption == UpdateOption.REGAL
}

fun getCurRefreshModeString(): String {
    return (Device.currentDevice().appScopeRefreshMode).toString()
}

suspend fun waitForEpdRefresh(updateOption: UpdateOption = Device.currentDevice().appScopeRefreshMode) {
    log.d("Waiting for screen, Update mode: $updateOption")
//        Device.currentDevice().waitForUpdateFinished()
    // depending on device, it may take different amount of time to
    // refresh the screen. So for example, when closing menus, we
    // need to wait before we freeze screen.

    // Onyx library might change
    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    when (updateOption) {
        UpdateOption.NORMAL -> {
            // HD mode
            delay(190.milliseconds) // On my device ~160 is the minimal delay
        }

        UpdateOption.REGAL -> {
            // regal mode
            delay(180.milliseconds) // On my device ~150 is the minimal delay
        }

        UpdateOption.FAST -> {
            //ultra fast, fast, balanced
            delay(20.milliseconds) // 5ms is problematic sometimes on balanced mode.
        }

        UpdateOption.FAST_X -> {
            // no idea what it is
            delay(4.milliseconds) // Minimal delay
        }

        UpdateOption.FAST_QUALITY -> {
            // no idea what it is
            delay(15.milliseconds)
        }

        else -> {
            // Default fallback
            log.e("Unknown refresh mode: $updateOption")
            delay(10.milliseconds)
        }
    }
}

/**
 * Attempts to set the refresh mode for a view using the Onyx EPDController.
 * Catches and logs exceptions to prevent crashes on unsupported devices or update modes.
 * Returns true if successful, false otherwise.
 * This is necessary because the Onyx library is unstable and unreliable.
 */
private fun tryToSetRefreshMode(view: View, mode: UpdateMode): Boolean {
    return try {
        EpdController.setViewDefaultUpdateMode(view, mode)
        log.d("Set update mode $mode")
        true
    } catch (e: NullPointerException) {
        log.d("Device does not support update mode $mode (NullPointerException): ${e.message}")
        false
    } catch (e: IllegalArgumentException) {
        log.d("Device does not support update mode $mode (IllegalArgumentException): ${e.message}")
        false
    } catch (e: Exception) {
        log.e("Unexpected error when setting update mode $mode: ${e.message}", e)
        false
    }
}

fun onSurfaceInit(view: View) {
    log.v("onSurfaceInit, (${view.left}, ${view.top} - ${view.right}, ${view.bottom})")
    if (!tryToSetRefreshMode(view, UpdateMode.HAND_WRITING_REPAINT_MODE))
        tryToSetRefreshMode(view, UpdateMode.REGAL)
    EpdController.enablePost(1)
}

fun onSurfaceChanged(view: View) {
    EpdController.enablePost(view, 1)

}


fun onSurfaceDestroy(view: View, touchHelper: TouchHelper?) {
    if (touchHelper == null) return
    log.v("onSurfaceDestroy, (${view.left}, ${view.top} - ${view.right}, ${view.bottom})")
    touchHelper.setRawDrawingEnabled(false)
}


fun setupSurface(view: View, touchHelper: TouchHelper?, toolbarHeight: Int) {
    if (touchHelper == null) return
    // Takes at least 50ms on Note 4c,
    // and I don't think that we need it immediately
    log.i("Setup editable surface")
    touchHelper.debugLog(false)
    touchHelper.setRawDrawingEnabled(false)
    touchHelper.closeRawDrawing()

    // Store view dimensions locally before using in Rect
    val viewWidth = view.width
    val viewHeight = view.height

    // Determine the exclusion area based on toolbar position
    val excludeRect: Rect =
        if (GlobalAppSettings.current.toolbarPosition == AppSettings.Position.Top) {
            Rect(0, 0, viewWidth, toolbarHeight)
        } else {
            Rect(0, viewHeight - toolbarHeight, viewWidth, viewHeight)
        }

    val limitRect =
        if (GlobalAppSettings.current.toolbarPosition == AppSettings.Position.Top)
            Rect(0, toolbarHeight, viewWidth, viewHeight)
        else
            Rect(0, 0, viewWidth, viewHeight - toolbarHeight)

    touchHelper.setLimitRect(mutableListOf(limitRect)).setExcludeRect(listOf(excludeRect))
        .openRawDrawing()

    touchHelper.setRawDrawingEnabled(true)

    // Enable the firmware's native eraser indicator. MUST be called after setRawDrawingEnabled(true)
    // because that call internally resets it to disabled. Also re-asserted in onBeginRawErasing.
    // See docs/onyx-sdk/onyx-native-eraser-indicator.md.
    enableNativeEraser(touchHelper)
    log.i("Setup editable surface completed")

}

/**
 * Firmware stroke style dedicated to the native eraser track. Not a public [TouchHelper]
 * constant (those stop at 7); the stock Onyx note app (com.onyx.kreader) uses style 8 for the
 * drag-erase indicator. Its width is bound to [Device.setStrokeParameters], independent of the
 * pen's stroke config. This is the ONLY reliable style: with STROKE_STYLE_MARKER the eraser
 * channel falls back to the global setStrokeWidth (thin pen) and the first-erase bug returns.
 */
private const val ERASER_STROKE_STYLE = 8

/**
 * Native eraser indicator thickness. Bound to [ERASER_SWATH_WIDTH] so the indicator is drawn at
 * exactly the diameter [handleErase] deletes — the stock app likewise draws the indicator at the
 * eraser's full width (kreader: eraserWidth*2).
 */
private const val ERASER_STROKE_WIDTH = ERASER_SWATH_WIDTH

/**
 * Style 8's 2nd/3rd SurfaceFlinger shape params (kreader passes {width, 0.5, 0.1}). These are
 * per-style SHAPE params (edge/smooth), NOT colour — CONFIRMED on-device: sweeping param[1] from
 * 0.01..0.8 changes the track only subtly and never its colour. Left at kreader's values. The
 * track colour is set separately via setStrokeColor (see onBeginRawErasing / ERASER_INDICATOR_COLOR).
 */
private const val ERASER_STROKE_PARAM1 = 0.5f
private const val ERASER_STROKE_PARAM2 =
    1f // how much the strokes are filled in with dots, or something, I don't know

/**
 * Colour of the native eraser track. The eraser channel carries no colour of its own; the firmware
 * draws the track using the global setStrokeColor state (SET_STROKE_COLOR). Applied in
 * onBeginRawErasing (mirrors the old working behaviour). Tune here for a lighter/darker grey.
 */
internal val ERASER_INDICATOR_COLOR = android.graphics.Color.BLACK

/**
 * Firmware DASH stroke style ([TouchHelper.STROKE_STYLE_DASH]) used for the LASSO/SELECT eraser
 * track — a dotted outline, matching what the lasso eraser drew before the native-eraser rework.
 * kreader likewise uses style 5 for area/lasso erase (isMoveStrokeErase ? 8 : 5). Params are
 * {thickness, dashLen, gapLen, phase} (same values the old applyEraserIndicatorStyle used).
 */
private const val SELECT_ERASER_STYLE = 5
private val SELECT_ERASER_PARAMS = floatArrayOf(5f, 9f, 9f, 0f)

/**
 * Enables the firmware's native eraser-stroke rendering for pen side-button erasing.
 * MUST be called after every setRawDrawingEnabled(true) (which resets it to disabled).
 * Wrapped in try/catch because the Onyx SDK is unstable across devices/firmware.
 *
 * Configures the eraser style's parameters PROACTIVELY via [Device.setStrokeParameters], the way
 * the stock Onyx note app does (PenEventHandler.x()/y()): the eraser track width is then bound to
 * the eraser STYLE's own parameters, NOT the pen's current setStrokeWidth. This decouples the
 * indicator from the active pen so the side-button eraser renders at the correct thickness on the
 * FIRST erase after drawing with a thin pen — previously the width was set reactively in
 * onBeginRawErasing, too late for the firmware's stroke snapshot, so the first erase inherited the
 * thin pen. Must be the dedicated style 8: with STROKE_STYLE_MARKER the eraser channel ignores
 * these params and falls back to the pen's global width, bringing the bug back.
 * See docs/onyx-sdk/onyx-native-eraser-indicator.md.
 */
fun enableNativeEraser(touchHelper: TouchHelper?, eraser: Eraser = Eraser.PEN) {
    if (touchHelper == null) return
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
//                touchHelper.setEraserRawDrawingEnabled(true, TouchHelper.STROKE_STYLE_MARKER)
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
 * default nib orientation and the stroke visibly rotates on pen-up. Mirrors the stock app's
 * NoteRenderUtils.setSquarePenStrokeParameters: params[1] = width (≤10), params[2] = angle.
 */
fun configureCalligraphyLiveAngle(angleDegrees: Float, strokeWidth: Float) {
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

fun prepareForPartialUpdate(view: View, touchHelper: TouchHelper?) {
    if (touchHelper == null) return
    EpdController.setDisplayScheme(SCHEME_SCRIBBLE)
    EpdController.enableA2ForSpecificView(view)
    EpdController.setEpdTurbo(100)
    touchHelper.isRawDrawingRenderEnabled = false
    touchHelper.isRawDrawingRenderEnabled = true
}

fun refreshScreenRegion(view: View, dirtyRect: Rect) {
    if (!view.isAttachedToWindow) {
        log.e("View is not attached to window")
        logCallStack("refreshScreenRegion")
    }
    EpdController.refreshScreenRegion(
        view,
        dirtyRect.left,
        dirtyRect.top,
        dirtyRect.width(),
        dirtyRect.height(),
        UpdateMode.ANIMATION_MONO
    )
}

fun refreshScreen() {
    EpdController.repaintEveryThing(UpdateMode.REGAL_PLUS)
}

fun restoreDefaults(view: View) {
    EpdController.setDisplayScheme(SCHEME_NORMAL)
}

fun partialRefreshRegionOnce(view: View, dirtyRect: Rect, touchHelper: TouchHelper?) {
    if (touchHelper == null) return
    refreshScreenRegion(view, dirtyRect)
    resetScreenFreeze(touchHelper)
}

// Single coroutine scope + job for the raw-drawing resume so overlapping calls coalesce.
// Without this, a continuous scroll fires resetScreenFreeze on every frame, each arming a
// fresh 500 ms resume timer — the timers stack and "Resuming raw drawing" floods at the end.
private val screenFreezeScope = CoroutineScope(Dispatchers.Default)
private var screenFreezeResetJob: Job? = null

// When raw drawing is being turned off entirely (e.g. entering selection), a pending resume
// must not fire afterwards: its delayed isRawDrawingRenderEnabled=true would hand the screen
// back to the firmware with input disabled — a frozen screen nothing unfreezes.
fun cancelPendingScreenFreezeReset() {
    screenFreezeResetJob?.cancel()
}

fun resetScreenFreeze(touchHelper: TouchHelper?, view: View? = null) {
    if (touchHelper == null) {
        log.w("touchHelper is null")
        return
    }
    // Cancel any pending resume and re-arm. While calls keep arriving (e.g. during a scroll)
    // raw drawing stays disabled; only the last call's delay completes and re-enables it once.
    screenFreezeResetJob?.cancel()
    screenFreezeResetJob = screenFreezeScope.launch {
        touchHelper.isRawDrawingRenderEnabled = false
        DeviceCompat.delayBeforeResumingDrawing()
        touchHelper.isRawDrawingRenderEnabled = true
    }
}


/**
 * Automatically holds e‑ink animation mode while the attached subtree scrolls.
 * Works with any Compose scrollable that supports nested scroll (Lazy* and scrollable()).
 *
 * - Acquires an [EpdRefreshArbiter] handle when any scroll/drag/fling starts.
 * - Releases it [debounceOffMillis] after the end of drag/fling; a scroll
 *   resuming inside that window acquires a fresh handle, so the mode never
 *   drops in between.
 */
fun Modifier.autoEInkAnimationOnScroll(
    debounceOffMillis: Long = 500,
): Modifier = composed {
    var handle: EpdRefreshArbiter.Handle? by remember { mutableStateOf(null) }

    fun turnOn() {
        if (handle == null) handle = EpdRefreshArbiter.acquire("scroll-ui")
    }

    fun scheduleOff() {
        handle?.releaseAfterMillis(debounceOffMillis)
        handle = null
    }

    val connection = remember(debounceOffMillis) {
        object : NestedScrollConnection {
            // Any pre-scroll (user drag) -> ON
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available != Offset.Zero) turnOn()
                return Offset.Zero
            }

            // Any post-scroll (child consumed) -> ON
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (consumed != Offset.Zero) {
                    turnOn()
                } else if (source == NestedScrollSource.UserInput && available == Offset.Zero) {
                    // Likely drag end without fling -> schedule OFF
                    scheduleOff()
                }
                return Offset.Zero
            }

            // Fling start -> ON
            override suspend fun onPreFling(available: Velocity): Velocity {
                turnOn()
                return Velocity.Zero
            }

            // Fling finished -> schedule OFF
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                scheduleOff()
                return Velocity.Zero
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            handle?.release()
            handle = null
        }
    }

    this.nestedScroll(connection)
}

object DeviceCompat {
    /**
     * True when running on an ONYX BOOX device and Onyx SDK classes are present.
     * Checks manufacturer/brand and that a core Onyx class exists at runtime.
     */
    val isOnyxDevice: Boolean by lazy {
        isOnyxManufacturer() && isOnyxSdkAvailable()
    }

    private fun isOnyxManufacturer(): Boolean {
        return "ONYX".equals(Build.MANUFACTURER, ignoreCase = true)
                || "ONYX".equals(Build.BRAND, ignoreCase = true)
    }

    private fun isOnyxSdkAvailable(): Boolean {
        return try {
            Class.forName("com.onyx.android.sdk.pen.TouchHelper")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    fun isColorDevice(): Boolean {
        if (!isOnyxDevice) return true
        return try {
            DeviceInfoUtil.isColorDevice()
        } catch (e: Exception) {
            log.e("Failed to check if device is color: ${e.message}")
            false
        }
    }

    suspend fun delayBeforeResumingDrawing(isErasing: Boolean = false, areaErase: Boolean = false) {
        if (!isOnyxDevice) return
        // Delays mirror kreader's WaitForUpdateFinishedAction:
        //  - erase: 150ms stroke, 500ms area (lasso). Safe at 150ms because commitErase uses the
        //    heavy setRawDrawingEnabled toggle which hands the screen back atomically.
        //  - normal pen: 500ms color, 300ms monochrome.
        // See docs/onyx-sdk/onyx-pen-up-refresh-and-screen-freeze.md.
        val delay = when {
            isErasing -> if (areaErase) 500.milliseconds else 150.milliseconds
            isColorDevice() -> 500.milliseconds
            else -> 300.milliseconds
        }
        log.d("delayBeforeResumingDrawing(isErasing=$isErasing): Delaying raw drawing resume for ${delay}ms")
        delay(delay)
        log.d("delayBeforeResumingDrawing: Resuming raw drawing")
    }
}