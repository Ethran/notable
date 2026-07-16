package com.ethran.notable.editor.ui.toolbar.model

import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.PenSetting
import kotlinx.serialization.Serializable
import java.util.UUID
import android.graphics.Color as AndroidColor

/**
 * A user-created pen instance: a base [Pen] type plus its own color and size. The same
 * base type may appear multiple times (two ballpens in different colors), each as its own
 * toolbar button. The preset *is* the pen's setting — StrokeMenu edits write back to it,
 * persisted in `AppSettings.toolbarPens`.
 *
 * Presets are referenced from [ToolbarLayout] lists as `"PEN:<id>"` entries; all other
 * elements keep their [ToolbarElementId] names.
 */
@Serializable
data class ToolbarPen(
    /** Generated, stable, never reused. Layouts and the editor state reference it. */
    val id: String,
    /** Base type: which stroke style the pen produces. */
    val pen: Pen,
    val color: Int,
    val size: Float,
) {
    /** The preset's color/size as a fresh [PenSetting] (its fields are mutable). */
    fun setting(): PenSetting = PenSetting(size, color)

    /** How this preset is referenced from [ToolbarLayout] lists. */
    val layoutEntry: String get() = "$LAYOUT_PREFIX$id"

    companion object {
        const val LAYOUT_PREFIX = "PEN:"

        fun newId(): String = UUID.randomUUID().toString().take(8)

        /** Base types offered when creating a preset — legacy color variants and the
         * erase-indicator DASHED are not placeable. */
        val BASE_TYPES = listOf(Pen.BALLPEN, Pen.PENCIL, Pen.BRUSH, Pen.FOUNTAIN, Pen.MARKER)

        /**
         * Reproduces the historical eight pen buttons. Seed ids are stable so
         * [ToolbarLayout.DEFAULT] can reference them by name. The old red/blue/green
         * pens were separate [Pen] values; as presets they are plain ballpens with a
         * color — new strokes persist `pen = BALLPEN` and render identically.
         */
        val DEFAULT_PENS = listOf(
            ToolbarPen("ball", Pen.BALLPEN, AndroidColor.BLACK, 5f),
            ToolbarPen("red", Pen.BALLPEN, AndroidColor.RED, 5f),
            ToolbarPen("blue", Pen.BALLPEN, AndroidColor.BLUE, 5f),
            ToolbarPen("green", Pen.BALLPEN, AndroidColor.GREEN, 5f),
            ToolbarPen("pencil", Pen.PENCIL, AndroidColor.BLACK, 5f),
            ToolbarPen("brush", Pen.BRUSH, AndroidColor.BLACK, 5f),
            ToolbarPen("fountain", Pen.FOUNTAIN, AndroidColor.BLACK, 5f),
            ToolbarPen("marker", Pen.MARKER, AndroidColor.LTGRAY, 40f),
        )

        /**
         * Fallback pen settings keyed by preset id, derived from [DEFAULT_PENS] — the
         * single source of truth. EditorViewModel.DEFAULT_PEN_SETTINGS aliases this;
         * persisted user presets always win.
         */
        val defaultPenSettings: Map<String, PenSetting> =
            DEFAULT_PENS.associate { it.id to it.setting() }
    }
}
