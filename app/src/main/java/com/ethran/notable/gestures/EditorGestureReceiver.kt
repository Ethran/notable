package com.ethran.notable.gestures

import android.graphics.Rect
import android.os.SystemClock
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
import androidx.compose.ui.unit.IntRect
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.ui.SelectionVisualCues
import com.ethran.notable.editor.utils.EpdRefreshArbiter

import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlin.coroutines.cancellation.CancellationException

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
    val thresholds: GestureThresholds,
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

/**
 * Per-gesture recognizer state: the pointer geometry plus the recognizer
 * phase. [mode] carries EPD refresh-mode side effects on transition and is
 * only ever assigned through `applyGestureMode` (both are private to this
 * file, so the compiler enforces it).
 */
private class Recognizer {
    val tracker = PointerTracker(now = { SystemClock.uptimeMillis() })
    var mode: GestureMode = GestureMode.Normal
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
                    thresholds = GestureThresholds(density = this),
                    updateSelectionCues = { cross, rect ->
                        crossPosition = cross
                        rectangleBounds = rect
                    },
                )
                awaitEachGesture {
                    var recognizer: Recognizer? = null
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

                        recognizer = Recognizer()
                        recognizer.tracker.update(down)

                        when (trackGesture(recognizer, ctx)) {
                            TrackResult.Aborted,
                            TrackResult.ConsumedByOther -> return@awaitEachGesture

                            TrackResult.Completed -> {}
                        }

                        if (finishModalGesture(recognizer, ctx)) return@awaitEachGesture
                        if (ctx.shouldAbort()) return@awaitEachGesture

                        handleGestureEnd(recognizer, ctx)
                    } catch (e: CancellationException) {
                        // Cancellation is normal control flow (composition
                        // disposal, pointerInput restart) — propagate it.
                        throw e
                    } catch (e: Exception) {
                        log.e("Unexpected error in gesture handling", e)
                    } finally {
                        // Every exit path — completion, abort, consumed-by-
                        // other, unexpected exception, cancellation — funnels
                        // through here, so the refresh handle can never
                        // outlive the gesture (the arbiter's contract) and
                        // Selection UI state can't get stuck. No-op when the
                        // gesture already finished normally.
                        recognizer?.let { cleanupGesture(it, ctx) }
                    }
                }
            }
            .fillMaxWidth()
            .fillMaxHeight()
    )
    SelectionVisualCues(crossPosition, rectangleBounds)
}

/**
 * The event pump: consumes touch events into the recognizer's tracker,
 * applies mode transitions and streams Scroll/Transform deltas until all
 * fingers are up.
 */
private suspend fun AwaitPointerEventScope.trackGesture(
    recognizer: Recognizer,
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

            // Another recognizer already claimed these events — bail.
            if (fingerChange.find { it.isConsumed } != null) {
                log.i("Canceling gesture - already consumed")
                return TrackResult.ConsumedByOther
            }
            fingerChange.forEach { change ->
                // Consume changes and update positions
                change.consume()
                recognizer.tracker.update(change)
            }
            // The gesture lives until all fingers are up. E-ink panels
            // routinely drop one contact of a multi-finger gesture for a
            // few ms, so multi-finger gestures get a grace window in which
            // a re-landing finger resumes the same gesture.
            if (fingerChange.isNotEmpty() && recognizer.tracker.pressedCount() == 0) {
                if (recognizer.tracker.maxConcurrentPressed < 2) return TrackResult.Completed
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
                        recognizer.tracker.update(change)
                    }
            }
        }
        // events are only sent on change; hold detection re-evaluates against
        // "now" inside the hold predicates
        applyModeTransitions(recognizer, ctx)
        streamActiveMode(recognizer, ctx)
    }
}

