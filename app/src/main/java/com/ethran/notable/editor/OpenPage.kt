package com.ethran.notable.editor

import com.ethran.notable.data.CachedBackground
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.getBackgroundType
import com.ethran.notable.data.model.BackgroundType
import io.shipbook.shipbooksdk.ShipBook

/**
 * One page, open in one view: which page it is, and everything derived from the page record itself.
 *
 * ### Why this exists
 *
 * `PageDataManager` holds a single `pageFromDb`, and every notion of "the current page" is derived
 * from it:
 *
 * ```kotlin
 * private val currentPage: String get() = pageFromDb?.id.orEmpty()
 * ```
 *
 * So a view that asked the manager *which page am I showing?* got an app-wide answer. With one
 * editor that is always right and therefore invisible; with two it means both views report the same
 * page, read the same strokes, and overwrite each other's `setPage`.
 *
 * This is the fourth time the same shape has appeared — the window buffer, the viewport, the signal
 * bus, and now page identity were all single-instance state living in a shared object. The fix is
 * the same each time: **the per-view object owns its live state, while the shared singleton keeps
 * cache and persistence keyed by id.**
 *
 * ### Division of labour
 *
 * Anything derived from the page *record* — background name and type, whether transforms are
 * allowed, the page number within its notebook — is answered here, from this view's own [entity].
 * Strokes, images, bitmaps and backgrounds stay in [PageDataManager], which already keys them all
 * by page id and pools backgrounds across pages.
 *
 * [PageDataManager.setPage] is still called for the *foreground* page, because the manager
 * legitimately needs to know which page to prefetch neighbours for and which to protect from
 * eviction. That is a statement about focus, not about identity.
 */
class OpenPage(
    private val pageDataManager: PageDataManager,
    initialPageId: String,
) {
    private val log = ShipBook.getLogger("OpenPage")

    /** Which page this view shows. Owned here, not read back from a shared "current page". */
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
     * Mirrors `PageDataManager.isTransformationAllowedForCurrentPage`, but answers for *this* page.
     */
    val isTransformationAllowed: Boolean
        get() = when (entity?.backgroundType) {
            "coverImage" -> false
            else -> true
        }

    /** This page's background bitmap, from the shared pool. */
    fun background(): CachedBackground = pageDataManager.getBackground(pageId)

    /** Load the page record and its position. Call after construction and after [changeTo]. */
    suspend fun load() {
        // Pin before loading: an unpinned page is evictable, and the foreground pin covers only
        // one view. Without this the inactive pane's strokes are dropped under memory pressure.
        pageDataManager.pinPage(pageId)
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
        pageDataManager.unpinPage(pageId)
        pageId = newPageId
        entity = null
        pageNumber = -1
        load()
    }

    /** Release this view's pin. Call when the view goes away, or its page stays resident forever. */
    fun close() {
        pageDataManager.unpinPage(pageId)
    }

    /** Re-read the record without changing which page this is — after an edit to its metadata. */
    suspend fun refresh() {
        entity = pageDataManager.getPageRecord(pageId)
        log.i("Refreshed page $pageId, background: ${entity?.background}")
    }
}
