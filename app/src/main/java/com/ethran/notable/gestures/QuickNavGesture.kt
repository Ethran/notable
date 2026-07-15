package com.ethran.notable.gestures

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import com.ethran.notable.data.datastore.GlobalAppSettings
import io.shipbook.shipbooksdk.ShipBook
import kotlin.coroutines.cancellation.CancellationException

private val log = ShipBook.getLogger("QuickNavGesture")

/**
 * App-level recognizer for the three-finger swipe-up that opens QuickNav.
 *
 * Sits above the whole NavHost so QuickNav is reachable from any screen.
 * Recognition happens when the fingers lift ([isQuickNavSwipe]), so three
 * fingers merely landing (a palm contact, or the start of a horizontal
 * three-finger swipe) do not open it. Deconfliction with the editor's
 * gestures is by classification, not consumption: the editor only acts on
 * *horizontal* three-finger swipes.
 */
fun Modifier.quickNavGesture(
    onOpen: () -> Unit
): Modifier = this.pointerInput(GlobalAppSettings.current.enableQuickNav) {
    if (!GlobalAppSettings.current.enableQuickNav) return@pointerInput
    val thresholds = GestureThresholds(density = this)
    awaitEachGesture {
        try {
            // Wait for a DOWN that was not already consumed by children.
            val firstDown =
                awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Main)

            // Only react to finger input; awaitEachGesture waits for all
            // pointers to lift before the next gesture, so just bail.
            if (firstDown.type != PointerType.Touch) return@awaitEachGesture

            val tracker = PointerTracker(now = { SystemClock.uptimeMillis() })
            tracker.update(firstDown)

            // Track without consuming until all fingers are up, then decide.
            while (tracker.pressedCount() > 0) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                event.changes
                    .filter { it.type == PointerType.Touch }
                    .forEach { tracker.update(it) }
            }
            if (isQuickNavSwipe(tracker, thresholds)) onOpen()
        } catch (e: CancellationException) {
            // Cancellation (composition disposal, pointerInput restart) is
            // normal control flow — propagate it.
            throw e
        } catch (e: Exception) {
            log.e("Unexpected error in QuickNav gesture", e)
        }
    }
}