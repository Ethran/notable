package com.ethran.notable.editor

import com.ethran.notable.data.CachedBackground
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.getBackgroundType
import com.ethran.notable.data.model.BackgroundType
import io.shipbook.shipbooksdk.ShipBook

/**
 * The page a [PageView] shows, and its page record.
 *
 * Each view keeps its own, because [PageDataManager] tracks one current page for the whole app:
 * whichever a view last passed to [PageDataManager.setPage]. During navigation between pages the
 * old editor is disposed after the new one has set its page, so the manager cannot tell a view
 * which page it is showing. Strokes, images and backgrounds stay in [PageDataManager], keyed by id.
 */
class OpenPage(
    private val pageDataManager: PageDataManager,
    initialPageId: String,
) {
    private val log = ShipBook.getLogger("OpenPage")

    var pageId: String = initialPageId
        private set

    /** The page record. Null until [load], or if the page was deleted underneath us. */
    var entity: Page? = null
        private set

    /** Position within the notebook, or -1 for a loose page or before [load]. */
    var pageNumber: Int = -1
        private set

    val notebookId: String?
        get() = entity?.notebookId

    val backgroundName: String
        get() = entity?.background ?: "blank"

    val backgroundType: BackgroundType?
        get() = entity?.getBackgroundType()

    /**
     * Whether scroll and zoom are allowed. A cover image is a fixed page and must not transform.
     * Same rule as `PageDataManager.isTransformationAllowedForCurrentPage`, for this view's page.
     */
    val isTransformationAllowed: Boolean
        get() = when (entity?.backgroundType) {
            "coverImage" -> false
            else -> true
        }

    /** This page's background bitmap, from the shared pool. */
    fun background(): CachedBackground = pageDataManager.getBackground(pageId)

    /** Load the page record and its position. Call once after construction; [changeTo] reloads. */
    suspend fun load() {
        val loaded = pageDataManager.getPageRecord(pageId)
        entity = loaded
        if (loaded == null) {
            log.e("Page($pageId) not found")
            pageNumber = -1
            return
        }
        pageNumber = loaded.notebookId
            ?.let { pageDataManager.getPageNumber(it, pageId) }
            ?: -1
    }

    /** Point this view at a different page and reload the record. */
    suspend fun changeTo(newPageId: String) {
        if (newPageId == pageId && entity != null) return
        pageId = newPageId
        entity = null
        pageNumber = -1
        load()
    }

    /** Re-read the record without changing which page this is, after an edit to its metadata. */
    suspend fun refresh() {
        entity = pageDataManager.getPageRecord(pageId)
        log.i("Refreshed page $pageId, background: ${entity?.background}")
    }
}
