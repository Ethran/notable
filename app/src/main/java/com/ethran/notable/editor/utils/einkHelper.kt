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
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.api.device.epd.UpdateOption
import com.onyx.android.sdk.device.Device
import com.onyx.android.sdk.utils.DeviceInfoUtil
import io.shipbook.shipbooksdk.ShipBook

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


/** Where raw drawing works on [view]: everything but the toolbar. Returns (limit, exclude). */
fun rawDrawingArea(view: View, toolbarHeight: Int): Pair<Rect, Rect> {
    val viewWidth = view.width
    val viewHeight = view.height
    return if (GlobalAppSettings.current.toolbarPosition == AppSettings.Position.Top) {
        Rect(0, toolbarHeight, viewWidth, viewHeight) to Rect(0, 0, viewWidth, toolbarHeight)
    } else {
        Rect(0, 0, viewWidth, viewHeight - toolbarHeight) to
                Rect(0, viewHeight - toolbarHeight, viewWidth, viewHeight)
    }
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

    /**
     * How long a post needs to reach the panel before the pen comes back on: 500ms color, 300ms
     * monochrome. With less, the post is lost along with the firmware's ink (measured on a BOOX).
     */
    fun postSettleMillis(): Long = if (isColorDevice()) 500 else 300

    /**
     * How long the pen stays off after an erase commit: 150ms for a stroke erase, 500ms for an
     * area (lasso) erase and for scribble-to-erase (150ms was too short after a large scribble).
     */
    fun eraseSettleMillis(areaErase: Boolean): Long = if (areaErase) 500 else 150
}