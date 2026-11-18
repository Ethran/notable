package com.ethran.notable.editor.canvas.rectify

/**
 * A [RectifyRenderer] that records instead of drawing.
 *
 * The order of calls matters as much as their arguments: the firmware layer must be cleared before
 * the first band, or the band is painted straight back out. So every call is appended to [calls] in
 * one shared log.
 *
 * @param pushArea what [drawBand] reports as pushed; null makes every push fail, which is how the
 *   "this gesture cannot rectify" path is exercised.
 */
class FakeRectifyRenderer(
    var pushArea: RectBounds? = RectBounds(0, 0, 500, 500),
) : RectifyRenderer {

    /** Every call in order: [ClearFirmwareLayer], [DrawBand] or [WipeBand]. */
    val calls: MutableList<Call> = mutableListOf()

    override var rawDrawingRenderEnabled: Boolean = true

    /** Regions passed to [clearFirmwareLayer], in order. */
    val cleared: List<RectBounds>
        get() = calls.filterIsInstance<ClearFirmwareLayer>().map { it.area }

    /** Bands passed to [drawBand], in order. */
    val bands: List<DrawBand>
        get() = calls.filterIsInstance<DrawBand>()

    val wipes: Int
        get() = calls.count { it is WipeBand }

    sealed interface Call

    data class ClearFirmwareLayer(val area: RectBounds) : Call

    data class DrawBand(
        val a: Point2D,
        val b: Point2D,
        val spec: BandPaintSpec,
        val dirty: RectBounds,
    ) : Call

    data object WipeBand : Call

    override fun clearFirmwareLayer(area: RectBounds) {
        calls.add(ClearFirmwareLayer(area))
    }

    override fun drawBand(
        a: Point2D,
        b: Point2D,
        spec: BandPaintSpec,
        dirty: RectBounds,
    ): RectBounds? {
        calls.add(DrawBand(a, b, spec, dirty))
        return pushArea
    }

    override fun wipeBand() {
        calls.add(WipeBand)
    }
}
