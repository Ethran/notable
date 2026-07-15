package com.ethran.notable.gestures

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.unit.IntRect
import kotlin.math.abs

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

/**
 * Pure pointer geometry for one gesture: which fingers went where, when.
 * No thresholds, no classification, no Android dependencies — timestamps
 * come from the input events plus the injected [now] clock (matching
 * `PointerInputChange.uptimeMillis`' time base), so the tracker runs as-is
 * in JVM tests fed with synthetic sequences.
 *
 * The only impure members are the `consume*` deltas, which advance their
 * reference on each call.
 */
class PointerTracker(
    private val now: () -> Long,
) {
    val initialTimestamp: Long = now()

    /**
     * Timestamp of the last input event; only [update] advances it, so
     * double-tap timing always measures from the finger, never from a
     * hold-detection tick.
     */
    var lastInputTimestamp: Long = initialTimestamp
        private set

    // Insertion-ordered: first entry is the first finger that went down.
    // Keyed by the raw pointer-id value so tests don't need PointerId.
    private val pointers = LinkedHashMap<Long, PointerTrack>()

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
    private var pinchPair: Pair<Long, Long>? = null

    // Reference for incremental drag deltas: centroid of pressed pointers and
    // the id set it was computed from. When the set changes (finger lands,
    // lifts, or id churns) the reference is reset instead of accumulating a
    // bogus jump.
    private var movementRefCentroid: Offset? = null
    private var movementRefIds: Set<Long> = emptySet()

    private var lastPinchDistance: Float? = null

    // Net centroid translation accumulated across the whole gesture, with the
    // same guarded-reference pattern as the drag delta: when the pressed set
    // changes the reference resets, so a finger dropping and re-landing
    // mid-swipe neither adds a bogus jump nor under-measures the travel.
    private var netTravelRefCentroid: Offset? = null
    private var netTravelRefIds: Set<Long> = emptySet()
    private var netTravel: Offset = Offset.Zero

    /** Feed every touch [PointerInputChange] of the gesture through this. */
    fun update(change: PointerInputChange) =
        update(change.id.value, change.position, change.pressed, change.uptimeMillis)

    /** Raw-value overload for tests. */
    fun update(id: Long, position: Offset, pressed: Boolean, timestamp: Long) {
        lastInputTimestamp = timestamp
        val track = pointers[id]
        if (track == null) {
            if (!pressed) return
            pointers[id] = PointerTrack(position, position)
            val pressedNow = pressedCount()
            if (pressedNow > maxConcurrentPressed) maxConcurrentPressed = pressedNow
            if (pinchPair == null && pressedNow == 2) {
                val ids = pointers.filterValues { it.pressed }.keys.toList()
                pinchPair = ids[0] to ids[1]
            }
        } else {
            track.currentPosition = position
            track.pressed = pressed
        }
        advanceNetTravel()
    }

    private fun advanceNetTravel() {
        val pressed = pointers.filterValues { it.pressed }
        if (pressed.isEmpty()) {
            netTravelRefCentroid = null
            netTravelRefIds = emptySet()
            return
        }
        var sumX = 0f
        var sumY = 0f
        for (track in pressed.values) {
            sumX += track.currentPosition.x
            sumY += track.currentPosition.y
        }
        val centroid = Offset(sumX / pressed.size, sumY / pressed.size)
        val ids = pressed.keys.toSet()
        val reference = netTravelRefCentroid
        if (reference != null && ids == netTravelRefIds) netTravel += centroid - reference
        netTravelRefCentroid = centroid
        netTravelRefIds = ids
    }

    /** Number of fingers currently on the screen. */
    fun pressedCount(): Int = pointers.values.count { it.pressed }

    /** Duration between the first and the last input event of the gesture. */
    fun inputDuration(): Long = lastInputTimestamp - initialTimestamp

    /**
     * Time since the gesture started, measured against the clock. Events only
     * arrive on change, so hold detection must use this: a stationary finger
     * produces no input to advance [lastInputTimestamp].
     */
    fun elapsedSinceStart(): Long = now() - initialTimestamp

    /** Summed travel of every pointer from its down position. */
    fun totalTravel(): Float {
        return pointers.values.sumOf { track ->
            (track.downPosition - track.currentPosition).getDistance().toDouble()
        }.toFloat()
    }

    /**
     * Net translation of the pressed-pointer centroid from where the fingers
     * landed: a pan moves it, a symmetric pinch leaves it ~0. That is what
     * tells panning apart from zooming.
     */
    fun centroidTravel(): Float {
        val pressed = pointers.values.filter { it.pressed }
        if (pressed.isEmpty()) return 0f
        var dx = 0f
        var dy = 0f
        for (track in pressed) {
            dx += track.currentPosition.x - track.downPosition.x
            dy += track.currentPosition.y - track.downPosition.y
        }
        return Offset(dx / pressed.size, dy / pressed.size).getDistance()
    }

    /**
     * Net centroid translation accumulated over the whole gesture. Unlike
     * [centroidTravel] (down → current of the pressed fingers) it survives
     * fingers lifting and re-landing mid-gesture, so it is the swipe distance
     * for multi-finger gestures.
     */
    fun netCentroidTravel(): Offset = netTravel

    /** Current position of the first finger that went down. */
    fun lastPosition(): Offset? = pointers.values.firstOrNull()?.currentPosition

    /** Rectangle spanned by the first finger's down and current positions. */
    fun dragRect(): IntRect? {
        val track = pointers.values.firstOrNull() ?: return null
        val first = track.downPosition
        val last = track.currentPosition

        return IntRect(
            first.x.coerceAtMost(last.x).toInt(),
            first.y.coerceAtMost(last.y).toInt(),
            first.x.coerceAtLeast(last.x).toInt(),
            first.y.coerceAtLeast(last.y).toInt()
        )
    }

    /**
     * Smallest horizontal movement across the fingers, or 0 if any finger
     * moved more vertically than horizontally. Pointers whose total delta is
     * under [noiseFloorPx] (phantom palm contacts, re-landed fingers that
     * barely moved) neither veto nor contribute.
     */
    fun horizontalDrag(noiseFloorPx: Float = 0f): Float {
        var minHorizontalMovement: Float? = null

        for (track in pointers.values) {
            val delta = track.currentPosition - track.downPosition
            if (delta.getDistance() < noiseFloorPx) continue

            // Check if the movement is more horizontal than vertical
            if (abs(delta.x) <= abs(delta.y)) return 0f

            // Track the smallest horizontal movement
            if (minHorizontalMovement == null || abs(delta.x) < abs(minHorizontalMovement)) {
                minHorizontalMovement = delta.x
            }
        }
        return minHorizontalMovement ?: 0f
    }

    /**
     * Smallest vertical movement across the fingers, or 0 if any finger moved
     * more horizontally than vertically. Same [noiseFloorPx] rule as
     * [horizontalDrag].
     */
    fun verticalDrag(noiseFloorPx: Float = 0f): Float {
        var minVerticalMovement: Float? = null

        for (track in pointers.values) {
            val delta = track.currentPosition - track.downPosition
            if (delta.getDistance() < noiseFloorPx) continue

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

    /** Pinch growth ratio since the gesture start (0 = unchanged). */
    fun pinchRatio(): Float {
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

    /** Current focal point (center) of the pinch gesture in screen coordinates. */
    fun pinchCenter(): Offset? {
        val (a, b) = pinchPair ?: return null
        val positionA = pointers[a]?.currentPosition ?: return null
        val positionB = pointers[b]?.currentPosition ?: return null
        return Offset((positionA.x + positionB.x) / 2f, (positionA.y + positionB.y) / 2f)
    }
}
