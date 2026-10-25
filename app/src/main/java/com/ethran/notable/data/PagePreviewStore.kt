package com.ethran.notable.data

import android.content.Context
import com.ethran.notable.editor.utils.canSavePreview
import com.ethran.notable.editor.utils.deletePagePreviews
import com.ethran.notable.editor.utils.saveHQPagePreview
import com.ethran.notable.editor.utils.savePageThumbnail
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Writes the preview and thumbnail of each page the editor leaves, from the screen bitmap it left
 * on [PageDataManager]'s shelf. Only a view unzoomed and at the page's left edge is written; its
 * vertical scroll is the one the page opens at.
 *
 * It waits [SAVE_DELAY_MS] after the first page left since its last writes, then writes every page
 * left meanwhile, one at a time, each once however often it was left. So a page the user goes
 * straight back to is usually shown from its bitmap rather than wait for the write; the view has
 * taken the bitmap off the shelf by then, and nothing is written. While a page is written its
 * bitmap is off the shelf, so no view draws into it. The files of a page deleted meanwhile are
 * removed again.
 *
 * A singleton, so the previews of the pages left last are written after the editor closes too.
 */
@Singleton
class PagePreviewStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pageDataManager: PageDataManager,
    private val appRepository: AppRepository,
) {
    private val log = ShipBook.getLogger("PagePreviewStore")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Pages left since the last writes, in the order they were left. Guarded by itself.
    private val requests = LinkedHashSet<String>()
    private val requested = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { writeRequestedPreviews() }
    }

    /** Asks for the preview of [pageId], whose bitmap the view has just put on the shelf. */
    fun request(pageId: String) {
        synchronized(requests) { requests += pageId }
        requested.trySend(Unit)
    }

    private suspend fun writeRequestedPreviews() {
        while (true) {
            requested.receive()
            delay(SAVE_DELAY_MS.milliseconds)
            val pageIds = synchronized(requests) { requests.toList().also { requests.clear() } }
            for (pageId in pageIds) write(pageId)
        }
    }

    private suspend fun write(pageId: String) {
        val (bitmap, viewport) = pageDataManager.takeBitmapToSave(pageId) ?: return
        // Off the shelf, the bitmap is drawn into no more: it shows the page as it is now.
        val capturedAtMs = System.currentTimeMillis()
        try {
            if (canSavePreview(viewport)) {
                saveHQPagePreview(context, bitmap, pageId, viewport, capturedAtMs)
                savePageThumbnail(context, bitmap, pageId, capturedAtMs = capturedAtMs)
            }
        } catch (e: Exception) {
            log.e("Writing the preview of page $pageId failed: ${e.message}", e)
        } finally {
            pageDataManager.putBackBitmap(pageId, bitmap, viewport)
        }
        // Deleting a page removes its files after removing its record, so whichever runs second
        // removes them.
        try {
            if (appRepository.pageRepository.getById(pageId) == null) deletePagePreviews(context.filesDir, pageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("Checking whether page $pageId still exists failed: ${e.message}")
        }
    }

    private companion object {
        const val SAVE_DELAY_MS = 2_000L
    }
}
