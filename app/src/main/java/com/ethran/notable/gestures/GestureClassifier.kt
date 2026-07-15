package com.ethran.notable.gestures

import kotlin.math.abs

/**
 * Recognizer phase of the gesture in progress. Owned by the receiver;
 * transitions carry EPD refresh-mode side effects and are applied in one
 * place (`applyGestureMode`).
 */
enum class GestureMode {
    Selection,
    Scroll,

    /** Two fingers panning and pinch-zooming together — one continuous transform. */
    Transform,
    Normal,
}

/** The settings that affect *recognition* (not which action a gesture maps to). */
data class GestureFlags(
    val smoothScroll: Boolean,
    val continuousZoom: Boolean,
)

/**
 * End-of-gesture classification: pure function from what the fingers did to
 * the [GestureEvent]s it means. Reads no settings besides [flags] and calls
 * only pure [PointerTracker] members, so it can be exercised in JVM tests
 * with synthetic input sequences.
 */
fun classifyGesture(
    tracker: PointerTracker,
    mode: GestureMode,
    flags: GestureFlags,
    thresholds: GestureThresholds,
): List<GestureEvent> {
    val events = mutableListOf<GestureEvent>()
    val fingers = tracker.maxConcurrentPressed

    if (fingers == 1) {
        if (isOneFingerTap(tracker, thresholds)) events += GestureEvent.Tap(fingers = 1)
    } else if (fingers == 2) {
        if (isTwoFingerTap(tracker, mode, thresholds)) events += GestureEvent.Tap(fingers = 2)

        val pinchRatio = tracker.pinchRatio()
        if (!flags.continuousZoom && abs(pinchRatio) > PINCH_ZOOM_THRESHOLD) {
            events += GestureEvent.PinchZoom(pinchRatio)
        }
    }

    if (mode == GestureMode.Normal) {
        // Multi-finger swipes use the accumulated centroid travel (survives
        // fingers re-landing mid-swipe) and a shorter distance threshold;
        // one-finger swipes keep the per-track measure.
        val horizontalDrag: Float
        val swipeThresholdPx: Float
        if (fingers == 1) {
            horizontalDrag = tracker.horizontalDrag(thresholds.swipeNoiseFloorPx)
            swipeThresholdPx = thresholds.swipePx
        } else {
            val net = tracker.netCentroidTravel()
            horizontalDrag = if (abs(net.x) > abs(net.y)) net.x else 0f
            swipeThresholdPx = thresholds.multiFingerSwipePx
        }
        if (horizontalDrag < -swipeThresholdPx) {
            events += GestureEvent.Swipe(fingers, GestureEvent.Direction.Left)
        } else if (horizontalDrag > swipeThresholdPx) {
            events += GestureEvent.Swipe(fingers, GestureEvent.Direction.Right)
        }
    }

    if (!flags.smoothScroll && fingers == 1) {
        val verticalDrag = tracker.verticalDrag(thresholds.swipeNoiseFloorPx)
        if (abs(verticalDrag) > thresholds.swipePx) {
            events += GestureEvent.VerticalScroll(verticalDrag)
        }
    }

    return events
}

// --- Classification predicates: thresholds applied to tracker geometry. ---

fun isOneFingerTap(tracker: PointerTracker, thresholds: GestureThresholds): Boolean {
    return tracker.totalTravel() < thresholds.tapMovementTolerancePx &&
            tracker.inputDuration() < ONE_FINGER_TOUCH_TAP_TIME
}

fun isTwoFingerTap(
    tracker: PointerTracker,
    mode: GestureMode,
    thresholds: GestureThresholds,
): Boolean {
    if (tracker.maxConcurrentPressed == 1) return false
    if (mode != GestureMode.Normal) return false
    val duration = tracker.inputDuration()
    return tracker.totalTravel() < thresholds.twoFingerTapMovementTolerancePx &&
            duration < TWO_FINGER_TOUCH_TAP_MAX_TIME &&
            duration > TWO_FINGER_TOUCH_TAP_MIN_TIME
}

/**
 * Three or more fingers swiping up — the app-level gesture that opens
 * QuickNav. Measured on the accumulated centroid travel so a finger dropping
 * and re-landing mid-swipe doesn't under-measure it; the direction check
 * keeps it clear of the horizontal three-finger swipe actions.
 */
fun isQuickNavSwipe(tracker: PointerTracker, thresholds: GestureThresholds): Boolean {
    if (tracker.maxConcurrentPressed < QUICK_NAV_FINGER_COUNT) return false
    val net = tracker.netCentroidTravel()
    // Screen coordinates: up is negative y.
    return net.y < -thresholds.multiFingerSwipePx && abs(net.y) > abs(net.x)
}

/** Hold detection measures against "now" — a stationary finger sends no events. */
fun isHoldingOneFinger(tracker: PointerTracker, thresholds: GestureThresholds): Boolean {
    return tracker.elapsedSinceStart() >= HOLD_THRESHOLD_MS &&
            tracker.maxConcurrentPressed == 1 &&
            tracker.totalTravel() < thresholds.tapMovementTolerancePx
}

/**
 * Two-finger transform (pan + pinch-zoom as one gesture). Enters on a pan
 * ([GestureThresholds.panEnterPx]) or, with continuous zoom on, on a pinch
 * ([PINCH_ZOOM_THRESHOLD_CONTINUOUS]). With continuous zoom off a pure pinch
 * stays in Normal, so the discrete snap-zoom fires at gesture end instead.
 */
fun shouldEnterTransform(
    tracker: PointerTracker,
    mode: GestureMode,
    thresholds: GestureThresholds,
    continuousZoom: Boolean,
): Boolean {
    if (mode != GestureMode.Normal) return false
    if (tracker.maxConcurrentPressed != 2 || tracker.pressedCount() != 2) return false
    val panning = tracker.centroidTravel() > thresholds.panEnterPx
    val pinching = continuousZoom && abs(tracker.pinchRatio()) > PINCH_ZOOM_THRESHOLD_CONTINUOUS
    return panning || pinching
}

fun shouldEnterScroll(
    tracker: PointerTracker,
    mode: GestureMode,
    thresholds: GestureThresholds,
): Boolean {
    return mode == GestureMode.Normal &&
            tracker.maxConcurrentPressed == 1 &&
            abs(tracker.verticalDrag(thresholds.swipeNoiseFloorPx)) > thresholds.smoothScrollEnterPx
}
