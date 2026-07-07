package com.ethran.notable.gestures

import android.graphics.Rect
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.unit.IntOffset
import com.ethran.notable.data.model.SimplePointF
import kotlin.math.abs


const val HOLD_THRESHOLD_MS = 300
private const val ONE_FINGER_TOUCH_TAP_TIME = 100L
private const val TAP_MOVEMENT_TOLERANCE = 15f
private const val SWIPE_THRESHOLD_SMOOTH = 100f
private const val TWO_FINGER_TOUCH_TAP_MAX_TIME = 200L
private const val TWO_FINGER_TOUCH_TAP_MIN_TIME = 20L
private const val TWO_FINGER_TAP_MOVEMENT_TOLERANCE = 20f
private const val PINCH_ZOOM_THRESHOLD_CONTINUOUS = 0.25f

const val PINCH_ZOOM_THRESHOLD = 0.5f
const val SWIPE_THRESHOLD = 200f
const val DOUBLE_TAP_TIMEOUT_MS = 170L
const val DOUBLE_TAP_MIN_MS = 20L
const val ZOOM_SNAP_THRESHOLD = 0.02f

// E-ink touch panels routinely drop one contact of a multi-finger gesture for
// a few ms and re-report it with a new pointer id. After all fingers lift,
// multi-finger gestures wait this long for a re-landing contact before the
// gesture is considered finished.
const val TOUCH_RELAND_GRACE_MS = 80L

enum class GestureMode {
    Selection,
    Scroll,
    Zoom,
    Normal,
    Drag
}

/**
 * Life of a single pointer within a gesture. Entries are kept after the
 * pointer lifts so end-of-gesture classification sees the full history;
 * live computations (drag delta, pinch) filter to pressed pointers.
 */
private class PointerTrack(
    val downPosition: Offset,
    var currentPosition: Offset,
    var pressed: Boolean = true,
)

