package com.ethran.notable.classes

import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.unit.IntOffset
import com.ethran.notable.modals.GlobalAppSettings
import com.ethran.notable.utils.SimplePointF
import com.ethran.notable.utils.setAnimationMode
import io.shipbook.shipbooksdk.ShipBook
import kotlin.math.abs
import kotlin.math.sqrt


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

enum class GestureMode {
    Selection,
    Scroll,
    Zoom,
    Normal
}


data class GestureState(
    val initialPositions: MutableMap<PointerId, Offset> = mutableMapOf(),
    val lastPositions: MutableMap<PointerId, Offset> = mutableMapOf(),
    var initialTimestamp: Long = System.currentTimeMillis(),
    var lastTimestamp: Long = initialTimestamp,
) {
    val log = ShipBook.getLogger("GestureState")

    var gestureMode: GestureMode = GestureMode.Normal
        set(value) {
            if (field != value) {
                when (value) {
                    GestureMode.Zoom, GestureMode.Scroll, GestureMode.Selection -> {
                        log.d("Entered ${value.name} gesture mode")
                        setAnimationMode(true)
                    }
                    GestureMode.Normal -> {
                        log.d("Entered ${value.name} gesture mode")
                        setAnimationMode(false)
                    }
                }
                field = value
            }
        }
    private var lastCheckForMovementPosition: List<Offset>? = null

    fun getElapsedTime(): Long {
        return lastTimestamp - initialTimestamp
    }

    private fun calculateTotalDelta(): Float {
        return initialPositions.keys.sumOf { id ->
            val initial = initialPositions[id] ?: Offset.Zero
            val last = lastPositions[id] ?: initial
            (initial - last).getDistance().toDouble()
        }.toFloat()
    }

    fun getFirstPosition(): IntOffset? {
        return initialPositions.values.firstOrNull()?.let { point ->
            IntOffset(point.x.toInt(), point.y.toInt())
        }
    }

    fun getFirstPositionF(): SimplePointF? {
        return initialPositions.values.firstOrNull()?.let { point ->
            SimplePointF(point.x, point.y)
        }
    }

    fun getLastPositionIO(): IntOffset? {
        return lastPositions.values.firstOrNull()?.let { point ->
            IntOffset(point.x.toInt(), point.y.toInt())
        } ?: getFirstPosition()
    }

    fun calculateRectangleBounds(): Rect? {
        if (initialPositions.isEmpty() && lastPositions.isEmpty()) return null

        val firstPosition = initialPositions.values.firstOrNull() ?: return null
        val lastPosition = lastPositions.values.firstOrNull() ?: firstPosition

        return Rect(
            firstPosition.x.coerceAtMost(lastPosition.x).toInt(),
            firstPosition.y.coerceAtMost(lastPosition.y).toInt(),
            firstPosition.x.coerceAtLeast(lastPosition.x).toInt(),
            firstPosition.y.coerceAtLeast(lastPosition.y).toInt()
        )
    }

    // Insert a position for the given pointer ID
    fun insertPosition(input: PointerInputChange) {
        lastTimestamp = System.currentTimeMillis()
        if (initialPositions.containsKey(input.id)) {
            // Update last position if the pointer ID already exists in initial positions
            lastPositions[input.id] = input.position

        } else {
            // Add to initial positions if the pointer ID is new
            initialPositions[input.id] = input.position
        }
    }

    // Get the current number of active inputs
    fun getInputCount(): Int {
        return initialPositions.size
    }

    //return smallest horizontal movement, or 0, if movement is not horizontal
    fun getHorizontalDrag(): Int {
        if (initialPositions.isEmpty() || lastPositions.isEmpty()) return 0

        var minHorizontalMovement: Float? = null

        for ((id, initial) in initialPositions) {
            val last = lastPositions[id] ?: continue
            val delta = last - initial

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
    fun getVerticalDrag(): Int {
        if (initialPositions.isEmpty() || lastPositions.isEmpty()) return 0

        var minVerticalMovement: Float? = null

        for ((id, initial) in initialPositions) {
            val last = lastPositions[id] ?: continue
            val delta = last - initial

            // Check if the movement is more vertical than horizontal
            if (abs(delta.y) <= abs(delta.x)) return 0

            // Track the smallest vertical movement
            if (minVerticalMovement == null || abs(delta.y) < abs(minVerticalMovement)) {
                minVerticalMovement = delta.y
            }
        }
        return minVerticalMovement?.toInt() ?: 0
    }


    // returns the delta from last request
    fun getVerticalDragDelta(): Int {
        if (lastPositions.isEmpty()) return 0
        val currentPosition = lastPositions.values.toList()
        if (lastCheckForMovementPosition.isNullOrEmpty()) {
            lastCheckForMovementPosition = currentPosition
            return 0
        }
        val initial = lastCheckForMovementPosition?.get(0)?.y ?: return 0
        val last = currentPosition[0].y
        val delta = (last - initial).toInt()
        lastCheckForMovementPosition = currentPosition
        return delta
    }

    private fun calculateDistance(point1: Offset, point2: Offset): Float {
        val dx = point1.x - point2.x
        val dy = point1.y - point2.y
        return sqrt(dx * dx + dy * dy)
    }

    // returns value to be added or subtracted to zoom
    fun getPinchDrag(): Float {
        if (lastPositions.size < 2 || initialPositions.size < 2) return 0.0f

        val currentDistance = calculateDistance(
            lastPositions.values.elementAt(0),
            lastPositions.values.elementAt(1)
        )

        val initialDistance = calculateDistance(
            initialPositions.values.elementAt(0),
            initialPositions.values.elementAt(1)
        )

        if (initialDistance == 0f) return 0.0f
        return currentDistance / initialDistance - 1.0f
    }

    // Returns incremental zoom delta since last check
    fun getPinchDelta(): Float {
        val currentPosition = lastPositions.values.toList()
        if (currentPosition.size < 2) return 0f

        val previousPosition = lastCheckForMovementPosition
        if (previousPosition.isNullOrEmpty() || previousPosition.size < 2) {
            lastCheckForMovementPosition = currentPosition
            return 0f
        }

        val currentDistance = calculateDistance(currentPosition[0], currentPosition[1])
        val lastDistance = calculateDistance(previousPosition[0], previousPosition[1])

        // Avoid division by zero
        if (lastDistance == 0f) return 0f

        lastCheckForMovementPosition = currentPosition

        return currentDistance / lastDistance - 1f
    }


    fun isHolding(): Boolean {
        return if (getElapsedTime() >= HOLD_THRESHOLD_MS && getInputCount() == 1)
            if (calculateTotalDelta() < TAP_MOVEMENT_TOLERANCE)
                true
            else
                false
        else
            false
    }

    fun checkSmoothScrolling(): Boolean {
        return if (GlobalAppSettings.current.smoothScroll && abs(getVerticalDrag()) > SWIPE_THRESHOLD_SMOOTH && getInputCount() == 1) {
            gestureMode = GestureMode.Scroll
            true
        } else
            false
    }

    fun checkContinuousZoom(): Boolean {
        return if (GlobalAppSettings.current.continuousZoom && abs(getPinchDrag()) > PINCH_ZOOM_THRESHOLD_CONTINUOUS && getInputCount() == 2) {
            gestureMode = GestureMode.Zoom
            true
        } else
            false
    }

    fun isOneFinger(): Boolean {
        return getInputCount() == 1
    }

    fun isTwoFingers(): Boolean {
        return getInputCount() == 2
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