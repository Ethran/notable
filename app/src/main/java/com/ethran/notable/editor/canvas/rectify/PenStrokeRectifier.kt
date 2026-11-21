package com.ethran.notable.editor.canvas.rectify

import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.rawInputMaxPressure

/**
 * Turns a pen gesture that pauses into a straight line, without help from the SDK or a panel: the
 * firmware's wobbly live track is replaced by a band from the pen-down point to the pen, and
 * [onPenUp] hands back a [RectifyCommit] for the adapter to draw.
 *
 * Time comes from [now] and the panel from [renderer], so a JVM test can drive a gesture point by
 * point. All state here belongs to exactly one gesture, and no SDK `TouchPoint` survives one.
 */
class PenStrokeRectifier(
    private val now: () -> Long,
    private val renderer: RectifyRenderer,
    private val thresholds: RectifyThresholdsPx,
    private val surfaceSize: () -> Pair<Int, Int> = { 0 to 0 },
) {
    /** True between entering rectify and pen-up. */
    var isActive: Boolean = false
        private set

    /** A, the pen-down point. Kept because the SDK's own copy is recycled before pen-up. */
    private var pointA: Point2D? = null

    /** The pen's previous move, the reference the next dwell is measured from. */
    private var previousMove: Point2D? = null

    /** When the pen first came to rest; null while it is still travelling. */
    private var dwellStartedAt: Long? = null

    /** B, the latest move of the gesture, the line's far end at commit. */
    private var lastMove: Point2D? = null

    /** Where B was at the last push, reference for the move threshold. */
    private var lastBandDrawnAt: Point2D? = null

    /** When the band last reached the panel, reference for the interval throttle. */
    private var lastBandDrawTime = 0L

    /** The screen area the band covers so far, widened by each push. */
    private var bandRect: RectBounds? = null

    private var gesturePageId: String = ""

    /** Taken once per gesture, so a toolbar change mid-stroke cannot split the band. */
    private var bandPaint: BandPaintSpec? = null

    /** The gesture's running mean pressure, raw as the SDK reported it. */
    private var pressureMean = 0f
    private var pressureSamples = 0

    /**
     * The pen went down at [a] on [pageId]. A null [a] means the SDK reported no position, so
     * nothing can be rectified: there is no A to start a line from.
     */
    fun onPenDown(
        a: Point2D?,
        pageId: String,
        pen: Pen,
        strokeWidthPx: Float,
        strokeWidth: Float,
        color: Int,
    ) {
        gesturePageId = pageId
        pointA = a
        pressureMean = 0f
        pressureSamples = 0
        bandPaint = rectifyBandPaint(pen, color, strokeWidthPx, strokeWidth)
        a?.pressure?.let { foldPressure(it) }
        bandRect = null
        lastMove = null
        lastBandDrawnAt = null
        lastBandDrawTime = 0L
        clearDwellDetection()
    }

    /**
     * The pen moved to [b]. While rectifying this widens the band toward the pen, throttled;
     * otherwise it measures the dwell. [canRectify] keeps the dedicated line tool on its own gesture.
     */
    fun onMove(b: Point2D, canRectify: Boolean) {
        // Folded for every move, so the travel before the dwell counts too.
        foldPressure(b.pressure)

        if (isActive) {
            // No A means no line to draw: hand the gesture back to the freehand path.
            val a = pointA ?: run {
                abandon()
                return
            }
            lastMove = b
            if (shouldPushBand(a, b)) {
                lastBandDrawTime = now()
                drawBand(a, b)
            }
            return
        }

        val previous = previousMove
        val a = pointA
        // The line's length so far is A to the pen, not A to the previous sample: measuring it
        // against the previous sample latches the gesture shut, because a stroke whose first sample
        // starts inside the floor could then never clear it — the early return below is what kept
        // previousMove from ever advancing.
        val longEnough = a != null && a.distanceTo(b) >= thresholds.distanceABThresholdPx

        if (previous != null && longEnough && b.distanceTo(previous) < thresholds.distanceThresholdPx) {
            val started = dwellStartedAt
            if (started == null) {
                dwellStartedAt = now()
                // previousMove stays put while a rest is being timed, so drifting out of the rest
                // breaks the dwell rather than re-timing it.
                return
            }
            if (canRectify && now() - started > thresholds.triggerTimeMs) enterRectifyMode(a, b)
            return
        }

        previousMove = b
        dwellStartedAt = null
    }

    /**
     * The pen lifted at [upPoint]. Returns what to commit, or null when the gesture was not
     * rectifying and should be drawn freehand. Restores the firmware's live drawing either way.
     */
    fun onPenUp(upPoint: Point2D?): RectifyCommit? {
        if (!isActive) {
            clearDwellDetection()
            return null
        }
        isActive = false
        renderer.rawDrawingRenderEnabled = true

        val a = pointA ?: return null
        val raw = lastMove ?: upPoint ?: a
        val b = snapBand(a, raw, thresholds)
        val dirty = bandRect
        val pressure = pressureMean
        clearGestureState()
        return RectifyCommit(
            a = a,
            b = b,
            pageId = gesturePageId,
            dirty = dirty,
            pressure = pressure,
        )
    }

    /** Gives up on the gesture and wipes the band off the panel. */
    fun abandon() {
        if (!isActive) return
        isActive = false
        lastMove = null
        lastBandDrawnAt = null
        pointA = null
        bandPaint = null
        pressureMean = 0f
        pressureSamples = 0
        renderer.rawDrawingRenderEnabled = true
        renderer.wipeBand()
        bandRect = null
    }

    /**
     * Disarms the dwell detector. It must not survive its gesture: a next stroke starting on the
     * previous stroke's last point would read as at rest from its very first move.
     */
    private fun clearDwellDetection() {
        previousMove = null
        dwellStartedAt = null
    }

    /** Drops everything belonging to the finished gesture, leaving the dwell detector clear. */
    private fun clearGestureState() {
        previousMove = null
        dwellStartedAt = null
        lastMove = null
        lastBandDrawnAt = null
        bandRect = null
        pointA = null
        bandPaint = null
        pressureMean = 0f
        pressureSamples = 0
    }

    private fun foldPressure(reading: Float) {
        pressureMean = foldBandPressure(
            mean = pressureMean,
            count = pressureSamples,
            reading = reading,
            maxPressure = rawInputMaxPressure,
        )
        if (reading > 0f) pressureSamples++
    }

    private fun enterRectifyMode(a: Point2D, b: Point2D) {
        // The dwell keeps firing while the pen rests; re-entering would clear the band area and
        // re-time the throttle, so every move would push a fresh EPD transaction.
        if (isActive) return

        isActive = true
        lastMove = b

        // Render off, input on: the heavier toggle would drop the rest of the gesture.
        renderer.rawDrawingRenderEnabled = false

        val (width, height) = surfaceSize()
        renderer.clearFirmwareLayer(RectBounds.ofSize(width, height))
        drawBand(a, b)
        // Nothing reached the panel, so there is no band to commit. Give up rather than let the
        // raw wobble through as a line.
        if (bandRect == null) {
            isActive = false
            renderer.rawDrawingRenderEnabled = true
        }
    }

    /** True once the pen has moved enough since the last push, and enough time has passed. */
    private fun shouldPushBand(a: Point2D, b: Point2D): Boolean {
        val reference = lastBandDrawnAt ?: a
        val moved = bandRect == null ||
            snappedEnd(a, b).distanceTo(reference) > thresholds.bandMinMovePx
        return moved && now() - lastBandDrawTime > thresholds.bandMinIntervalMs
    }

    /** [b] rotated onto an axis guide when one is near, as the panel shows it. */
    private fun snappedEnd(a: Point2D, b: Point2D): Point2D = snapBand(a, b, thresholds)

    private fun drawBand(a: Point2D, b: Point2D) {
        val spec = (bandPaint ?: return).copy(pressure = pressureMean)
        val end = snappedEnd(a, b)
        val dirty = bandBounds(bandRect, a, end, spec.strokeWidthPx)
        val pushed = renderer.drawBand(a, end, spec, dirty)
        if (pushed != null) {
            bandRect = pushed
            lastBandDrawnAt = end
        }
    }

    /**
     * The screen area a band from [a] to [b] covers, unioned with [previous] so one push wipes an
     * earlier band rather than leaving it behind, and clipped to the screen — a band off-screen
     * gives an empty rect, which the adapter refuses to push.
     */
    private fun bandBounds(
        previous: RectBounds?,
        a: Point2D,
        b: Point2D,
        strokeWidth: Float,
    ): RectBounds {
        val padding = (strokeWidth / 2f).toInt() + thresholds.bandPaddingPx
        val next = RectBounds(
            minOf(a.x, b.x).toInt() - padding,
            minOf(a.y, b.y).toInt() - padding,
            maxOf(a.x, b.x).toInt() + padding,
            maxOf(a.y, b.y).toInt() + padding,
        )
        val rect = (previous ?: RectBounds.EMPTY).union(next)
        val sorted = rect.sorted()
        val (width, height) = surfaceSize()
        return sorted.intersect(RectBounds.ofSize(width, height)) ?: RectBounds.EMPTY
    }
}
