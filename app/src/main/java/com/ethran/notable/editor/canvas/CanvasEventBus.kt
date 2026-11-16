package com.ethran.notable.editor.canvas

import android.net.Uri
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.measureTimeMillis

object CanvasEventBus {
    /** Redraw the whole page into its bitmap and show it. */
    val forceUpdate = MutableSharedFlow<Unit>()
    val refreshUi = MutableSharedFlow<Unit>()
    val refreshUiImmediately = MutableSharedFlow<Unit>(
        replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val reinitSignal = MutableSharedFlow<Unit>()
    val reloadFromDb = MutableSharedFlow<Unit>()


    private val _isResumed = MutableStateFlow(true)

    /** The activity is resumed. Written only by [setResumed], from MainActivity's onResume/onPause. */
    val isResumed: StateFlow<Boolean> = _isResumed.asStateFlow()

    private val _hasWindowFocus = MutableStateFlow(true)

    /**
     * The activity's window has input focus; false while a focusable popup or dialog, or another
     * window, holds it. Written only by [setWindowFocus], from MainActivity.onWindowFocusChanged.
     */
    val hasWindowFocus: StateFlow<Boolean> = _hasWindowFocus.asStateFlow()

    fun setResumed(resumed: Boolean) {
        _isResumed.value = resumed
    }

    fun setWindowFocus(hasFocus: Boolean) {
        _hasWindowFocus.value = hasFocus
    }

    // It might be bad idea, but plan is to insert graphic in this, and then take it from it
    // There is probably better way
    val addImageByUri = MutableStateFlow<Uri?>(null)

    val drawingInProgress = Mutex()

    // For cleaning whole page, activated from toolbar menu
    val clearPageSignal = MutableSharedFlow<Unit>()


    // For QuickNav scrolling with previews
    val isScrubbing = MutableStateFlow<Boolean> (false)
    val previewPage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val restoreCanvas = MutableSharedFlow<Unit>(extraBufferCapacity = 1)


    val changePage = MutableSharedFlow<String>(extraBufferCapacity = 1)


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