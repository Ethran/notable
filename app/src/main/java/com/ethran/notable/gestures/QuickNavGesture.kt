package com.ethran.notable.gestures

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import com.ethran.notable.data.datastore.GlobalAppSettings
import io.shipbook.shipbooksdk.Log
import kotlin.coroutines.cancellation.CancellationException


/**
 * Detects a three-finger touch (simultaneous finger contacts) to open QuickNav.
 *
 */
fun Modifier.quickNavGesture(
    onOpen: () -> Unit
): Modifier = this.pointerInput(GlobalAppSettings.current.enableQuickNav) {
    if(!GlobalAppSettings.current.enableQuickNav) return@pointerInput
    while (true) {
        try {
            awaitPointerEventScope {
                // Wait for a DOWN that was not already consumed by children.
                val firstDown = try {
                    awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Main)
                } catch (_: CancellationException) {
                    return@awaitPointerEventScope
                }

                // Only react to finger input; ignore stylus or other pointer types.
                if (firstDown.type != PointerType.Touch) {
                    // Drain without consuming until all pointers are up; then restart listening.
                    do {
                        val e = awaitPointerEvent(PointerEventPass.Main)
                    } while (e.changes.any { it.pressed })
                    return@awaitPointerEventScope
                }

                var opened = false

                // Track until all pointers lift (single gesture life cycle).
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)

                    // Count currently pressed finger touches
                    val touches =
                        event.changes.filter { it.type == PointerType.Touch && it.pressed }

                    // Recognize three-finger touch once; consume only upon recognition
                    if (!opened && touches.size >= 3) {
                        opened = true
                        touches.take(3).forEach { it.consume() }
                        onOpen()
                    } else if (opened) {
                        // After recognition, keep consuming these touches to avoid bleed-through
                        touches.forEach { it.consume() }
                    }

                    // End when all pointers are up
                    if (event.changes.none { it.pressed }) break
                }
            }
        } catch (_: CancellationException) {
            // Pointer input was cancelled (e.g., recomposition);
            return@pointerInput
        } catch (e: Throwable) {
            Log.e("QuickNavGesture","Router: Error in pointerInput", e)
        }
    }
}