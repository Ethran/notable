package com.ethran.notable.editor.state

/**
 * Drawing mode for the editor.
 */
enum class Mode {
    Draw, Erase, Select, Line
}

/**
 * Shapes the SHAPE tool can draw. Only LINE exists today (backed by [Mode.Line]);
 * RECT/ELLIPSE/ARROW are future entries — the shape-picker submenu grows automatically.
 */
enum class Shape {
    LINE
}

/**
 * Placement mode for selection operations.
 * If state is Move then applySelectionDisplace() will delete original strokes and images.
 */
enum class PlacementMode {
    Move, Paste
}
