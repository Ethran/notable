package com.ethran.notable.editor.ui.toolbar.model

import kotlinx.serialization.Serializable

/**
 * The user's toolbar layout: which elements appear, in what order, in which of the two
 * physical zones. Global (lives in AppSettings, step 4), not per-notebook.
 *
 * Ids serialize as **strings, not enum ordinals**: unknown names are dropped on load
 * (a layout exported from a newer app version still imports), and reordering
 * [ToolbarElementId] can never corrupt saved layouts. An element absent from both lists
 * is simply hidden.
 */
@Serializable
data class ToolbarLayout(
    /** Left zone: scrolls horizontally, ordered. */
    val scrollable: List<String>,
    /** Right zone: pinned, never scrolls. */
    val pinned: List<String>,
) {

    /** [scrollable] resolved to ids, in order. Only meaningful on a [validated] layout. */
    fun scrollableIds(): List<ToolbarElementId> =
        scrollable.mapNotNull { ToolbarElementId.fromString(it) }

    /** [pinned] resolved to ids, in order. Only meaningful on a [validated] layout. */
    fun pinnedIds(): List<ToolbarElementId> =
        pinned.mapNotNull { ToolbarElementId.fromString(it) }

    /**
     * Sanitizes a layout on load/import:
     * - drops names that resolve to no [ToolbarElementId];
     * - drops [ToolbarElementId.TOGGLE] — it is structural, always rendered first;
     * - drops duplicates across both zones (first occurrence wins), except
     *   [ToolbarElementId.DIVIDER], which may repeat freely;
     * - enforces the single invariant: [ToolbarElementId.MENU] must be present somewhere;
     *   if missing, it is appended to [pinned].
     *
     * Everything else — including hiding elements by omission — is the user's business.
     */
    fun validated(): ToolbarLayout {
        val seen = mutableSetOf<ToolbarElementId>()

        fun sanitize(names: List<String>): List<String> = names.mapNotNull { name ->
            val id = ToolbarElementId.fromString(name) ?: return@mapNotNull null
            if (id == ToolbarElementId.TOGGLE) return@mapNotNull null
            if (id != ToolbarElementId.DIVIDER && !seen.add(id)) return@mapNotNull null
            id.name
        }

        val cleanScrollable = sanitize(scrollable)
        var cleanPinned = sanitize(pinned)
        if (ToolbarElementId.MENU !in seen) {
            cleanPinned = cleanPinned + ToolbarElementId.MENU.name
        }
        return ToolbarLayout(cleanScrollable, cleanPinned)
    }

    companion object {
        val DEFAULT = ToolbarLayout(
            scrollable = listOf(
                "PEN_BALL", "PEN_RED", "PEN_BLUE", "PEN_GREEN", "PEN_PENCIL", "PEN_BRUSH",
                "PEN_FOUNTAIN", "SHAPE", "DIVIDER", "PEN_MARKER", "DIVIDER", "ERASER",
                "DIVIDER", "SELECT", "DIVIDER", "IMAGE", "DIVIDER", "PASTE", "RESET_VIEW",
            ),
            pinned = listOf(
                "DIVIDER", "UNDO", "REDO", "DIVIDER", "PAGE_NAV", "HOME", "DIVIDER", "MENU",
            ),
        )
    }
}
