package com.ethran.notable.editor.ui.toolbar.model

import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import com.ethran.notable.R
import com.ethran.notable.editor.ToolbarAction
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.state.Shape
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.PenSetting
import compose.icons.FeatherIcons
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.RefreshCcw

// Size presets, formerly hardcoded in ToolbarContent.
val SIZES_STROKES_DEFAULT = listOf("S" to 3f, "M" to 5f, "L" to 10f, "XL" to 20f)
val SIZES_MARKER_DEFAULT = listOf("M" to 25f, "L" to 40f, "XL" to 60f, "XXL" to 80f)

// Color presets, formerly hardcoded in PenToolbarButton. StrokeMenu substitutes its
// grayscale palette in monochrome mode.
val COLORS_DEFAULT = listOf(
    Color.Red, Color.Green, Color.Blue, Color.Cyan, Color.Magenta,
    Color.Yellow, Color.Gray, Color.DarkGray, Color.Black,
)

private val STROKE_SUBMENU = StrokeSubmenuSpec(SIZES_STROKES_DEFAULT, COLORS_DEFAULT)

private fun pen(
    id: ToolbarElementId,
    pen: Pen,
    icon: Int,
    defaultColor: Int = AndroidColor.BLACK,
    visibleWhen: VisibleWhen = { _, _ -> true },
) = PenElement(
    id = id,
    icon = IconRef.Drawable(icon),
    contentDescription = pen.penName,
    visibleWhen = visibleWhen,
    pen = pen,
    defaultSetting = PenSetting(5f, defaultColor),
    submenu = STROKE_SUBMENU,
)

/**
 * The registry: every placeable toolbar element, keyed by id. Adding a tool = adding one
 * entry here (plus, for stroke-producing pens, a StrokeStyleRegistry entry — step 2).
 * A unit test asserts every [ToolbarElementId] resolves.
 */
object ToolbarElements {

    val all: Map<ToolbarElementId, ToolbarElement> = listOf(
        ActionElement(
            id = ToolbarElementId.TOGGLE,
            icon = IconRef.Vector(FeatherIcons.EyeOff),
            contentDescription = "close toolbar",
            action = ToolbarAction.ToggleToolbar,
        ),

        pen(ToolbarElementId.PEN_BALL, Pen.BALLPEN, R.drawable.ballpen),
        pen(
            ToolbarElementId.PEN_RED, Pen.REDBALLPEN, R.drawable.ballpenred,
            defaultColor = AndroidColor.RED,
            visibleWhen = { _, settings -> !settings.monochromeMode },
        ),
        pen(
            ToolbarElementId.PEN_BLUE, Pen.BLUEBALLPEN, R.drawable.ballpenblue,
            defaultColor = AndroidColor.BLUE,
            visibleWhen = { _, settings -> !settings.monochromeMode },
        ),
        pen(
            ToolbarElementId.PEN_GREEN, Pen.GREENBALLPEN, R.drawable.ballpengreen,
            defaultColor = AndroidColor.GREEN,
            visibleWhen = { _, settings -> !settings.monochromeMode },
        ),
        pen(
            ToolbarElementId.PEN_PENCIL, Pen.PENCIL, R.drawable.pencil,
            visibleWhen = { _, settings -> settings.neoTools },
        ),
        pen(
            ToolbarElementId.PEN_BRUSH, Pen.BRUSH, R.drawable.brush,
            visibleWhen = { _, settings -> settings.neoTools },
        ),
        pen(ToolbarElementId.PEN_FOUNTAIN, Pen.FOUNTAIN, R.drawable.fountain),
        PenElement(
            id = ToolbarElementId.PEN_MARKER,
            icon = IconRef.Drawable(R.drawable.marker),
            contentDescription = Pen.MARKER.penName,
            pen = Pen.MARKER,
            defaultSetting = PenSetting(40f, AndroidColor.LTGRAY),
            submenu = StrokeSubmenuSpec(SIZES_MARKER_DEFAULT, COLORS_DEFAULT),
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

    /**
     * Canonical per-pen defaults, derived from the specs — the single source of truth.
     * EditorViewModel.DEFAULT_PEN_SETTINGS aliases this; do not re-declare values there.
     * Copies keep spec instances isolated (PenSetting fields are mutable). These are
     * fallbacks for pens absent from persisted settings — never re-seed user values.
     */
    val defaultPenSettings: Map<String, PenSetting> =
        all.values.filterIsInstance<PenElement>()
            .associate { it.pen.penName to it.defaultSetting.copy() }

    /**
     * Collapsed-toolbar icon: the element matching the active mode/pen. In Line mode both
     * the shape button and the active pen report selected — the mode tool wins, matching
     * the old presentlyUsedToolIcon() behavior.
     */
    fun presentlyUsedToolIcon(state: ToolbarUiState): IconRef? {
        val selected = all.values.filter { it.isSelected(state) }
        val element = selected.firstOrNull { it !is PenElement } ?: selected.firstOrNull()
        // No element matches only in Draw mode with an element-less pen. Today that is
        // exactly Pen.DASHED (erase-indicator only, never a toolbar pen) — mirror the old
        // exhaustive when(pen) by showing the dashed-line icon.
        return element?.icon ?: IconRef.Drawable(R.drawable.line_dashed)
    }
}
