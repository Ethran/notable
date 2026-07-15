package com.ethran.notable.gestures

import androidx.compose.ui.unit.IntRect

/**
 * A recognized gesture — the value between recognition and acting on it.
 * Produced by [classifyGesture] at gesture end (plus [DoubleTap] and
 * [HoldSelect], which the receiver emits itself: double-tap needs awaiting a
 * second down, hold-select is the end of a modal mode). Continuous modes
 * (smooth scroll and the two-finger transform — pan plus continuous zoom)
 * stay streamed callbacks — they are not events.
 *
 * One gesture can produce more than one event (e.g. two fingers moving apart
 * horizontally can be both a discrete pinch-zoom and a swipe), hence
 * [classifyGesture] returns a list, in dispatch order.
 */
sealed interface GestureEvent {
    enum class Direction { Left, Right }

    data class Tap(val fingers: Int) : GestureEvent
    data object DoubleTap : GestureEvent
    data class Swipe(val fingers: Int, val direction: Direction) : GestureEvent
    data class HoldSelect(val rect: IntRect) : GestureEvent

    /** Discrete (non-continuous) pinch zoom; [delta] is the growth ratio. */
    data class PinchZoom(val delta: Float) : GestureEvent

    /** Discrete (non-smooth) scroll; [delta] is the vertical travel in px. */
    data class VerticalScroll(val delta: Float) : GestureEvent
}
