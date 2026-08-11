package com.ethran.notable.editor.state

/**
 * Where a page sits: which page it is, which notebook it belongs to, and its position in it.
 *
 * ### Why this is one value rather than several fields
 *
 * These facts are all functions of the page id, and they were previously stored separately —
 * `notebookId`, `pageId`, `isBookActive`, `pageNumberInfo` and `currentPageNumber` on the toolbar
 * state, plus a `bookId` on the ViewModel taken from the editor's *route*. Because they were
 * written by different code on different triggers, they could disagree, and did:
 *
 * - The route's notebook and the page's notebook were two stores for one fact. After splitting, a
 *   page turn in the second pane walked the *first* pane's notebook into it, because navigation
 *   read the route's copy (ROADMAP §8, STATE-PLAN §2.3).
 * - A page count written only when a notebook had two or more pages survived a switch to a shorter
 *   one, reading "0/3" on a one-page notebook (STATE-PLAN §2.2).
 *
 * Resolved together and replaced together, neither is expressible. There is no index without the
 * count it indexes into, and no notebook id that some other component believes differently.
 *
 * Derived state, so nothing here is persisted or edited — [Companion.of] is the only way to build
 * one from a record, and it is the only place the rules live.
 */
data class PageLocation(
    val pageId: String,
    /** Null for a quick page, which belongs to no notebook. */
    val notebookId: String? = null,
    /** Zero-based position within the notebook. Zero for a quick page. */
    val index: Int = 0,
    /** Pages in the notebook. One for a quick page, which is its own whole document. */
    val count: Int = 1,
) {
    /** Whether page navigation applies at all: a quick page has nothing to page through. */
    val isInNotebook: Boolean get() = notebookId != null

    /**
     * The page counter's text — "3/12" in a notebook, "1/1" for a quick page.
     *
     * One-based for display; [index] stays zero-based because that is what callers index with.
     */
    val label: String get() = if (isInNotebook) "${index + 1}/$count" else "1/1"

    companion object {
        /**
         * Build a location from a page's notebook and that notebook's page list.
         *
         * Pass a null [notebookId] for a quick page. [pageIds] is the notebook's ordered pages; a
         * page missing from its own notebook's list is a data inconsistency rather than a crash, so
         * it lands at index 0 — `fixNotebook` is what repairs that.
         */
        fun of(pageId: String, notebookId: String?, pageIds: List<String>): PageLocation {
            if (notebookId == null) return PageLocation(pageId)
            return PageLocation(
                pageId = pageId,
                notebookId = notebookId,
                index = pageIds.indexOf(pageId).coerceAtLeast(0),
                count = pageIds.size.coerceAtLeast(1),
            )
        }
    }
}
