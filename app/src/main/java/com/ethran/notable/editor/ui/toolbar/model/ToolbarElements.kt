package com.ethran.notable.editor.ui.toolbar.model

import androidx.compose.ui.graphics.Color
import com.ethran.notable.R
import com.ethran.notable.editor.ToolbarAction
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.state.Shape
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.Pen
import compose.icons.FeatherIcons
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.RefreshCcw

/**
 * The registry: every placeable **static** toolbar element, keyed by id. Pen buttons are
 * not here — they are user-created [ToolbarPen] presets, resolved into [PenElement]s by
 * [ToolbarElements.resolve]. Adding a static tool = adding one entry here (plus, for
 * stroke-producing pens, a StrokeStyleRegistry entry — step 2). A unit test asserts every
 * [ToolbarElementId] except the pen sentinel resolves.
 */
object ToolbarElements {

    val all: Map<ToolbarElementId, ToolbarElement> = listOf(
        ActionElement(
            id = ToolbarElementId.TOGGLE,
            icon = IconRef.Vector(FeatherIcons.EyeOff),
            contentDescription = "close toolbar",
            action = ToolbarAction.ToggleToolbar,
        ),

        ShapeElement(
            id = ToolbarElementId.SHAPE,
            icon = IconRef.Drawable(R.drawable.line),
            contentDescription = "shape",
            shapes = listOf(Shape.LINE),
        ),
        ModeElement(
            id = ToolbarElementId.ERASER,
            icon = IconRef.Drawable(R.drawable.eraser),
            contentDescription = "eraser",
            mode = Mode.Erase,
            submenu = EraserSubmenuSpec(erasers = listOf(Eraser.PEN, Eraser.SELECT)),
        ),
        ModeElement(
            id = ToolbarElementId.SELECT,
            icon = IconRef.Drawable(R.drawable.lasso),
            contentDescription = "lasso",
            mode = Mode.Select,
        ),
        CustomElement(
            id = ToolbarElementId.IMAGE,
            icon = IconRef.Drawable(R.drawable.image),
            contentDescription = "Image picker",
            kind = CustomKind.IMAGE_PICKER,
        ),
        ActionElement(
            id = ToolbarElementId.PASTE,
            icon = IconRef.Vector(FeatherIcons.Clipboard),
            contentDescription = "paste",
            visibleWhen = { state, _ -> state.hasClipboard },
            action = ToolbarAction.Paste,
        ),
        ActionElement(
            id = ToolbarElementId.RESET_VIEW,
            icon = IconRef.Vector(FeatherIcons.RefreshCcw),
            contentDescription = "reset zoom and scroll",
            visibleWhen = { state, _ -> state.showResetView },
            action = ToolbarAction.ResetView,
        ),

        ActionElement(
            id = ToolbarElementId.UNDO,
            icon = IconRef.Drawable(R.drawable.undo),
            contentDescription = "undo",
            action = ToolbarAction.Undo,
        ),
        ActionElement(
            id = ToolbarElementId.REDO,
            icon = IconRef.Drawable(R.drawable.redo),
            contentDescription = "redo",
            action = ToolbarAction.Redo,
        ),
        CustomElement(
            id = ToolbarElementId.PAGE_NAV,
            icon = null,
            contentDescription = "page navigation",
            visibleWhen = { state, _ -> state.notebookId != null },
            kind = CustomKind.PAGE_NAV,
        ),
        ActionElement(
            id = ToolbarElementId.HOME,
            icon = IconRef.Drawable(R.drawable.home),
            contentDescription = "library",
            action = ToolbarAction.NavigateToHome,
        ),
        CustomElement(
            id = ToolbarElementId.MENU,
            icon = IconRef.Drawable(R.drawable.menu),
            contentDescription = "menu",
            kind = CustomKind.MENU,
        ),
        DividerElement,
    ).associateBy { it.id }

    fun of(id: ToolbarElementId): ToolbarElement =
        all.getValue(id)

    /** Icon for a base pen type. Legacy color variants share the plain ballpen icon —
     * the button's tint comes from the preset's color. */
    fun penIcon(pen: Pen): IconRef = IconRef.Drawable(
        when (pen) {
            Pen.BALLPEN, Pen.REDBALLPEN, Pen.GREENBALLPEN, Pen.BLUEBALLPEN -> R.drawable.ballpen
            Pen.PENCIL -> R.drawable.pencil
            Pen.BRUSH -> R.drawable.brush
            Pen.MARKER -> R.drawable.marker
            Pen.FOUNTAIN -> R.drawable.fountain
            Pen.DASHED -> R.drawable.line_dashed
        }
    )

    /** Builds the toolbar element for a pen preset. The StrokeMenu options come from the
     * preset itself — which colors/sizes appear is per-pen, edited in toolbar settings. */
    fun penElement(preset: ToolbarPen): PenElement = PenElement(
        icon = penIcon(preset.pen),
        contentDescription = preset.pen.penName,
        presetId = preset.id,
        pen = preset.pen,
        setting = preset.setting(),
        submenu = StrokeSubmenuSpec(
            sizeOptions = preset.effectiveSizeOptions().map { sizeLabel(it) to it },
            colorOptions = preset.effectiveColorOptions().map { Color(it) },
        ),
    )

    /** "3", "5", "10" — numeric labels; custom option sets have no natural S/M/L names. */
    fun sizeLabel(size: Float): String =
        if (size == size.toInt().toFloat()) size.toInt().toString() else size.toString()

    /**
     * Resolves a persisted layout entry: `"PEN:<id>"` against the preset list, anything
     * else against the static registry. Null for unknown names and deleted presets —
     * callers skip those entries.
     */
    fun resolve(name: String, pens: List<ToolbarPen>): ToolbarElement? {
        if (name.startsWith(ToolbarPen.LAYOUT_PREFIX)) {
            val presetId = name.removePrefix(ToolbarPen.LAYOUT_PREFIX)
            return pens.find { it.id == presetId }?.let(::penElement)
        }
        val id = ToolbarElementId.fromString(name) ?: return null
        return all[id]
    }

    /**
     * Collapsed-toolbar icon: the mode tool if one is selected (shape/eraser/select —
     * in Line mode the shape button wins over the pen, matching old behavior), otherwise
     * the active pen preset's icon.
     */
    fun presentlyUsedToolIcon(state: ToolbarUiState, pens: List<ToolbarPen>): IconRef {
        all.values.firstOrNull { it.isSelected(state) }?.icon?.let { return it }
        val pen = pens.find { it.id == state.penPresetId }?.pen
        // No preset matches only if the active one was deleted (or the transient DASHED
        // erase indicator) — show the dashed-line icon, mirroring the old fallback.
        return pen?.let(::penIcon) ?: IconRef.Drawable(R.drawable.line_dashed)
    }
}
