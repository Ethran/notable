package com.ethran.notable.gestures

import android.graphics.Rect
import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.ui.SelectionVisualCues
import com.ethran.notable.editor.utils.EpdRefreshArbiter

import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

private val log = ShipBook.getLogger("GestureReceiver")

/**
 * Everything the gesture pipeline needs besides the per-gesture state.
 * Built once per pointerInput block; a new one is created whenever
 * settings change (the pointerInput key).
 */
private class GestureContext(
    val actions: GestureActions,
    val appSettings: AppSettings,
    val scope: CoroutineScope,
    val view: View,
    val updateSelectionCues: (IntOffset?, Rect?) -> Unit,
) {
    // Held while a gesture mode with fast (animation) refresh is active.
    // Lives on the context, not the per-gesture state: a rapid follow-up
    // gesture re-acquires before the previous release settles, so the
    // refresh mode never drops between flicks.
    var refreshHandle: EpdRefreshArbiter.Handle? = null

    // Guards against events arriving while the composition is being torn
    // down (CompletionHandlerException in resume onCancellation) or after
    // the window lost focus.
    fun shouldAbort(): Boolean = !scope.isActive || !view.hasWindowFocus()
}

private enum class TrackResult { Completed, Aborted, ConsumedByOther }


@Composable
fun EditorGestureReceiver(
    actions: GestureActions,
) {
    val coroutineScope = rememberCoroutineScope()
    val appSettings = GlobalAppSettings.current
    var crossPosition by remember { mutableStateOf<IntOffset?>(null) }
    var rectangleBounds by remember { mutableStateOf<Rect?>(null) }
    val view = LocalView.current
    Box(
        modifier = Modifier
            .pointerInput(appSettings) {
                val ctx = GestureContext(
                    actions = actions,
                    appSettings = appSettings,
                    scope = coroutineScope,
                    view = view,
                    updateSelectionCues = { cross, rect ->
                        crossPosition = cross
                        rectangleBounds = rect
                    },
                )
                awaitEachGesture {
                    try {
                        // Detect initial touch
                        val down = awaitFirstDown()

                        // We should not get any stylus events
                        if (down.type == PointerType.Stylus || down.type == PointerType.Eraser) {
                            return@awaitEachGesture // Escapes the current gesture loop, waits for the next one
                        }
                        if (ctx.shouldAbort()) return@awaitEachGesture

                        // Ignore non-touch input
                        if (down.type != PointerType.Touch) {
                            log.i("Ignoring non-touch input")
                            return@awaitEachGesture
                        }

                        val gestureState = GestureState()
                        gestureState.update(down)

                        when (trackGesture(gestureState, ctx)) {
                            TrackResult.Aborted,
                            TrackResult.ConsumedByOther -> return@awaitEachGesture

                            TrackResult.Completed -> {}
                        }

                        if (finishModalGesture(gestureState, ctx)) return@awaitEachGesture
                        if (ctx.shouldAbort()) return@awaitEachGesture

                        classifyTapOrSwipe(gestureState, ctx)
                    } catch (e: CancellationException) {
                        // Cancellation is normal control flow (composition
                        // disposal, pointerInput restart) — propagate it.
                        throw e
                    } catch (e: Exception) {
                        log.e("Unexpected error in gesture handling", e)
                    }
                }
            }
            .fillMaxWidth()
            .fillMaxHeight()
    )
    SelectionVisualCues(crossPosition, rectangleBounds)
}

/**
 * The event pump: consumes touch events into [gestureState], applies mode
 * transitions and streams Scroll/Zoom/Drag deltas until all fingers are up.
 */
private suspend fun AwaitPointerEventScope.trackGesture(
    gestureState: GestureState,
    ctx: GestureContext,
): TrackResult {
    while (true) {
        // wait for the next event; on timeout re-evaluate hold detection
        val event =
            withTimeoutOrNull(HOLD_THRESHOLD_MS.toLong()) { awaitPointerEvent() }
        if (ctx.shouldAbort()) return TrackResult.Aborted

        if (event != null) {
            val fingerChange =
                event.changes.filter { it.type == PointerType.Touch }

            // is already consumed return
            if (fingerChange.find { it.isConsumed } != null) {
                log.i("Canceling gesture - already consumed")
                if (gestureState.gestureMode == GestureMode.Selection) {
                    ctx.updateSelectionCues(null, null)
                    applyGestureMode(gestureState, GestureMode.Normal, ctx)
                    ctx.actions.setIsDrawing(true)
                }
                return TrackResult.ConsumedByOther
            }
            fingerChange.forEach { change ->
                // Consume changes and update positions
                change.consume()
                gestureState.update(change)
            }
            // The gesture lives until all fingers are up. E-ink panels
            // routinely drop one contact of a multi-finger gesture for a
            // few ms, so multi-finger gestures get a grace window in which
            // a re-landing finger resumes the same gesture.
            if (fingerChange.isNotEmpty() && gestureState.pressedCount() == 0) {
                if (gestureState.getInputCount() < 2) return TrackResult.Completed
                val relandEvent = withTimeoutOrNull(TOUCH_RELAND_GRACE_MS) {
                    var e = awaitPointerEvent()
                    while (e.changes.none { it.type == PointerType.Touch && it.pressed }) {
                        e = awaitPointerEvent()
                    }
                    e
                } ?: return TrackResult.Completed
                relandEvent.changes
                    .filter { it.type == PointerType.Touch }
                    .forEach { change ->
                        change.consume()
                        gestureState.update(change)
                    }
            }
        }
        // events are only sent on change; hold detection re-evaluates against
        // "now" inside the state's hold predicates
        applyModeTransitions(gestureState, ctx)
        streamActiveMode(gestureState, ctx)
    }
}

