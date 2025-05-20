package com.ethran.notable.classes

import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.unit.IntOffset
import com.ethran.notable.utils.SimplePointF
import kotlin.math.abs


enum class GestureMode {
    Selection,
    Scroll,
    Normal
}


data class GestureState(
    val initialPositions: MutableMap<PointerId, Offset> = mutableMapOf(),
    val lastPositions: MutableMap<PointerId, Offset> = mutableMapOf(),
    var initialTimestamp: Long = System.currentTimeMillis(),
    var lastTimestamp: Long = initialTimestamp,
    var gestureMode: GestureMode = GestureMode.Normal,
) {
    private var lastCheckForMovementPosition: Offset? = null

    fun getElapsedTime(): Long {
        return lastTimestamp - initialTimestamp
    }

    fun calculateTotalDelta(): Float {
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
    fun getHorizontalDrag(): Float {
        if (initialPositions.isEmpty() || lastPositions.isEmpty()) return 0f

        var minHorizontalMovement: Float? = null

        for ((id, initial) in initialPositions) {
            val last = lastPositions[id] ?: continue
            val delta = last - initial

            // Check if the movement is more horizontal than vertical
            if (abs(delta.x) <= abs(delta.y)) return 0f

            // Track the smallest horizontal movement
            if (minHorizontalMovement == null || abs(delta.x) < abs(minHorizontalMovement)) {
                minHorizontalMovement = delta.x
            }
        }

        return minHorizontalMovement ?: 0f
    }

    //return smallest vertical movement, or 0, if movement is not vertical
    fun getVerticalDrag(): Float {
        if (initialPositions.isEmpty() || lastPositions.isEmpty()) return 0f

        var minVerticalMovement: Float? = null

        for ((id, initial) in initialPositions) {
            val last = lastPositions[id] ?: continue
            val delta = last - initial

            // Check if the movement is more vertical than horizontal
            if (abs(delta.y) <= abs(delta.x)) return 0f

            // Track the smallest vertical movement
            if (minVerticalMovement == null || abs(delta.y) < abs(minVerticalMovement)) {
                minVerticalMovement = delta.y
            }
        }
        return minVerticalMovement ?: 0f
    }

    fun getVerticalDragDelta(): Int {
        if (lastPositions.isEmpty()) return 0
        val currentPosition = lastPositions.values.lastOrNull() ?: return 0
        if (lastCheckForMovementPosition == null) {
            lastCheckForMovementPosition = currentPosition
            return 0
        }
        val initial = lastCheckForMovementPosition?.y ?: return 0
        val last = currentPosition.y
        val delta = (last - initial).toInt()
        lastCheckForMovementPosition = currentPosition
        return delta
    }
}