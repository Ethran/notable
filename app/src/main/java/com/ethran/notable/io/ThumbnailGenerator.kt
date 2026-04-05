package com.ethran.notable.io

import android.content.Context
import com.ethran.notable.editor.utils.getThumbnailFile
import com.ethran.notable.editor.utils.getThumbnailTargetWidthPx
import com.ethran.notable.editor.utils.persistBitmapThumbnail
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThumbnailGenerator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pageContentRenderer: PageContentRenderer
) {
    private val log = ShipBook.getLogger("ThumbnailGenerator")
    private val inFlightLock = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<Unit>>()

    suspend fun ensureThumbnail(pageId: String) {
        if (thumbnailExists(pageId)) return

        val existing = inFlightLock.withLock { inFlight[pageId] }
        if (existing != null) {
            existing.await()
            return
        }

        val marker = CompletableDeferred<Unit>()
        val acquired = inFlightLock.withLock {
            if (inFlight.containsKey(pageId)) {
                false
            } else {
                inFlight[pageId] = marker
                true
            }
        }

        if (!acquired) {
            inFlightLock.withLock { inFlight[pageId] }?.await()
            return
        }

        try {
            generateIfNeeded(pageId)
            marker.complete(Unit)
        } catch (t: Throwable) {
            marker.completeExceptionally(t)
            throw t
        } finally {
            inFlightLock.withLock { inFlight.remove(pageId) }
        }
    }

    private suspend fun generateIfNeeded(pageId: String) {
        if (thumbnailExists(pageId)) return

        val targetWidth = getThumbnailTargetWidthPx()
        val bitmap = pageContentRenderer.renderPageBitmap(
            pageId = pageId,
            target = RenderTarget.Thumbnail(
                maxWidthPx = targetWidth,
                maxHeightPx = Int.MAX_VALUE
            )
        )

        bitmap.useAndRecycle { rendered ->
            persistBitmapThumbnail(context, rendered, pageId)
        }

        log.d("Thumbnail ensured for pageId=$pageId")
    }

    private suspend fun thumbnailExists(pageId: String): Boolean = withContext(Dispatchers.IO) {
        getThumbnailFile(context, pageId).exists()
    }

    private inline fun android.graphics.Bitmap.useAndRecycle(block: (android.graphics.Bitmap) -> Unit) {
        try {
            block(this)
        } finally {
            if (!isRecycled) {
                recycle()
            }
        }
    }
}

