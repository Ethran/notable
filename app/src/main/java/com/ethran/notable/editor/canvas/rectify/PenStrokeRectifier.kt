package com.ethran.notable.editor.canvas.rectify

import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.rawInputMaxPressure

/**
 * Turns a pen gesture that pauses into a straight line, without help from the SDK or a panel.
 *
 * When the pen stops moving for [RectifyThresholds.triggerTimeMs], the gesture enters rectify: the
 * firmware's wobbly live track is replaced by a straight band from the pen-down point to the pen,
 * which follows the pen until it lifts. [onPenUp] then hands back a [RectifyCommit] for the adapter
 * to draw.
 *
 * This holds the whole rule — dwell detection, band bounds, push throttling, commit and abandon —
 * and nothing else. Time comes from [now] and the panel from [renderer], so a JVM test can drive a
 * gesture point by point and assert what was pushed and what was committed. Compare
 * `PointerTracker`, which is isolated from gestures the same way.
 *
 * Everything here is state for exactly one gesture. The SDK hands over `TouchPoint`s that it
 * recycles before pen-up, so they are converted to [Point2D] on arrival and none are kept across a
 * gesture.
 *
 * @param now the current time in ms, on the same clock the caller uses for the band throttle.
 * @param thresholds the dwell and throttle tunables.
 * @param renderer the panel and pen-render control.
 * @param surfaceSize the screen the band may be drawn on, used to clip the pushed region.
 */
class PenStrokeRectifier(
    private val now: () -> Long,
    private val renderer: RectifyRenderer,
    private val thresholds: RectifyThresholds = RectifyThresholds(),
    private val surfaceSize: () -> Pair<Int, Int> = { 0 to 0 },
) {
    /** True while the gesture is rectifying, i.e. between entering rectify and pen-up. */
    var isActive: Boolean = false
        private set

    /** A, the pen-down point. Kept because the SDK's own copy is recycled before pen-up. */
    private var pointA: Point2D? = null

    /**
     * The pen's previous move, to measure the next one against. Null between gestures, so a dwell
     * is never measured from the previous gesture's last point — see [clearDwellDetection].
     */
    private var previousMove: Point2D? = null

    /** When the pen first came to rest within [RectifyThresholds.distanceThresholdPx]; null while
     *  the pen is still travelling. */
    private var dwellStartedAt: Long? = null

    /** B, the latest move of the gesture, used as the line's far end at commit. */
    private var lastMove: Point2D? = null

    /** Where B was when the band last reached the panel, the reference for the move threshold. */
    private var lastBandDrawnAt: Point2D? = null

    /** When the band last reached the panel, the reference for the interval throttle. */
    private var lastBandDrawTime = 0L

    /** The screen area the band covers so far, widened by each push. */
    private var bandRect: RectBounds? = null

    /** The page the pen went down on, carried to the commit. */
    private var gesturePageId: String = ""

    /** The band's paint rule, taken once per gesture so a toolbar change mid-stroke cannot split it. */
    private var bandPaint: BandPaintSpec? = null

    /** The gesture's running mean pressure, raw as the SDK reported it, and how many readings it holds. */
    private var pressureMean = 0f
    private var pressureSamples = 0

    /**
     * The pen went down at [a] on [pageId], with the active pen described by [pen] and [color], its
     * nib [strokeWidth] and its on-screen extent [strokeWidthPx] (that width scaled by page zoom).
     *
     * A null [a] means the SDK reported no pen-down position. Nothing can then be rectified: there
     * is no A for the line to start from, so the gesture stays freehand rather than committing a
     * line from an invented point.
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
     * The pen moved to [b].
     *
     * While rectifying, this widens the band toward the pen, throttled. Otherwise it measures the
     * dwell, and a pause long enough starts one — but only while [canRectify] reports a mode that
     * draws freehand, so the dedicated line tool keeps its own gesture.
     */
    fun onMove(b: Point2D, canRectify: Boolean) {
        // Folded for every move, not just the rectifying ones, so the travel before the dwell counts.
        foldPressure(b.pressure)

        if (isActive) {
            // No A means no line to draw: give the gesture back to the freehand path.
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

        // Calculate distance between the 2 points
        previousMove?.let { previous ->
            if (b.distanceTo(previous) < thresholds.distanceThresholdPx) {
                val started = dwellStartedAt
                if (started == null) {
                    dwellStartedAt = now()
                    return
                }
                val a = pointA
                if (canRectify && a != null && now() - started > thresholds.triggerTimeMs) {
                    enterRectifyMode(a = a, b = b)
                }
                return
            }
        }
        // Update pointB
        previousMove = b
        dwellStartedAt = null
    }

    /**
     * The pen lifted at [upPoint], ending the gesture.
     *
     * Returns what to commit, or null when the gesture was not rectifying and should be drawn
     * freehand instead. Restores the firmware's live drawing either way.
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

    /**
     * Gives up on rectifying the current gesture and wipes what the band left on the panel,
     * restoring it to the page bitmap underneath.
     */
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
     * Disarms the dwell detector, on pen-down and on pen-up.
     *
     * A dwell is only counted while the pen stays in one spot, so it must not survive the gesture
     * that measured it: [previousMove] holds the previous stroke's last point, so a next stroke
     * starting at that point — or jittering around it — would sit within the distance threshold from
     * its very first move, and [RectifyThresholds.triggerTimeMs] later be taken for a dwell and turn
     * into a rectify. The SDK also recycles its points, so none of them may be kept across a
     * gesture.
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

    /** Folds one pressure reading into the gesture's mean. */
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
        // Already rectifying: the dwell keeps firing while the pen rests, and re-entering would
        // clear the band area and re-time the throttle, so each move would push a fresh EPD
        // transaction and hang the panel.
        if (isActive) return

        isActive = true
        lastMove = b

        // Render off, input on: the heavier toggle would drop the rest of the gesture.
        renderer.rawDrawingRenderEnabled = false

        val (width, height) = surfaceSize()
        renderer.clearFirmwareLayer(RectBounds.ofSize(width, height))
        drawBand(a, b)
        // Nothing reached the panel, so there is no band to follow and nothing to commit. Give up
        // rather than let the raw wobble through as a line, and hand the firmware's drawing back:
        // abandon() cannot do it, being guarded on an active rectify with a band to wipe.
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

    /** The band's far end as the panel shows it: [b] rotated onto an axis guide when one is near. */
    private fun snappedEnd(a: Point2D, b: Point2D): Point2D = snapBand(a, b, thresholds)

    /** Pushes the band at the gesture's current mean pressure, and remembers the pushed area. */
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
     * The screen area a band from [a] to [b] covers, widened by the stroke and by what earlier
     * bands already covered, clipped to the screen.
     *
     * [previous] is the area already pushed, so widening rather than replacing is what lets one
     * push wipe an earlier band instead of leaving it behind. The padding is half the stroke plus
     * [RectifyThresholds.bandPaddingPx], enough for the round cap. A band off-screen gives an empty
     * rect, which the adapter refuses to push.
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
        // Built the same way production does it: union into an empty-at-origin rect, sort, then
        // clip to the screen — and when the clip misses, hand back the empty-at-origin rect, which
        // is what makes the adapter's isEmpty check refuse to push it.
        val rect = (previous ?: RectBounds.EMPTY).union(next)
        val sorted = rect.sorted()
        val (width, height) = surfaceSize()
        return sorted.intersect(RectBounds.ofSize(width, height)) ?: RectBounds.EMPTY
    }
}
