package com.ethran.notable.editor.ui.toolbar.model

import androidx.compose.ui.graphics.Color
import com.ethran.notable.editor.state.Shape
import com.ethran.notable.editor.utils.Eraser

/**
 * Declarative description of an element's popup submenu. The generic element renderer
 * (ToolbarElementView, build-order step 3) interprets these; specs hold data only.
 */
sealed interface SubmenuSpec

/** Size/color picker for stroke-producing tools, rendered by the existing StrokeMenu. */
data class StrokeSubmenuSpec(
    val sizeOptions: List<Pair<String, Float>>,
    val colorOptions: List<Color>,
) : SubmenuSpec

/** Picker listing the shapes a [ShapeElement] can draw. */
data class ShapePickerSpec(
    val shapes: List<Shape>,
) : SubmenuSpec

/** Eraser type selection plus the scribble-to-erase toggle. */
data class EraserSubmenuSpec(
    val erasers: List<Eraser>,
) : SubmenuSpec