/** Detects mode entry (Selection/Scroll/Transform) and applies it. */
private fun applyModeTransitions(recognizer: Recognizer, ctx: GestureContext) {
    val tracker = recognizer.tracker
    if (recognizer.mode == GestureMode.Selection) {
        ctx.updateSelectionCues(
            tracker.lastPosition()?.toIntOffset(),
            tracker.dragRect()?.toAndroidRect()
        )
        return
    }
    if (isHoldingOneFinger(tracker, ctx.thresholds)) {
        applyGestureMode(recognizer, GestureMode.Selection, ctx)
        ctx.actions.setIsDrawing(false) // unfreeze the screen
        ctx.updateSelectionCues(
            tracker.lastPosition()?.toIntOffset(),
            tracker.dragRect()?.toAndroidRect()
        )
        ctx.actions.showHint("Selection mode!")
    }
    if (ctx.appSettings.smoothScroll && shouldEnterScroll(tracker, recognizer.mode, ctx.thresholds))
        applyGestureMode(recognizer, GestureMode.Scroll, ctx)
    if (shouldEnterTransform(
            tracker,
            recognizer.mode,
            ctx.thresholds,
            ctx.appSettings.continuousZoom
        )
    )
        applyGestureMode(recognizer, GestureMode.Transform, ctx)
}

/** Streams incremental deltas of the active continuous mode. */
private fun streamActiveMode(recognizer: Recognizer, ctx: GestureContext) {
    when (recognizer.mode) {
        GestureMode.Scroll -> {
            val delta = recognizer.tracker.consumeDragDelta()
            ctx.actions.requestScroll(Offset(0f, delta.y))
        }

        GestureMode.Transform -> {
            // Zoom and pan together: scale about the pinch center, then
            // translate by the centroid delta (the same point for two
            // fingers), so the content stays under the fingers.
            if (ctx.appSettings.continuousZoom) {
                val zoom = recognizer.tracker.consumePinchDelta()
                if (zoom != 0f)
                    ctx.actions.onPinchToZoom(zoom, recognizer.tracker.pinchCenter())
            }
            val pan = recognizer.tracker.consumeDragDelta()
            if (pan != Offset.Zero) ctx.actions.requestScroll(pan)
        }

        GestureMode.Selection, GestureMode.Normal -> {}
    }
}

/**
 * Resolves a gesture that ended in a modal mode (Selection/Scroll/Transform)
 * and returns the screen to normal. Returns false for Normal, meaning the
 * gesture still needs tap/swipe classification.
 */
private fun finishModalGesture(recognizer: Recognizer, ctx: GestureContext): Boolean {
    when (recognizer.mode) {
        GestureMode.Selection -> {
            dispatchEvent(
                GestureEvent.HoldSelect(recognizer.tracker.dragRect() ?: IntRect.Zero),
                ctx
            )
            ctx.updateSelectionCues(null, null)
            applyGestureMode(recognizer, GestureMode.Normal, ctx)
            ctx.actions.setIsDrawing(true)
        }

        GestureMode.Scroll -> {
            // return screen updates to normal.
            applyGestureMode(recognizer, GestureMode.Normal, ctx)
        }

        GestureMode.Transform -> {
            log.d("Transform (pan/zoom) ended -- final redraw")
            // A zoom leaves the snapshot upscaled; redraw once at the settled
            // transform. (A pan-only transform redraws harmlessly.)
            ctx.actions.redrawCanvas()
            // return screen updates to normal.
            applyGestureMode(recognizer, GestureMode.Normal, ctx)
        }

        GestureMode.Normal -> return false
    }
    return true
}

/**
 * End-of-gesture handling: classify what the fingers did into
 * [GestureEvent]s, then act on each. The one-finger tap is special-cased
 * because double-tap recognition needs to await a second down — pump
 * territory, not classification.
 */
private suspend fun AwaitPointerEventScope.handleGestureEnd(
    recognizer: Recognizer,
    ctx: GestureContext,
) {
    val events = classifyGesture(
        tracker = recognizer.tracker,
        mode = recognizer.mode,
        flags = GestureFlags(
            smoothScroll = ctx.appSettings.smoothScroll,
            continuousZoom = ctx.appSettings.continuousZoom,
        ),
        thresholds = ctx.thresholds,
    )
    if (events.isNotEmpty()) log.v("Gesture events: $events")

    for (event in events) {
        if (event is GestureEvent.Tap && event.fingers == 1) {
            if (awaitDoubleTap(recognizer.tracker, ctx)) {
                dispatchEvent(GestureEvent.DoubleTap, ctx)
                return
            }
        } else {
            dispatchEvent(event, ctx)
        }
    }
}

