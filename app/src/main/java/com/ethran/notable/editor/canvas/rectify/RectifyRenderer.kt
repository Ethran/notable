package com.ethran.notable.editor.canvas.rectify

/**
 * What the rectify engine needs from the panel and the pen SDK — nothing more.
 *
 * Keeping the surface, the EPD and `TouchHelper` behind this port is what lets the dwell and band
 * logic be tested without a device. The only implementation is `OnyxRectifyRenderer` inside
 * `OnyxInputHandler`, which drives `CanvasRefreshManager.drawOverFirmware` and the SDK.
 *
 * All methods are called from the pen thread, synchronously: the band is pushed mid-gesture, and a
 * post would land after the SDK has moved on.
 */
interface RectifyRenderer {

    /**
     * Blits the page bitmap over [area] to bury whatever the firmware's raw-drawing layer left
     * there. The adapter remembers the region pushed and wipes just that on [wipeBand]; a full
     * screen is what a scroll does, and it is the only thing that clears ink the firmware put down
     * before this gesture.
     *
     * @param area the screen to clear, before the first band so the band is not painted straight
     *   back out.
     */
    fun clearFirmwareLayer(area: RectBounds)

    /**
     * Pushes the band from [a] to [b] over [dirty], returning the area actually pushed so the
     * engine can widen the next push from it, or null when nothing reached the panel — which the
     * engine reads as "this gesture cannot rectify".
     */
    fun drawBand(a: Point2D, b: Point2D, spec: BandPaintSpec, dirty: RectBounds): RectBounds?

    /**
     * Wipes the band the adapter last pushed, restoring the page bitmap underneath. Does nothing
     * when nothing was pushed. Called when a rectify is given up on.
     */
    fun wipeBand()

    /**
     * The firmware's live-drawing *render*, which stays separate from its input.
     *
     * Turned off for the gesture so the firmware stops drawing its own wobbly line over the band.
     * Input stays on: the heavier toggle would drop the rest of the gesture. Restored on pen-up and
     * on abandon.
     */
    var rawDrawingRenderEnabled: Boolean
}
