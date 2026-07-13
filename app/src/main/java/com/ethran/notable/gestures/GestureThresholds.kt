package com.ethran.notable.gestures

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

// Time thresholds (density-independent).
const val HOLD_THRESHOLD_MS = 300
const val ONE_FINGER_TOUCH_TAP_TIME = 100L
const val TWO_FINGER_TOUCH_TAP_MAX_TIME = 200L
const val TWO_FINGER_TOUCH_TAP_MIN_TIME = 20L
const val DOUBLE_TAP_TIMEOUT_MS = 170L
const val DOUBLE_TAP_MIN_MS = 20L

// Pinch thresholds are distance *ratios*, also density-independent.
const val PINCH_ZOOM_THRESHOLD = 0.5f
const val PINCH_ZOOM_THRESHOLD_CONTINUOUS = 0.25f
const val ZOOM_SNAP_THRESHOLD = 0.02f

// Fraction of the per-frame pinch growth applied to the zoom. 1.0 tracks the
// fingers exactly; lower zooms more gently. Tune to taste.
const val ZOOM_SENSITIVITY = 0.4f

// Bounds the zoom level is clamped to.
const val MIN_ZOOM = 0.1f
const val MAX_ZOOM = 10.0f

// E-ink touch panels routinely drop one contact of a multi-finger gesture for
// a few ms and re-report it with a new pointer id. After all fingers lift,
// multi-finger gestures wait this long for a re-landing contact before the
// gesture is considered finished.
const val TOUCH_RELAND_GRACE_MS = 80L

// How long fast (animation) refresh is kept after a gesture ends. A follow-up
// flick lands and re-acquires within this window, so rapid flick scrolling
// never drops to a quality refresh in between; only after the user actually
// stops does the screen settle back to full quality.
const val GESTURE_REFRESH_SETTLE_MS = 500L

// Distance thresholds in dp; a raw pixel count is a different physical
// distance on every panel, so distances are declared here and resolved to
// px once per pointerInput block via [GestureThresholds].
private val TAP_MOVEMENT_TOLERANCE = 15.dp
private val TWO_FINGER_TAP_MOVEMENT_TOLERANCE = 20.dp

// One-finger swipe distance (the default page-turn) and the shorter travel
// that engages smooth scrolling.
private val SWIPE_THRESHOLD = 160.dp
private val SWIPE_THRESHOLD_SMOOTH = 100.dp

// How far two fingers must translate together before a pan engages. No
// stationary hold first, so panning feels immediate; a pure pinch keeps the
// centroid still and never trips this.
private val PAN_ENTER_THRESHOLD = 30.dp

/**
 * Distance thresholds resolved to px for the current [Density]. Built once
 * per pointerInput block; recognition code takes this instead of reading
 * global constants, which is also what makes classification testable with
 * synthetic values.
 */
class GestureThresholds(density: Density) {
    /** Max total finger travel for a one-finger tap / hold. */
    val tapMovementTolerancePx: Float = with(density) { TAP_MOVEMENT_TOLERANCE.toPx() }

    /** Max total travel (both fingers combined) for a two-finger tap. */
    val twoFingerTapMovementTolerancePx: Float =
        with(density) { TWO_FINGER_TAP_MOVEMENT_TOLERANCE.toPx() }

    /** Net two-finger translation before a pan engages. */
    val panEnterPx: Float = with(density) { PAN_ENTER_THRESHOLD.toPx() }

    /** Min directional travel for a swipe (and for discrete scroll). */
    val swipePx: Float = with(density) { SWIPE_THRESHOLD.toPx() }

    /** Min vertical travel before smooth scrolling engages. */
    val smoothScrollEnterPx: Float = with(density) { SWIPE_THRESHOLD_SMOOTH.toPx() }
}
