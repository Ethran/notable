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
    PENCIL("PENCIL"),          // charcoal V1 — labelled "Charcoal (classic)" in the UI
    BRUSH("BRUSH"),
    MARKER("MARKER"),
    FOUNTAIN("FOUNTAIN"),
    DASHED("DASHED"),
    // Added pens (see docs/onyx-sdk/onyx-pen-styles-catalog.md):
    CHARCOAL("CHARCOAL"),      // charcoal V2 texture — labelled "Charcoal"
    CALLIGRAPHY("CALLIGRAPHY");// NeoSquarePen, fixed +45° nib


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
        Pen.CHARCOAL -> StrokeStyle.CHARCOAL_V2
        Pen.CALLIGRAPHY -> StrokeStyle.SQUARE_PEN
    }
}


@Serializable
data class PenSetting(
    var strokeSize: Float,
    //TODO: Rename to strokeColor
    var color: Int
)
