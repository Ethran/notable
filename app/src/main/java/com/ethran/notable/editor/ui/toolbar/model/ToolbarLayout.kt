package com.ethran.notable.editor.ui.toolbar.model

import kotlinx.serialization.Serializable

/**
 * The user's toolbar layout: which elements appear, in what order, in which of the two
 * physical zones. Global (lives in AppSettings, step 4), not per-notebook.
 *
 * Entries serialize as **strings, not enum ordinals**: static elements by
 * [ToolbarElementId] name, pen presets as `"PEN:<preset id>"` (see [ToolbarPen]). Unknown
 * names are dropped on load (a layout exported from a newer app version still imports),
 * and reordering [ToolbarElementId] can never corrupt saved layouts. An element absent
 * from both lists is simply hidden.
 */
@Serializable
data class ToolbarLayout(
    /** Left zone: scrolls horizontally, ordered. */
    val scrollable: List<String>,
    /** Right zone: pinned, never scrolls. */
    val pinned: List<String>,
) {

    /**
     * Sanitizes a layout on load/import:
     * - drops names that resolve to no [ToolbarElementId], and `"PEN:<id>"` entries whose
     *   preset is absent from [pens] (deleted, or from another device's export);
     * - drops [ToolbarElementId.TOGGLE] (structural, always rendered first) and the bare
     *   [ToolbarElementId.PEN] sentinel (pens are only placeable as `"PEN:<id>"`);
     * - drops duplicates across both zones (first occurrence wins), except
     *   [ToolbarElementId.DIVIDER], which may repeat freely;
     * - enforces the single invariant: [ToolbarElementId.MENU] must be present somewhere;
     *   if missing, it is appended to [pinned].
     *
     * Everything else — including hiding elements by omission — is the user's business.
     */
    fun validated(pens: List<ToolbarPen>): ToolbarLayout {
        val seen = mutableSetOf<String>()

        fun sanitize(names: List<String>): List<String> = names.mapNotNull { name ->
            if (name.startsWith(ToolbarPen.LAYOUT_PREFIX)) {
                val presetId = name.removePrefix(ToolbarPen.LAYOUT_PREFIX)
                if (pens.none { it.id == presetId }) return@mapNotNull null
                if (!seen.add(name)) return@mapNotNull null
                return@mapNotNull name
            }
            val id = ToolbarElementId.fromString(name) ?: return@mapNotNull null
            if (id == ToolbarElementId.TOGGLE || id == ToolbarElementId.PEN) return@mapNotNull null
            if (id != ToolbarElementId.DIVIDER && !seen.add(id.name)) return@mapNotNull null
            id.name
        }

        val cleanScrollable = sanitize(scrollable)
        var cleanPinned = sanitize(pinned)
        if (ToolbarElementId.MENU.name !in seen) {
            cleanPinned = cleanPinned + ToolbarElementId.MENU.name
        }
        return ToolbarLayout(cleanScrollable, cleanPinned)
    }

    companion object {
        /** References the stable seed ids of [ToolbarPen.DEFAULT_PENS]. */
        val DEFAULT = ToolbarLayout(
            scrollable = listOf(
                "PEN:ball", "PEN:red", "PEN:blue", "PEN:green", "PEN:pencil", "PEN:brush",
                "PEN:fountain", "SHAPE", "DIVIDER", "PEN:marker", "DIVIDER", "ERASER",
                "DIVIDER", "SELECT", "DIVIDER", "IMAGE", "DIVIDER", "PASTE", "RESET_VIEW",
            ),
            pinned = listOf(
                "DIVIDER", "UNDO", "REDO", "DIVIDER", "PAGE_NAV", "HOME", "DIVIDER", "MENU",
            ),
        )
    }
}
