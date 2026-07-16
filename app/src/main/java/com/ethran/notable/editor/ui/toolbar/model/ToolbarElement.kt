package com.ethran.notable.editor.ui.toolbar.model

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.editor.ToolbarAction
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.state.Shape
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.PenSetting

/**
 * Wraps the two icon sources in use — drawable resources and Feather [ImageVector]s —
 * so element specs can hold either.
 */
sealed interface IconRef {
    data class Drawable(@DrawableRes val resId: Int) : IconRef
    data class Vector(val imageVector: ImageVector) : IconRef
}

/**
 * Visibility predicate for an element. Absorbs the legacy layout conditionals
 * (hasClipboard, showResetView, notebookId): settings are passed in explicitly so the
 * model stays unit-testable without Compose snapshot state.
 *
 * Deliberate deviation from the design doc's `(ToolbarUiState) -> Boolean`: settings
 * flags are not part of ToolbarUiState. The renderer must evaluate
 * these predicates **during composition**, passing `GlobalAppSettings.current` — that is
 * a Compose snapshot-state read, so setting changes recompose the toolbar automatically
 * (colored pens / neo tools toggle live). Do not cache the result outside composition.
 */
typealias VisibleWhen = (state: ToolbarUiState, settings: AppSettings) -> Boolean

private val ALWAYS: VisibleWhen = { _, _ -> true }

/**
 * A slot in the toolbar layout: what it looks like, when it shows, when it reads as
 * selected. Elements are pure specs — all mutation flows through [ToolbarAction] and
 * the ViewModel; rendering is one generic composable (build-order step 3).
 */
sealed interface ToolbarElement {
    val id: ToolbarElementId

    /** Null only for structural elements ([DividerElement]) that render no button. */
    val icon: IconRef?
    val contentDescription: String
    val visibleWhen: VisibleWhen

    fun isSelected(state: ToolbarUiState): Boolean = false
}

/**
 * A pen instance, built from a [ToolbarPen] preset (see [ToolbarElements.penElement]).
 * Produces strokes; size/color configurable via its [StrokeSubmenuSpec]. Identity is the
 * preset id, not the base [Pen] type — two ballpens in different colors are two elements.
 */
data class PenElement(
    override val icon: IconRef,
    override val contentDescription: String,
    override val visibleWhen: VisibleWhen = ALWAYS,
    /** The [ToolbarPen] preset this button represents. */
    val presetId: String,
    /** Base type: key into stroke DB rows and the StrokeStyleRegistry. */
    val pen: Pen,
    /** The preset's color/size — fallback when absent from `ToolbarUiState.penSettings`. */
    val setting: PenSetting,
    val submenu: StrokeSubmenuSpec,
) : ToolbarElement {
    override val id: ToolbarElementId get() = ToolbarElementId.PEN

    override fun isSelected(state: ToolbarUiState): Boolean =
        (state.mode == Mode.Draw || state.mode == Mode.Line) && state.penPresetId == presetId
}

/** The single SHAPE button; its picker submenu selects among [shapes]. */
data class ShapeElement(
    override val id: ToolbarElementId,
    override val icon: IconRef,
    override val contentDescription: String,
    override val visibleWhen: VisibleWhen = ALWAYS,
    val shapes: List<Shape>,
) : ToolbarElement {
    override fun isSelected(state: ToolbarUiState): Boolean = state.mode == Mode.Line
}

/** Mode tools without stroke styles: eraser (with its submenu), select. */
data class ModeElement(
    override val id: ToolbarElementId,
    override val icon: IconRef,
    override val contentDescription: String,
    override val visibleWhen: VisibleWhen = ALWAYS,
    val mode: Mode,
    val submenu: SubmenuSpec? = null,
) : ToolbarElement {
    override fun isSelected(state: ToolbarUiState): Boolean = state.mode == mode
}

/** Stateless action buttons: undo, redo, paste, reset view, home, toolbar toggle. */
data class ActionElement(
    override val id: ToolbarElementId,
    override val icon: IconRef,
    override val contentDescription: String,
    override val visibleWhen: VisibleWhen = ALWAYS,
    val action: ToolbarAction,
) : ToolbarElement

/**
 * Elements whose rendering data can't express: the page-number text, the menu dropdown,
 * and the image picker (its activity-result launcher lives in ToolbarContent, so its
 * click routes through a local callback rather than a [ToolbarAction]).
 * Code-per-element is the exception, not the norm.
 */
data class CustomElement(
    override val id: ToolbarElementId,
    override val icon: IconRef?,
    override val contentDescription: String,
    override val visibleWhen: VisibleWhen = ALWAYS,
    val kind: CustomKind,
) : ToolbarElement

enum class CustomKind {
    PAGE_NAV, MENU, IMAGE_PICKER
}

/** Placeable vertical divider — lets users control grouping with the ordering mechanism. */
data object DividerElement : ToolbarElement {
    override val id = ToolbarElementId.DIVIDER
    override val icon: IconRef? = null
    override val contentDescription = "divider"
    override val visibleWhen: VisibleWhen = ALWAYS
}
