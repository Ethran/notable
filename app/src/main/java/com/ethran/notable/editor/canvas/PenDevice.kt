package com.ethran.notable.editor.canvas

import android.graphics.Rect
import com.onyx.android.sdk.pen.TouchHelper
import io.shipbook.shipbooksdk.ShipBook

private val log = ShipBook.getLogger("RawDrawing")

/**
 * The raw-drawing calls of one Onyx `TouchHelper`, as the SDK (onyxsdk-pen 1.5.8) implements them:
 * - [setRenderEnabled] and [setInputEnabled] do nothing unless a session is open and their own
 *   flag differs from the value;
 * - [setDrawingEnabled] is render, then input, then a reset of the pen defaults (eraser channel,
 *   stroke style), which runs every time;
 * - [openSession] clears both flags; after [closeSession] every setter is a no-op.
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

    /** Opens a session over the current drawing area; render and input start off. */
    fun openSession()
    fun closeSession()
}

/**
 * [PenDevice] over a real [TouchHelper]: the only place that calls the SDK's raw-drawing switches.
 * Logs every call, so the log shows exactly what the firmware was told and in which order.
 *
 * @param area the drawing area for a new session: (limit, exclude).
 */
class TouchHelperPenDevice(
    private val helper: TouchHelper,
    private val area: () -> Pair<Rect, Rect>,
) : PenDevice {
    override fun setDrawingEnabled(enabled: Boolean) {
        log.d("setRawDrawingEnabled($enabled)")
        helper.setRawDrawingEnabled(enabled)
    }

    override fun setRenderEnabled(enabled: Boolean) {
        log.v("setRawDrawingRenderEnabled($enabled)")
        helper.isRawDrawingRenderEnabled = enabled
    }

    override fun setInputEnabled(enabled: Boolean) {
        log.d("setRawInputReaderEnable($enabled)")
        helper.setRawInputReaderEnable(enabled)
    }

    override fun openSession() {
        val (limit, exclude) = area()
        log.i("openRawDrawing(limit=$limit, exclude=$exclude)")
        helper.debugLog(false)
        helper.setLimitRect(mutableListOf(limit)).setExcludeRect(listOf(exclude)).openRawDrawing()
    }

    override fun closeSession() {
        log.i("closeRawDrawing")
        helper.closeRawDrawing()
    }
}
