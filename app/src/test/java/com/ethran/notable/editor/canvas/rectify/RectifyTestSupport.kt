package com.ethran.notable.editor.canvas.rectify

import com.ethran.notable.editor.utils.Pen

// Readable pen gestures over [PenStrokeRectifier], on a clock the test advances by hand.

/** A time the engine never reaches on its own, so every dwell is measured from here. */
internal const val T0 = 1_000L

/** The screen the engine clips bands to. */
internal const val SCREEN_W = 1000
internal const val SCREEN_H = 800

/** Time the band throttle needs between pushes, so a second move really reaches the panel. */
internal val PUSHER_GAP: Long = RectifyThresholds().bandMinIntervalMs + 1

internal fun PenStrokeRectifier.penDown(
    x: Float = 100f,
    y: Float = 100f,
    pageId: String = "page-a",
    pen: Pen = Pen.BALLPEN,
    strokeWidthPx: Float = 6f,
    strokeWidth: Float = strokeWidthPx,
    color: Int = 0xFF000000.toInt(),
) = onPenDown(Point2D(x, y), pageId, pen, strokeWidthPx, strokeWidth, color)

/** A pen move that draws — the mode the rectify gesture is allowed from. */
internal fun PenStrokeRectifier.move(x: Float, y: Float) = onMove(Point2D(x, y), canRectify = true)

internal fun PenStrokeRectifier.move(point: Point2D) = onMove(point, canRectify = true)

internal fun PenStrokeRectifier.moveWithoutRectify(x: Float, y: Float) =
    onMove(Point2D(x, y), canRectify = false)

/** A pen move [distance] past the dwell threshold, which restarts the dwell clock. */
internal fun PenStrokeRectifier.moveFar(from: Point2D, distance: Float = 200f) =
    move(from.x + distance, from.y)

/**
 * Holds the pen still at [x], [y] until [heldMs], sending a move every [stepMs] as the SDK does.
 * Needs at least three moves: the first only starts the dwell clock.
 */
internal fun PenStrokeRectifier.hold(
    clock: FakeClock,
    x: Float = 100f,
    y: Float = 100f,
    heldMs: Long = T0 + 700L,
    stepMs: Long = 50L,
) {
    var at = clock.now
    while (at < heldMs) {
        at += stepMs
        clock.now = at
        move(x, y)
    }
}

/** Pen up at [x], [y], or with no point when the SDK reports none. */
internal fun PenStrokeRectifier.penUp(
    x: Float? = null,
    y: Float? = null,
    pressure: Float = 0f,
): RectifyCommit? =
    onPenUp(if (x != null && y != null) Point2D(x, y, pressure) else null)