/** Detects mode entry (Selection/Scroll/Zoom/Drag) and applies it. */
private fun applyModeTransitions(gestureState: GestureState, ctx: GestureContext) {
    if (gestureState.gestureMode == GestureMode.Selection) {
        ctx.updateSelectionCues(
            gestureState.getLastPositionIO(),
            gestureState.calculateRectangleBounds()
        )
        return
    }
    if (gestureState.isHoldingOneFinger()) {
        applyGestureMode(gestureState, GestureMode.Selection, ctx)
        ctx.actions.setIsDrawing(false) // unfreeze the screen
        ctx.updateSelectionCues(
            gestureState.getLastPositionIO(),
            gestureState.calculateRectangleBounds()
        )
        ctx.actions.showHint("Selection mode!")
    }
    if (ctx.appSettings.smoothScroll && gestureState.shouldEnterScroll())
        applyGestureMode(gestureState, GestureMode.Scroll, ctx)
    if (ctx.appSettings.continuousZoom && gestureState.shouldEnterZoom())
        applyGestureMode(gestureState, GestureMode.Zoom, ctx)
    if (gestureState.shouldEnterDrag()) {
        applyGestureMode(gestureState, GestureMode.Drag, ctx)
        ctx.actions.showHint("Drag mode!")
    }
}

/** Streams incremental deltas of the active continuous mode. */
private fun streamActiveMode(gestureState: GestureState, ctx: GestureContext) {
    when (gestureState.gestureMode) {
        GestureMode.Scroll -> {
            val delta = gestureState.consumeDragDelta()
            ctx.actions.requestScroll(Offset(0f, delta.y))
        }

        GestureMode.Zoom -> {
            val delta = gestureState.consumePinchDelta()
            ctx.actions.onPinchToZoom(delta, gestureState.getPinchCenter())
        }

        GestureMode.Drag -> {
            val delta = gestureState.consumeDragDelta()
            ctx.actions.requestScroll(delta)
        }

        GestureMode.Selection, GestureMode.Normal -> {}
    }
}

/**
 * Resolves a gesture that ended in a modal mode (Selection/Scroll/Zoom/Drag)
 * and returns the screen to normal. Returns false for Normal, meaning the
 * gesture still needs tap/swipe classification.
 */
private fun finishModalGesture(gestureState: GestureState, ctx: GestureContext): Boolean {
    when (gestureState.gestureMode) {
        GestureMode.Selection -> {
            resolveGesture(
                action = ctx.appSettings.holdAction,
                ctx = ctx,
                rectangle = gestureState.calculateRectangleBounds() ?: Rect(),
            )
            ctx.updateSelectionCues(null, null)
            applyGestureMode(gestureState, GestureMode.Normal, ctx)
            ctx.actions.setIsDrawing(true)
        }

        GestureMode.Scroll -> {
            // return screen updates to normal.
            applyGestureMode(gestureState, GestureMode.Normal, ctx)
        }

        GestureMode.Zoom, GestureMode.Drag -> {
            log.d("Zoom or drag -- final redraw")
            // we need to redraw if we zoomed in only -- for now we will just always redraw after exiting gesture.
            ctx.actions.redrawCanvas()
            // return screen updates to normal.
            applyGestureMode(gestureState, GestureMode.Normal, ctx)
        }

        GestureMode.Normal -> return false
    }
    return true
}

