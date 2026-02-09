package com.ethran.notable.editor.canvas

import android.graphics.Rect
import android.net.Uri
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.measureTimeMillis

object CanvasEventBus {
    val forceUpdate = MutableSharedFlow<Rect?>() // null for full redraw
    val refreshUi = MutableSharedFlow<Unit>()
    val refreshUiImmediately = MutableSharedFlow<Unit>(
        replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val isDrawing = MutableSharedFlow<Boolean>()
    val restartAfterConfChange = MutableSharedFlow<Unit>()

    // used for managing drawing state on regain focus
    val onFocusChange = MutableSharedFlow<Boolean>()

    // before undo we need to commit changes
    val commitHistorySignal = MutableSharedFlow<Unit>()
    val commitHistorySignalImmediately = MutableSharedFlow<Unit>()

    // used for checking if commit was completed
    var commitCompletion = CompletableDeferred<Unit>()

    // It might be bad idea, but plan is to insert graphic in this, and then take it from it
    // There is probably better way
    val addImageByUri = MutableStateFlow<Uri?>(null)
    val rectangleToSelectByGesture = MutableStateFlow<Rect?>(null)
    val drawingInProgress = Mutex()

    // For cleaning whole page, activated from toolbar menu
    val clearPageSignal = MutableSharedFlow<Unit>()


    // For QuickNav scrolling with previews
    val saveCurrent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
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

    suspend fun waitForDrawingWithSnack() {
        if (drawingInProgress.isLocked) {
            val snack = SnackConf(text = "Waiting for drawing to finish…", duration = 60000)
            SnackState.globalSnackFlow.emit(snack)
            waitForDrawing()
            SnackState.cancelGlobalSnack.emit(snack.id)
        }
    }
}