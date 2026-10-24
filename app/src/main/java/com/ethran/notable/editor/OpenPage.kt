package com.ethran.notable.editor

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.getBackgroundType
import com.ethran.notable.data.model.BackgroundType
import io.shipbook.shipbooksdk.ShipBook

/**
 * Immutable snapshot of the page a [PageView] displays: its id, a copy of its [Page] record, and
 * its position in the notebook.
 *
 * [PageView] holds the current snapshot in one atomic reference and replaces it whole: with an
 * unloaded snapshot when it changes page, then with the result of [load]. [withLoaded] drops a
 * load that finishes after the view has moved to another page, so [pageId] and [record] always
 * describe the same page.
 */
data class OpenPage(
    val pageId: String,
    /** The page record. Null before [load], or if the page is not in the database. */
    val record: Page? = null,
    /** Position within the notebook, or -1 for a loose page or before [load]. */
    val pageNumber: Int = -1,
) {
    val backgroundName: String
        get() = record?.background ?: "blank"

    val backgroundType: BackgroundType?
        get() = record?.getBackgroundType()

    /**
     * Whether scroll and zoom are allowed: only once [load] has read the page's record, so its
     * viewport is in place, and never on a cover image, which is a fixed page.
     */
    val isTransformationAllowed: Boolean
        get() = record != null && backgroundType != BackgroundType.CoverImage

    /** [loaded] if it is a snapshot of this page, otherwise this one unchanged. */
    fun withLoaded(loaded: OpenPage): OpenPage = if (loaded.pageId == pageId) loaded else this

    companion object {
        private val log = ShipBook.getLogger("OpenPage")

        /**
         * Snapshot of [pageId] from its already-read [record]; looks up its position in the
         * notebook.
         */
        suspend fun load(appRepository: AppRepository, pageId: String, record: Page?): OpenPage {
            if (record == null) {
                log.e("Page($pageId) not found")
                return OpenPage(pageId)
            }
            val pageNumber = record.notebookId
                ?.let { appRepository.bookRepository.getById(it)?.pageIds?.indexOf(pageId) }
                ?: -1
            return OpenPage(pageId, record, pageNumber)
        }
    }
}
