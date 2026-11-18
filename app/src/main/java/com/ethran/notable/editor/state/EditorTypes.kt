package com.ethran.notable.editor.state

/**
 * Drawing mode for the editor.
 */
enum class Mode {
    Draw, Erase, Select, Shape
}

/**
 * Placeholder for the Shape tool. Nothing draws yet; straight lines come from the pen-dwell
 * rectify gesture in Draw mode, so LINE is not listed here.
 */
enum class Shape {
    SQUARE
}

/**
 * Placement mode for selection operations.
 * If state is Move then applySelectionDisplace() will delete original strokes and images.
 */
enum class PlacementMode {
    Move, Paste
}
