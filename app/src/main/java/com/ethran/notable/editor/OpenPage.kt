package com.ethran.notable.editor

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.getBackgroundType
import com.ethran.notable.data.model.BackgroundType
import io.shipbook.shipbooksdk.ShipBook

/**
 * Immutable snapshot of the page a [PageView] displays: its id, a copy of its [Page] record, and
 * its position and neighbors in the notebook.
 *
 * [PageView] holds the current snapshot in one atomic reference and replaces it whole: with an
 * unloaded snapshot when it changes page, then with the result of [load]. [withLoaded] drops a
 * load that finishes after the view has moved to another page, so [pageId] and [record] always
 * describe the same page.
 *
 * Records are read by id. Only the owning [PageView] changes which page it shows, unlike the page
 * in front in [PageDataManager], which is whichever page any view last passed to
 * [PageDataManager.setPage].
 */
data class OpenPage(
    val pageId: String,
    /** The page record. Null before [load], or if the page is not in the database. */
    val record: Page? = null,
    /** Position within the notebook, or -1 for a loose page or before [load]. */
    val pageNumber: Int = -1,
    /** The next and previous pages in the notebook, next first; empty for a loose page or before [load]. */
    val neighborIds: List<String> = emptyList(),
) {
    val backgroundName: String
        get() = record?.background ?: "blank"

    val backgroundType: BackgroundType?
        get() = record?.getBackgroundType()

    /** Whether scroll and zoom are allowed. A cover image is a fixed page and must not transform. */
    val isTransformationAllowed: Boolean
        get() = backgroundType != BackgroundType.CoverImage

    /** [loaded] if it is a snapshot of this page, otherwise this one unchanged. */
    fun withLoaded(loaded: OpenPage): OpenPage = if (loaded.pageId == pageId) loaded else this

    companion object {
        private val log = ShipBook.getLogger("OpenPage")

        /**
         * Snapshot of [pageId] from its already-read [record]; looks up its position and neighbors
         * in the notebook.
         */
        suspend fun load(appRepository: AppRepository, pageId: String, record: Page?): OpenPage {
            if (record == null) {
                log.e("Page($pageId) not found")
                return OpenPage(pageId)
            }
            val pageIds = record.notebookId
                ?.let { appRepository.bookRepository.getById(it)?.pageIds }
                .orEmpty()
            val pageNumber = pageIds.indexOf(pageId)
            val neighborIds = if (pageNumber == -1) emptyList() else listOfNotNull(
                pageIds.getOrNull(pageNumber + 1), pageIds.getOrNull(pageNumber - 1)
            )
            return OpenPage(pageId, record, pageNumber, neighborIds)
        }
    }
}
