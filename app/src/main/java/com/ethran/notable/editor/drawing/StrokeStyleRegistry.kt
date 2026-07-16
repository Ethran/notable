package com.ethran.notable.editor.drawing

import com.ethran.notable.editor.utils.Pen

/**
 * Which Onyx pen mechanism renders a stroke, plus the per-pen parameters that mechanism
 * takes. This is the data the renderer's old `when(stroke.pen)` hardcoded; the renderer
 * is now a thin executor of these values. Wrapper mechanics that never vary per pen
 * (erase flags, matrix plumbing) stay in the executor.
 */
sealed interface OnyxStrokeStyle {
    /** Plain canvas path (drawBallPenStroke) — no pressure, no texture. */
    data object BallPen : OnyxStrokeStyle

    /** NeoFountainPenV2Wrapper — pressure-sensitive width. */
    data object Fountain : OnyxStrokeStyle

    /** NeoBrushPenWrapper. */
    data object Brush : OnyxStrokeStyle

    /** NeoMarkerPenWrapper — flat translucent band. */
    data object Marker : OnyxStrokeStyle

    /** NeoCharcoalPenWrapper — textured pencil. */
    data class Charcoal(val tiltEnabled: Boolean) : OnyxStrokeStyle
}

/**
 * How a persisted stroke becomes pixels, per rendering backend. The app-backend side
 * (perfect-freehand `OutlineOptions` presets) is added in Phase 2 of that plan; until
 * then this carries only the Onyx style.
 */
data class StrokeStyle(
    val onyx: OnyxStrokeStyle,
)

/**
 * Pen -> StrokeStyle, keyed by the `stroke.pen` persisted in the DB. Serves the
 * renderers (page load, scroll, undo — no toolbar exists in those code paths); the
 * toolbar references pens by the same enum but never reads this registry.
 *
 * Adding a stroke-producing pen = one entry here (plus its toolbar element); the
 * renderers need no edits. [StrokeStyleRegistryTest] fails if an entry is missing.
 */
object StrokeStyleRegistry {

    private val styles: Map<Pen, StrokeStyle> = mapOf(
        Pen.BALLPEN to StrokeStyle(OnyxStrokeStyle.BallPen),
        Pen.REDBALLPEN to StrokeStyle(OnyxStrokeStyle.BallPen),
        Pen.GREENBALLPEN to StrokeStyle(OnyxStrokeStyle.BallPen),
        Pen.BLUEBALLPEN to StrokeStyle(OnyxStrokeStyle.BallPen),
        Pen.FOUNTAIN to StrokeStyle(OnyxStrokeStyle.Fountain),
        Pen.BRUSH to StrokeStyle(OnyxStrokeStyle.Brush),
        Pen.MARKER to StrokeStyle(OnyxStrokeStyle.Marker),
        Pen.PENCIL to StrokeStyle(OnyxStrokeStyle.Charcoal(tiltEnabled = true)),
        // Pen.DASHED intentionally absent: erase-indicator only, never persisted as ink.
    )

    /** Null for pens that produce no persisted ink (DASHED). */
    fun forPen(pen: Pen): StrokeStyle? = styles[pen]
}
