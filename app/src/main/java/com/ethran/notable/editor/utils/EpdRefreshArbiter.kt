package com.ethran.notable.editor.utils


import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

private val log = ShipBook.getLogger("EpdRefreshArbiter")

/**
 * Sole owner of the transient EPD animation refresh mode.
 *
 * Animation mode is shared hardware state wanted by several independent
 * interactions (gesture scrolling, an active selection, page reorder, UI
 * scrolls). Instead of each of them toggling the mode directly — where the
 * last writer wins and a delayed "off" from one module can land in the
 * middle of another's gesture — every interested party [acquire]s a
 * [Handle] for the duration of its interaction and releases it when done.
 * Fast refresh stays on as long as at least one handle is live.
 *
 * A handle counts as live until its release *settles*: the delayed release
 * variants keep the mode held for the settle period, so a new acquire during
 * that window simply overlaps and no off/on flicker occurs.
 */
object EpdRefreshArbiter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var liveHandles = 0

    class Handle internal constructor(private val owner: String) {
        private val released = AtomicBoolean(false)

        /** Releases immediately. Safe to call more than once. */
        fun release() {
            if (released.getAndSet(true)) return
            decrement(owner)
        }

        /** Keeps animation mode held for [millis], then releases. */
        fun releaseAfterMillis(millis: Long) {
            if (released.getAndSet(true)) return
            scope.launch {
                delay(millis.milliseconds)
                decrement(owner)
            }
        }
    }

    /**
     * Enables animation mode (if it is not already on) and returns the
     * handle that keeps it on. Every acquire must be paired with exactly
     * one release on all exit paths, or the screen stays in fast/ghosting
     * refresh indefinitely.
     */
    fun acquire(owner: String): Handle {
        synchronized(this) {
            liveHandles++
            log.d("acquire($owner): $liveHandles live")
            if (liveHandles == 1) setAnimationMode(true)
        }
        return Handle(owner)
    }

    private fun decrement(owner: String) {
        synchronized(this) {
            liveHandles--
            log.d("release($owner): $liveHandles live")
            if (liveHandles == 0) setAnimationMode(false)
        }
    }

    // reference:
    // https://github.com/onyx-intl/OnyxAndroidDemo/blob/d3a1ffd3af231fe4de60a2a0da692c17cb35ce31/app/OnyxPenDemo/src/main/java/com/onyx/android/eink/pen/demo/ui/PenDemoActivity.java#L500
    private fun setAnimationMode(isAnimationMode: Boolean) {
        if (isAnimationMode) {
            EpdController.applyTransientUpdate(UpdateMode.ANIMATION_X)
            log.d("Animation mode enabled")
        } else {
            EpdController.clearTransientUpdate(true)
            log.d("Animation mode disabled")
        }
    }
}
