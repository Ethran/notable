package com.ethran.notable.editor.canvas.rectify

/**
 * The tunables that decide when a held pen becomes a straight line, and how often the band is
 * pushed to the panel. Defaults are the shipped values; a test overrides them to exercise a
 * threshold without depending on what production happens to be set to.
 *
 * @param triggerTimeMs how long the pen must stay within [distanceThresholdPx] before the gesture
 *   rectifies. Long enough that resting mid-stroke is deliberate, short enough to feel immediate.
 * @param distanceThresholdPx movement below this counts as "not moving". Smaller means hand
 *   jitter can trigger a rectify; larger means the pen must be held nearly still.
 * @param bandMinIntervalMs minimum gap between panel pushes. Each push is one EPD transaction, so
 *   pushing every pen-move point hangs the panel.
 * @param bandMinMovePx how far the pen must move since the last push before it is worth pushing
 *   again.
 * @param bandPaddingPx slack added around the band on top of half the stroke width, so an
 *   anti-aliased round cap is not clipped at the edge of the pushed region.
 */
data class RectifyThresholds(
    val triggerTimeMs: Long = 600,
    val distanceThresholdPx: Float = 20f,
    val bandMinIntervalMs: Long = 120,
    val bandMinMovePx: Float = 18f,
    val bandPaddingPx: Int = 4,
)
