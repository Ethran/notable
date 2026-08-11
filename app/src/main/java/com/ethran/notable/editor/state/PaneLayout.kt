package com.ethran.notable.editor.state

/** One pane's document: which page it shows, and the notebook that page belongs to. */
data class PaneSlot(
    val pageId: String,
    /** Null for a quick page, which belongs to no notebook. */
    val notebookId: String? = null,
)

/** Why a document may not be opened in a pane. */
sealed interface PaneRejection {

    /** Another pane already shows this exact page. Two views of one page cannot be coherent. */
    data class PageHeldElsewhere(val pageId: String) : PaneRejection

    /** Another pane already has this notebook open — ROADMAP §8. */
    data class NotebookHeldElsewhere(val notebookId: String) : PaneRejection

    /** The pane already shows it. Legal, but opening it would do nothing. */
    data object AlreadyShown : PaneRejection
}

/**
 * The documents on screen, and the rules about which combinations are allowed.
 *
 * ### Why this exists
 *
 * The same rules were encoded twice and agreed only by accident. `PaneGroup` checked that panes
 * show distinct pages, when a pane set changed. The page picker separately decided what to *offer*,
 * from a different predicate covering both distinct pages and one-notebook-per-pane. Neither
 * covered a page *turn*, which is how a page turn came to walk one notebook into both panes — the
 * picker was not involved, and the pane set had not changed, so nothing checked.
 *
 * Stating the rules once, over a value, means asking "may this be opened here?" and "is this set
 * legal?" are the same question with the same answer. It is also pure, so the rules are testable
 * without a device — which is where every one of these bugs was found (STATE-PLAN §5).
 *
 * Deliberately *not* a command reducer. That would also have to own how a page is loaded into a
 * pane, which is the path that produced four device-only bugs and is not worth rewiring for a rule
 * check. See STATE-PLAN §6 step 5.
 */
data class PaneLayout(val slots: List<PaneSlot>) {

    /**
     * Why [candidate] may not be opened in the pane at [paneIndex], or null if it may.
     *
     * Pass a null [paneIndex] for a pane that does not exist yet — splitting — where every existing
     * pane counts as another pane and nothing can be "already shown".
     *
     * A notebook the *target* pane already holds is deliberately not rejected: opening a different
     * page of the notebook you are in is a normal, useful move. What makes re-opening your own
     * notebook pointless is that its landing page is the page you are already on, which
     * [PaneRejection.AlreadyShown] catches on the page id — so callers should ask about the page a
     * notebook would actually open, not about the notebook in the abstract.
     */
    fun rejectionFor(paneIndex: Int?, candidate: PaneSlot): PaneRejection? {
        if (paneIndex != null && slots.getOrNull(paneIndex)?.pageId == candidate.pageId) {
            return PaneRejection.AlreadyShown
        }
        slots.forEachIndexed { index, slot ->
            if (index == paneIndex) return@forEachIndexed
            if (slot.pageId == candidate.pageId) {
                return PaneRejection.PageHeldElsewhere(candidate.pageId)
            }
            if (candidate.notebookId != null && slot.notebookId == candidate.notebookId) {
                return PaneRejection.NotebookHeldElsewhere(candidate.notebookId)
            }
        }
        return null
    }

    companion object {
        /**
         * Why [slots] is not a legal pane set, or null if it is.
         *
         * The whole-set form of [rejectionFor], for validating a pane set rather than a single
         * move. Slots whose notebook is not yet known are compared on page id only — a record loads
         * asynchronously, and treating "not yet known" as a conflict would reject legal layouts.
         */
        fun rejectionAmong(slots: List<PaneSlot>): PaneRejection? {
            slots.forEachIndexed { index, slot ->
                val rest = PaneLayout(slots.filterIndexed { other, _ -> other != index })
                rest.rejectionFor(paneIndex = null, candidate = slot)?.let { return it }
            }
            return null
        }
    }
}
