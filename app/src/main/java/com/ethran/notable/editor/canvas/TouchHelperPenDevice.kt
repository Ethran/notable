package com.ethran.notable.editor.canvas

import android.graphics.Rect
import android.os.Looper
import com.ethran.notable.BuildConfig
import com.onyx.android.sdk.pen.TouchHelper
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val log = ShipBook.getLogger("RawDrawing")

/**
 * [PenDevice] over a real [TouchHelper]. Logs every call, so the log shows exactly what the
 * firmware was told and in which order.
 *
 * @param area the drawing area for a new session: (limit, exclude).
 */
class TouchHelperPenDevice(
    private val helper: TouchHelper,
    private val area: () -> Pair<Rect, Rect>,
) : PenDevice {
    override fun setRawDrawingEnabled(enabled: Boolean) {
        log.d("setRawDrawingEnabled($enabled)")
        helper.setRawDrawingEnabled(enabled)
    }

    override fun setRenderEnabled(enabled: Boolean) {
        log.v("setRenderEnabled($enabled)")
        helper.isRawDrawingRenderEnabled = enabled
    }

    override fun setInputEnabled(enabled: Boolean) {
        log.d("setInputEnabled($enabled)")
        helper.setRawInputReaderEnable(enabled)
    }

    override fun openSession() {
        val (limit, exclude) = area()
        log.i("openSession(limit=$limit, exclude=$exclude)")
        helper.debugLog(false)
        helper.setLimitRect(mutableListOf(limit)).setExcludeRect(listOf(exclude)).openRawDrawing()
    }

    override fun closeSession() {
        log.i("closeSession")
        helper.closeRawDrawing()
    }
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
    if (BuildConfig.DEBUG) error(message) else log.e(message)
}
