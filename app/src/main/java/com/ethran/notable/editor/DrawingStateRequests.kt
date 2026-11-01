package com.ethran.notable.editor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The single way to switch raw drawing on or off. The newest request wins: it
 * cancels a request still in flight, so a `true` waiting out its settle delay
 * cannot land after, and override, a later `false`.
 *
 * Call from the main thread.
 */
class DrawingStateRequests(
    private val scope: CoroutineScope,
    private val waitForSettle: suspend () -> Unit,
    private val publish: suspend (Boolean) -> Unit,
) {
    private var pending: Job? = null

    /** [settle]: wait for the screen to settle before turning drawing on. */
    fun request(isDrawing: Boolean, settle: Boolean = false) {
        pending?.cancel()
        pending = scope.launch {
            if (isDrawing && settle) waitForSettle()
            publish(isDrawing)
        }
    }
}
