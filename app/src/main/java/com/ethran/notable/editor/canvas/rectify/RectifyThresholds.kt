package com.ethran.notable.editor.canvas.rectify

import kotlin.math.roundToInt

/**
 * Tunables for the hold-still-becomes-a-line gesture, in the units they are tuned in: distances in
 * millimetres of physical screen, times in ms, angles in degrees. A pixel count is a different
 * physical distance on every panel, so [resolve] turns these into the pixels the engine works in.
 *
 * Defaults are tuned in millimetres so they hold on any panel: at the ~8.9 px/mm of a 10.3" screen
 * 1.5 mm is ~13 px, on a 7.8" at ~11.8 px/mm it is ~18 px. A test overrides them and resolves at
 * `pxPerMm = 1f`, so its literals read as pixels.
 */
data class RectifyThresholds(
    /** How long the pen must hold still before the gesture rectifies. */
    val triggerTimeMs: Long = 600,
    /**
     * Minimum A–B distance, else it reads as a tap rather than a line. High enough to shrug off a
     * pen-down jiggle, low enough that a dash or a tick — 4 to 7 mm — still rectifies.
     */
    val distanceABThresholdMm: Float = 1.8f,
    /** Movement below this counts as "not moving". Stays above the digitizer's rest jitter. */
    val distanceThresholdMm: Float = 1.5f,
    /** Minimum gap between panel pushes; each push is one EPD transaction. */
    val bandMinIntervalMs: Long = 120,
    /**
     * Movement since the last push that makes another one worth it. Kept above the dwell threshold:
     * drift can accumulate to it even while every individual move reads as at rest, and a push every
     * [bandMinIntervalMs] during a held stroke is what hangs the panel.
     */
    val bandMinMoveMm: Float = 2.5f,
    /** Slack around the band, on top of half the stroke, so a round cap is not clipped. */
    val bandPaddingMm: Float = 0.35f,
    val snapEnabled: Boolean = true,
    /** Axis spacing the line leans toward; 45 gives 0°, 45°, 90°, … */
    val snapStepDeg: Int = 45,
    /** How far from an axis a line may sit and still be pulled onto it. */
    val snapToleranceDeg: Int = 5,
    /** How much of the miss is closed up inside the tolerance; 1 lands exactly on the guide. */
    val snapStrength: Float = 1f,
) {

    /** Distances as screen pixels at [pxPerMm]. Only the padding rounds — it is added to an Int rect. */
    fun resolve(pxPerMm: Float): RectifyThresholdsPx = RectifyThresholdsPx(
        triggerTimeMs = triggerTimeMs,
        distanceABThresholdPx = distanceABThresholdMm * pxPerMm,
        distanceThresholdPx = distanceThresholdMm * pxPerMm,
        bandMinIntervalMs = bandMinIntervalMs,
        bandMinMovePx = bandMinMoveMm * pxPerMm,
        bandPaddingPx = (bandPaddingMm * pxPerMm).roundToInt(),
        snapEnabled = snapEnabled,
        snapStepDeg = snapStepDeg,
        snapToleranceDeg = snapToleranceDeg,
        snapStrength = snapStrength,
    )
}

/**
 * [RectifyThresholds] with distances in screen pixels, the only unit [PenStrokeRectifier] works in:
 * points arrive from the digitizer in pixels and band bounds clip to a pixel screen.
 */
data class RectifyThresholdsPx(
    val triggerTimeMs: Long,
    val distanceABThresholdPx: Float,
    val distanceThresholdPx: Float,
    val bandMinIntervalMs: Long,
    val bandMinMovePx: Float,
    val bandPaddingPx: Int,
    val snapEnabled: Boolean,
    val snapStepDeg: Int,
    val snapToleranceDeg: Int,
    val snapStrength: Float,
)