/**
 * Acts on one recognized gesture: maps it to the configured
 * [AppSettings.GestureAction] (via [resolveGesture]) or to the direct
 * [GestureActions] call for the non-remappable ones.
 */
private fun dispatchEvent(event: GestureEvent, ctx: GestureContext) {
    when (event) {
        is GestureEvent.Tap -> when (event.fingers) {
            // A lone one-finger tap has no mapped action (it only seeds
            // double-tap detection); 3+ fingers are reserved for QuickNav.
            2 -> resolveGesture(ctx.appSettings.twoFingerTapAction, ctx)
            else -> {}
        }

        GestureEvent.DoubleTap -> resolveGesture(ctx.appSettings.doubleTapAction, ctx)

        is GestureEvent.Swipe -> {
            val action = when (event.fingers) {
                1 -> when (event.direction) {
                    GestureEvent.Direction.Left -> ctx.appSettings.swipeLeftAction
                    GestureEvent.Direction.Right -> ctx.appSettings.swipeRightAction
                }

                // Two fingers are pan/zoom (Transform), so the multi-finger
                // swipe actions live on three fingers. Two still lands here
                // on rare churn edge cases (a finger lifting mid-gesture),
                // where firing the same action is the sensible outcome.
                else -> when (event.direction) {
                    GestureEvent.Direction.Left -> ctx.appSettings.twoFingerSwipeLeftAction
                    GestureEvent.Direction.Right -> ctx.appSettings.twoFingerSwipeRightAction
                }
            }
            resolveGesture(action, ctx)
        }

        is GestureEvent.HoldSelect ->
            resolveGesture(ctx.appSettings.holdAction, ctx, event.rect.toAndroidRect())

        is GestureEvent.PinchZoom -> {
            log.d("Discrete zoom: ${event.delta}")
            // Discrete zoom snaps to fixed levels (fit-width / 100%), which are
            // focal-point independent, so no pinch center is passed.
            ctx.actions.onPinchToZoom(event.delta, null)
        }

        is GestureEvent.VerticalScroll -> {
            log.d("Discrete scrolling, verticalDrag: ${event.delta}")
            ctx.actions.requestScroll(Offset(0f, event.delta))
        }
    }

}

/**
 * Waits [DOUBLE_TAP_TIMEOUT_MS] for a second tap after a one-finger tap.
 * Returns true if a double-tap was recognized.
 */
private suspend fun AwaitPointerEventScope.awaitDoubleTap(
    tracker: PointerTracker,
    ctx: GestureContext,
): Boolean {
    return withTimeoutOrNull(DOUBLE_TAP_TIMEOUT_MS) {
        val secondDown = awaitFirstDown()
        val deltaTime = secondDown.uptimeMillis - tracker.lastInputTimestamp
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
    } != null
}


/**
 * Last-resort cleanup, run in the gesture's `finally`. Returning the mode to
 * Normal releases the refresh handle (with the settle delay); a gesture
 * abandoned in Selection mode additionally needs its visual cues removed and
 * drawing re-enabled.
 */
private fun cleanupGesture(recognizer: Recognizer, ctx: GestureContext) {
    if (recognizer.mode == GestureMode.Selection) {
        ctx.updateSelectionCues(null, null)
        ctx.actions.setIsDrawing(true)
    }
    applyGestureMode(recognizer, GestureMode.Normal, ctx)
}

/**
 * The single place gesture-mode transitions are applied, because they carry
 * EPD refresh-mode side effects: active modes draw with fast animation
 * refresh, returning to Normal restores full-quality refresh.
 */
private fun applyGestureMode(
    recognizer: Recognizer,
    new: GestureMode,
    ctx: GestureContext,
) {
    val previous = recognizer.mode
    if (previous == new) return
    log.d("Entered ${new.name} gesture mode")
    when (new) {
        GestureMode.Transform, GestureMode.Scroll, GestureMode.Selection -> {
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
    recognizer.mode = new
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

private fun Offset.toIntOffset() = IntOffset(x.toInt(), y.toInt())

private fun IntRect.toAndroidRect() = Rect(left, top, right, bottom)
