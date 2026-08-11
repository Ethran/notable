package com.ethran.notable.editor.canvas

import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.measureTimeMillis

/**
 * Signals that are genuinely global.
 *
 * Everything here concerns the device or the app as a whole rather than one editor view: the EPD
 * raw-drawing state is a property of the panel, focus is a property of the window, and the menu
 * state is app-wide. Per-view signals live on [PaneEventBus] — see its documentation for why the
 * split exists.
 *
 * Keep this small. A signal added here is a signal every view will react to.
 */
object CanvasEventBus {

    /**
     * Guards stroke commits. Global because raw drawing is global: the Onyx firmware has one
     * raw-draw state for the whole panel, so two views cannot be mid-commit independently.
     */
    val drawingInProgress = Mutex()

    val isDrawing = MutableSharedFlow<Boolean>()

    // used for managing drawing state on regain focus
    val onFocusChange = MutableSharedFlow<Boolean>()

    /**
     * Signal to UI to close any open menus/modals, observed in EditorView. App-wide: menus are not
     * owned by a view.
     */
    val closeMenusSignal = MutableSharedFlow<Unit>()

    /**
     * Immediate, unconditional repaint of the whole surface. Global because it repaints the
     * surface itself rather than a view's content — it is used on surface creation and when the
     * EPD needs to be brought back into a known state.
     */
    val refreshUiImmediately = MutableSharedFlow<Unit>(
        replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    suspend fun waitForDrawing() {
        Log.d(
            "DrawCanvas.waitForDrawing", "waiting"
        )
        val elapsed = measureTimeMillis {
            withTimeoutOrNull(3000) {
                // Just to make sure wait 1ms before checking lock.
                delay(1)
                // Wait until drawingInProgress is unlocked before proceeding
                while (drawingInProgress.isLocked) {
                    delay(5)
                }
            } ?: Log.e(
                "DrawCanvas.waitForDrawing",
                "Timeout while waiting for drawing lock. Potential deadlock."
            )

        }
        when {
            elapsed > 3000 -> Log.e(
                "DrawCanvas.waitForDrawing", "Exceeded timeout ($elapsed ms)"
            )

            elapsed > 100 -> Log.w("DrawCanvas.waitForDrawing", "Took too long: $elapsed ms")
            else -> Log.d("DrawCanvas.waitForDrawing", "Finished waiting in $elapsed ms")
        }

    }

}
