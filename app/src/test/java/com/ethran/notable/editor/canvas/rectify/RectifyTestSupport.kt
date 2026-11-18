package com.ethran.notable.editor.canvas.rectify

import com.ethran.notable.editor.utils.Pen

/**
 * Readable pen gestures over [PenStrokeRectifier], on a clock the test advances by hand.
 * Timestamps use the same arbitrary base as the gesture tests (see `T0` in PointerTrackerTest).
 */

/** A time the engine never reaches on its own, so every dwell is measured from here. */
internal const val T0 = 1_000L

/** The screen the engine clips bands to. */
internal const val SCREEN_W = 1000
internal const val SCREEN_H = 800

/** Time the band throttle needs between pushes, so a second move really reaches the panel. */
internal val PUSHER_GAP: Long = RectifyThresholds().bandMinIntervalMs + 1

/** Pen down at [x], [y] with a plain ballpoint pen, on page [pageId]. */
internal fun PenStrokeRectifier.penDown(
    x: Float = 100f,
    y: Float = 100f,
    pageId: String = "page-a",
    pen: Pen = Pen.BALLPEN,
    strokeWidthPx: Float = 6f,
    color: Int = 0xFF000000.toInt(),
) = onPenDown(Point2D(x, y), pageId, pen, strokeWidthPx, color)

/** A pen move that draws, the mode the rectify gesture is allowed from. */
internal fun PenStrokeRectifier.move(x: Float, y: Float) = onMove(Point2D(x, y), canRectify = true)

/** A pen move in a mode that must not rectify, such as the dedicated line tool. */
internal fun PenStrokeRectifier.moveWithoutRectify(x: Float, y: Float) =
    onMove(Point2D(x, y), canRectify = false)

/** A pen move [distance] past the dwell threshold, which restarts the dwell clock. */
internal fun PenStrokeRectifier.moveFar(from: Point2D, distance: Float = 200f) =
    move(from.x + distance, from.y)

/**
 * Holds the pen still at [x], [y] for [heldMs], sending a move every 50 ms as the SDK does while
 * the pen rests. The dwell needs the clock to pass [RectifyThresholds.triggerTimeMs] while the
 * position stops changing, which needs at least three moves: the first only starts the clock.
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
internal fun PenStrokeRectifier.penUp(x: Float? = null, y: Float? = null): RectifyCommit? =
    onPenUp(if (x != null && y != null) Point2D(x, y) else null)
