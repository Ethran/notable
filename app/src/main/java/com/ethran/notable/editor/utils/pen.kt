package com.ethran.notable.editor.utils


import com.onyx.android.sdk.pen.style.StrokeStyle
import kotlinx.serialization.Serializable


enum class Pen(val penName: String) {
    BALLPEN("BALLPEN"),
    // RED/GREEN/BLUE ballpens are legacy: since toolbar pens became ToolbarPen presets
    // (a colored pen is a BALLPEN preset), nothing creates strokes with these values.
    // DO NOT REMOVE — existing DB stroke rows and Xopp/sync imports persist these names,
    // and StrokeStyleRegistry/penIcon must keep resolving them to render old notebooks.
    REDBALLPEN("REDBALLPEN"),
    GREENBALLPEN("GREENBALLPEN"),
    BLUEBALLPEN("BLUEBALLPEN"),
    PENCIL("PENCIL"),
    BRUSH("BRUSH"),
    MARKER("MARKER"),
    FOUNTAIN("FOUNTAIN"),
    DASHED("DASHED");

    companion object {
        fun fromString(name: String?): Pen {
            return entries.find { it.penName.equals(name, ignoreCase = true) } ?: BALLPEN
        }
    }
}

fun penToStroke(pen: Pen): Int {
    return when (pen) {
        Pen.BALLPEN -> StrokeStyle.PENCIL
        Pen.REDBALLPEN -> StrokeStyle.PENCIL
        Pen.GREENBALLPEN -> StrokeStyle.PENCIL
        Pen.BLUEBALLPEN -> StrokeStyle.PENCIL
        Pen.PENCIL -> StrokeStyle.CHARCOAL
        Pen.BRUSH -> StrokeStyle.NEO_BRUSH
        Pen.MARKER -> StrokeStyle.MARKER
        Pen.FOUNTAIN -> StrokeStyle.FOUNTAIN
        Pen.DASHED -> StrokeStyle.DASH
    }
}


@Serializable
data class PenSetting(
    var strokeSize: Float,
    //TODO: Rename to strokeColor
    var color: Int
)