// Timestamps use the uptime base (SystemClock.uptimeMillis), matching
// PointerInputChange.uptimeMillis, so durations are immune to wall-clock
// jumps.
class GestureState(
    val initialTimestamp: Long = SystemClock.uptimeMillis(),
) {
    // Mode transitions carry EPD refresh-mode side effects; the receiver
    // applies them in one place (applyGestureMode) — never assign directly.
    var gestureMode: GestureMode = GestureMode.Normal

    // Timestamp of the last real input event; only [update] may advance it,
    // so double-tap timing always measures from the finger, never from a
    // hold-detection tick.
    var lastInputTimestamp: Long = initialTimestamp
        private set

    // Insertion-ordered: first entry is the first finger that went down.
    private val pointers = LinkedHashMap<PointerId, PointerTrack>()

    /**
     * Highest number of simultaneously pressed fingers seen in this gesture.
     * Pointer ids churn on e-ink panels (contacts are dropped and re-reported
     * with new ids), so gestures are classified by this high-water mark, not
     * by how many ids were ever seen or are pressed at gesture end.
     */
    var maxConcurrentPressed: Int = 0
        private set

    // The two pointers pinch math is computed from, fixed when the second
    // finger lands so both distances always use the same finger pair. Kept
    // even after a finger lifts: discrete zoom is evaluated at gesture end.
    private var pinchPair: Pair<PointerId, PointerId>? = null

    // Reference for incremental drag deltas: centroid of pressed pointers and
    // the id set it was computed from. When the set changes (finger lands,
    // lifts, or id churns) the reference is reset instead of accumulating a
    // bogus jump.
    private var movementRefCentroid: Offset? = null
    private var movementRefIds: Set<PointerId> = emptySet()

    private var lastPinchDistance: Float? = null

    /** Feed every touch [PointerInputChange] of the gesture through this. */
    fun update(change: PointerInputChange) {
        lastInputTimestamp = change.uptimeMillis
        val track = pointers[change.id]
        if (track == null) {
            if (!change.pressed) return
            pointers[change.id] = PointerTrack(change.position, change.position)
            val pressed = pressedCount()
            if (pressed > maxConcurrentPressed) maxConcurrentPressed = pressed
            if (pinchPair == null && pressed == 2) {
                val ids = pointers.filterValues { it.pressed }.keys.toList()
                pinchPair = ids[0] to ids[1]
            }
        } else {
            track.currentPosition = change.position
            track.pressed = change.pressed
        }
    }

    /** Number of fingers currently on the screen. */
    fun pressedCount(): Int = pointers.values.count { it.pressed }

    /** Finger count used for gesture classification (see [maxConcurrentPressed]). */
    fun getInputCount(): Int = maxConcurrentPressed

    /** Duration between the first and the last input event of the gesture. */
    fun getElapsedTime(): Long {
        return lastInputTimestamp - initialTimestamp
    }

    // Events only arrive on change, so hold detection must measure against
    // "now": a stationary finger produces no input to advance
    // lastInputTimestamp.
    private fun elapsedSinceStart(): Long = SystemClock.uptimeMillis() - initialTimestamp

    private fun calculateTotalDelta(): Float {
        return pointers.values.sumOf { track ->
            (track.downPosition - track.currentPosition).getDistance().toDouble()
        }.toFloat()
    }

    fun getFirstPosition(): IntOffset? {
        return pointers.values.firstOrNull()?.downPosition?.let { point ->
            IntOffset(point.x.toInt(), point.y.toInt())
        }
    }

    fun getFirstPositionF(): SimplePointF? {
        return pointers.values.firstOrNull()?.downPosition?.let { point ->
            SimplePointF(point.x, point.y)
        }
    }

    fun getLastPositionIO(): IntOffset? {
        return pointers.values.firstOrNull()?.currentPosition?.let { point ->
            IntOffset(point.x.toInt(), point.y.toInt())
        }
    }

    fun calculateRectangleBounds(): Rect? {
        val track = pointers.values.firstOrNull() ?: return null
        val first = track.downPosition
        val last = track.currentPosition

        return Rect(
            first.x.coerceAtMost(last.x).toInt(),
            first.y.coerceAtMost(last.y).toInt(),
            first.x.coerceAtLeast(last.x).toInt(),
            first.y.coerceAtLeast(last.y).toInt()
        )
    }

    //return smallest horizontal movement, or 0, if movement is not horizontal
    fun getHorizontalDrag(): Int {
        var minHorizontalMovement: Float? = null

        for (track in pointers.values) {
            val delta = track.currentPosition - track.downPosition

            // Check if the movement is more horizontal than vertical
            if (abs(delta.x) <= abs(delta.y)) return 0

            // Track the smallest horizontal movement
            if (minHorizontalMovement == null || abs(delta.x) < abs(minHorizontalMovement)) {
                minHorizontalMovement = delta.x
            }
        }
        return minHorizontalMovement?.toInt() ?: 0
    }

    //return smallest vertical movement, or 0, if movement is not vertical
    fun getVerticalDrag(): Float {
        var minVerticalMovement: Float? = null

        for (track in pointers.values) {
            val delta = track.currentPosition - track.downPosition

            // Check if the movement is more vertical than horizontal
            if (abs(delta.y) <= abs(delta.x)) return 0f

            // Track the smallest vertical movement
            if (minVerticalMovement == null || abs(delta.y) < abs(minVerticalMovement)) {
                minVerticalMovement = delta.y
            }
        }
        return minVerticalMovement ?: 0f
    }

    /**
     * Consumes and returns the drag delta since the previous call: the
     * centroid movement of the pressed pointers, with the reference advanced
     * to the current centroid. Not a pure getter — call once per frame.
     */
    fun consumeDragDelta(): Offset {
        val pressed = pointers.filterValues { it.pressed }
        if (pressed.isEmpty()) return Offset.Zero

        var sumX = 0f
        var sumY = 0f
        for (track in pressed.values) {
            sumX += track.currentPosition.x
            sumY += track.currentPosition.y
        }
        val centroid = Offset(sumX / pressed.size, sumY / pressed.size)
        val ids = pressed.keys.toSet()

        val reference = movementRefCentroid
        val delta = if (reference != null && ids == movementRefIds) {
            centroid - reference
        } else {
            Offset.Zero
        }
        movementRefCentroid = centroid
        movementRefIds = ids
        return delta
    }

    private fun pinchDistance(position: (PointerTrack) -> Offset): Float? {
        val (a, b) = pinchPair ?: return null
        val trackA = pointers[a] ?: return null
        val trackB = pointers[b] ?: return null
        return (position(trackA) - position(trackB)).getDistance()
    }

    // returns value to be added or subtracted to zoom
    fun getPinchDrag(): Float {
        val currentDistance = pinchDistance { it.currentPosition } ?: return 0f
        val initialDistance = pinchDistance { it.downPosition } ?: return 0f

        if (initialDistance == 0f) return 0f
        return currentDistance / initialDistance - 1f
    }

    /**
     * Consumes and returns the incremental zoom delta since the previous
     * call, advancing the pinch-distance reference. Not a pure getter — call
     * once per frame.
     */
    fun consumePinchDelta(): Float {
        val currentDistance = pinchDistance { it.currentPosition } ?: return 0f
        val lastDistance = lastPinchDistance
        lastPinchDistance = currentDistance

        if (lastDistance == null || lastDistance == 0f) return 0f
        return currentDistance / lastDistance - 1f
    }

    /**
     * Returns the current focal point (center) of the pinch gesture in screen coordinates.
     */
    fun getPinchCenter(): Offset? {
        val (a, b) = pinchPair ?: return null
        val positionA = pointers[a]?.currentPosition ?: return null
        val positionB = pointers[b]?.currentPosition ?: return null
        return Offset((positionA.x + positionB.x) / 2f, (positionA.y + positionB.y) / 2f)
    }

    fun isHoldingOneFinger(): Boolean {
        return elapsedSinceStart() >= HOLD_THRESHOLD_MS &&
                maxConcurrentPressed == 1 &&
                calculateTotalDelta() < TAP_MOVEMENT_TOLERANCE
    }

    fun shouldEnterDrag(): Boolean {
        return gestureMode == GestureMode.Normal &&
                elapsedSinceStart() >= HOLD_THRESHOLD_MS &&
                maxConcurrentPressed == 2 &&
                calculateTotalDelta() < 2 * TAP_MOVEMENT_TOLERANCE
    }

    fun shouldEnterScroll(): Boolean {
        return gestureMode == GestureMode.Normal &&
                maxConcurrentPressed == 1 &&
                abs(getVerticalDrag()) > SWIPE_THRESHOLD_SMOOTH
    }

    fun shouldEnterZoom(): Boolean {
        return gestureMode == GestureMode.Normal &&
                maxConcurrentPressed == 2 &&
                abs(getPinchDrag()) > PINCH_ZOOM_THRESHOLD_CONTINUOUS
    }

    fun isOneFinger(): Boolean {
        return maxConcurrentPressed == 1
    }

    fun isTwoFingers(): Boolean {
        return maxConcurrentPressed == 2
    }

    fun isOneFingerTap(): Boolean {
        val totalDelta = calculateTotalDelta()
        val gestureDuration = getElapsedTime()
        return totalDelta < TAP_MOVEMENT_TOLERANCE && gestureDuration < ONE_FINGER_TOUCH_TAP_TIME
    }

    fun isTwoFingersTap(): Boolean {
        if (isOneFinger()) return false
        if (gestureMode != GestureMode.Normal) return false
        val totalDelta = calculateTotalDelta()
        val gestureDuration = getElapsedTime()
        return totalDelta < TWO_FINGER_TAP_MOVEMENT_TOLERANCE &&
                gestureDuration < TWO_FINGER_TOUCH_TAP_MAX_TIME &&
                gestureDuration > TWO_FINGER_TOUCH_TAP_MIN_TIME
    }
}