/** End-of-gesture classification: taps, double-tap, swipes, discrete zoom/scroll. */
private suspend fun AwaitPointerEventScope.classifyTapOrSwipe(
    gestureState: GestureState,
    ctx: GestureContext,
) {
    if (gestureState.isOneFinger()) {
        if (gestureState.isOneFingerTap() && awaitDoubleTap(gestureState, ctx))
            return
    } else if (gestureState.isTwoFingers()) {
        log.v("Two finger tap")
        if (gestureState.isTwoFingersTap()) {
            resolveGesture(ctx.appSettings.twoFingerTapAction, ctx)
        }
        // zoom gesture
        val zoomDelta = gestureState.getPinchDrag()
        if (!ctx.appSettings.continuousZoom && abs(zoomDelta) > PINCH_ZOOM_THRESHOLD) {
            ctx.actions.onPinchToZoom(zoomDelta, Offset(0f, 0f))
            log.d("Discrete zoom: $zoomDelta")
        }
    }

    val horizontalDrag = gestureState.getHorizontalDrag()
    val verticalDrag = gestureState.getVerticalDrag()

    log.v("horizontalDrag $horizontalDrag, verticalDrag $verticalDrag")

    if (gestureState.gestureMode == GestureMode.Normal) {
        val oneFinger = gestureState.getInputCount() == 1
        if (horizontalDrag < -SWIPE_THRESHOLD)
            resolveGesture(
                if (oneFinger) ctx.appSettings.swipeLeftAction
                else ctx.appSettings.twoFingerSwipeLeftAction,
                ctx
            )
        else if (horizontalDrag > SWIPE_THRESHOLD)
            resolveGesture(
                if (oneFinger) ctx.appSettings.swipeRightAction
                else ctx.appSettings.twoFingerSwipeRightAction,
                ctx
            )
    }
    if (!ctx.appSettings.smoothScroll && gestureState.isOneFinger()
        && abs(verticalDrag) > SWIPE_THRESHOLD
    ) {
        log.d("Discrete scrolling, verticalDrag: $verticalDrag")
        ctx.actions.requestScroll(Offset(0f, verticalDrag))
    }
}

/**
 * Waits [DOUBLE_TAP_TIMEOUT_MS] for a second tap after a one-finger tap.
 * Returns true if the double-tap action fired.
 */
private suspend fun AwaitPointerEventScope.awaitDoubleTap(
    gestureState: GestureState,
    ctx: GestureContext,
): Boolean {
    return withTimeoutOrNull(DOUBLE_TAP_TIMEOUT_MS) {
        val secondDown = awaitFirstDown()
        val deltaTime = secondDown.uptimeMillis - gestureState.lastInputTimestamp
        log.v("Second down detected: ${secondDown.type}, position: ${secondDown.position}, deltaTime: $deltaTime")
        if (deltaTime < DOUBLE_TAP_MIN_MS) {
            ctx.actions.showHint("Too quick for double click! time between: $deltaTime")
            return@withTimeoutOrNull null
        } else {
            log.v("double click!")
        }
        if (secondDown.type != PointerType.Touch) {
            log.i("Ignoring non-touch input during double-tap detection")
            return@withTimeoutOrNull null
        }
        resolveGesture(ctx.appSettings.doubleTapAction, ctx)
    } != null
}


/**
 * The single place gesture-mode transitions are applied, because they carry
 * EPD refresh-mode side effects: active modes draw with fast animation
 * refresh, returning to Normal restores full-quality refresh.
 */
private fun applyGestureMode(
    gestureState: GestureState,
    new: GestureMode,
    ctx: GestureContext,
) {
    val previous = gestureState.gestureMode
    if (previous == new) return
    log.d("Entered ${new.name} gesture mode")
    when (new) {
        GestureMode.Zoom, GestureMode.Scroll, GestureMode.Selection, GestureMode.Drag -> {
            if (ctx.refreshHandle == null)
                ctx.refreshHandle = EpdRefreshArbiter.acquire("gesture")
        }

        GestureMode.Normal -> {
            // If the gesture produced a selection, the selection flow has
            // already acquired its own handle (EditorControlTower
            // .selectRectangle), so releasing ours keeps the mode on until
            // the selection is dismissed. The settle window lets a follow-up
            // flick re-acquire before quality refresh returns.
            ctx.refreshHandle?.releaseAfterMillis(GESTURE_REFRESH_SETTLE_MS)
            ctx.refreshHandle = null
        }
    }
    gestureState.gestureMode = new
}

private fun resolveGesture(
    action: AppSettings.GestureAction,
    ctx: GestureContext,
    rectangle: Rect = Rect(),
) {
    when (action) {
        AppSettings.GestureAction.None -> log.i("No Action")
        AppSettings.GestureAction.PreviousPage -> ctx.actions.goToPreviousPage()

        AppSettings.GestureAction.NextPage -> ctx.actions.goToNextPage()

        AppSettings.GestureAction.ChangeTool -> ctx.actions.toggleTool()

        AppSettings.GestureAction.ToggleZen -> ctx.actions.toggleZen()

        AppSettings.GestureAction.Undo -> ctx.actions.undo()

        AppSettings.GestureAction.Redo -> ctx.actions.redo()

        AppSettings.GestureAction.Select -> {
            log.i("select")
            ctx.actions.selectRectangle(rectangle)
        }
    }
}
