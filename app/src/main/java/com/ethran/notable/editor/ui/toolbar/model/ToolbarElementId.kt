package com.ethran.notable.editor.ui.toolbar.model

/**
 * Stable identifiers for toolbar elements. Layouts persist these **by name** (see
 * [ToolbarLayout]), so renaming an entry is a breaking change for saved layouts;
 * reordering the enum is safe.
 */
enum class ToolbarElementId {
    /** The toolbar's own open/close control — always rendered first, never part of a layout. */
    TOGGLE,

    PEN_BALL, PEN_RED, PEN_BLUE, PEN_GREEN,
    PEN_PENCIL, PEN_BRUSH, PEN_FOUNTAIN, PEN_MARKER,
    SHAPE, ERASER, SELECT, IMAGE, PASTE, RESET_VIEW,
    UNDO, REDO, PAGE_NAV, HOME, MENU,

    /** Placeable pseudo-element: a vertical divider. May appear multiple times in a layout. */
    DIVIDER;
    // future: TEXT, custom pen presets

    companion object {
        /** Resolve a persisted name, or null for unknown names (dropped on layout load). */
        fun fromString(name: String): ToolbarElementId? =
            entries.find { it.name == name }
    }
}
