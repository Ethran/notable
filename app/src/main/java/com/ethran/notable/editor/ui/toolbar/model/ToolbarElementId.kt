package com.ethran.notable.editor.ui.toolbar.model

/**
 * Stable identifiers for toolbar elements. Layouts persist these **by name** (see
 * [ToolbarLayout]), so renaming an entry is a breaking change for saved layouts;
 * reordering the enum is safe.
 */
enum class ToolbarElementId {
    /** The toolbar's own open/close control — always rendered first, never part of a layout. */
    TOGGLE,

    /**
     * Shared by every pen instance ([PenElement] built from a [ToolbarPen] preset).
     * Pens are identified by preset id and appear in layouts as `"PEN:<id>"` entries —
     * a bare "PEN" name is invalid and dropped by the validator.
     */
    PEN,

    SHAPE, ERASER, SELECT, IMAGE, PASTE, RESET_VIEW,
    UNDO, REDO, PAGE_NAV, HOME, MENU,

    /** Placeable pseudo-element: a vertical divider. May appear multiple times in a layout. */
    DIVIDER;
    // future: TEXT

    companion object {
        /** Resolve a persisted name, or null for unknown names (dropped on layout load). */
        fun fromString(name: String): ToolbarElementId? =
            entries.find { it.name == name }
    }
}
