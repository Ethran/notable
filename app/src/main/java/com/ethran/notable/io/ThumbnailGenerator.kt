package com.ethran.notable.io

import android.content.Context
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.di.IoDispatcher
import com.ethran.notable.editor.utils.PreviewSaveMode
import com.ethran.notable.editor.utils.THUMBNAIL_WIDTH
import com.ethran.notable.editor.utils.getThumbnailFile
import com.ethran.notable.editor.utils.savePageThumbnail
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class ThumbnailEnsureResult {
    GENERATED,
    UP_TO_DATE,
    PAGE_NOT_FOUND
}


const val thumbnailGeneratorStaleMs = 3600000 // 1h

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ThumbnailGeneratorEntryPoint {
    fun thumbnailGenerator(): ThumbnailGenerator
}

/**
 * Responsible for generating and caching thumbnails for pages.
 *
 * Uses a [Mutex] and [CompletableDeferred] to prevent redundant concurrent generation
 * of the same thumbnail. Staleness is determined by comparing page modification time
 * and scroll position against stored metadata.
 */
@Singleton
class ThumbnailGenerator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pageContentRenderer: PageContentRenderer,
    private val pageRepository: PageRepository,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val log = ShipBook.getLogger("ThumbnailGenerator")
    private val inFlightLock = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<ThumbnailEnsureResult>>()

    private val _thumbnailUpdated = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /**
     * Flow of page IDs whose thumbnails have been updated (generated or refreshed).
     */
    val thumbnailUpdated = _thumbnailUpdated.asSharedFlow()


    /**
     * Checks if a thumbnail is up to date and generates it if necessary.
     * Returns immediately if a generation for the same [pageId] is already in progress.
     */
    suspend fun ensureThumbnail(pageId: String, mode: PreviewSaveMode): ThumbnailEnsureResult {
        val page = withContext(ioDispatcher) { pageRepository.getById(pageId) }
            ?: return ThumbnailEnsureResult.PAGE_NOT_FOUND

        if (!isThumbnailStale(page)) {
            return ThumbnailEnsureResult.UP_TO_DATE
        }

        val existing = inFlightLock.withLock { inFlight[pageId] }
        if (existing != null) {
            return existing.await()
        }

        val marker = CompletableDeferred<ThumbnailEnsureResult>()
        val acquired = inFlightLock.withLock {
            inFlight.putIfAbsent(
                pageId,
                marker
            ) == null // it will return null if there wasn't any value
        }

        if (!acquired) {
            return inFlightLock.withLock { inFlight[pageId] }?.await()
                ?: ThumbnailEnsureResult.UP_TO_DATE
        }

        try {
            val result = generate(page, mode)
            if (result == ThumbnailEnsureResult.GENERATED) {
                _thumbnailUpdated.tryEmit(pageId)
            }
            marker.complete(result)
            return result
        } catch (t: Throwable) {
            marker.completeExceptionally(t)
            throw t
        } finally {
            inFlightLock.withLock { inFlight.remove(pageId) }
        }
    }


    private suspend fun generate(
        page: Page, mode: PreviewSaveMode
    ): ThumbnailEnsureResult {
        val bitmap = pageContentRenderer.renderPageBitmap(
            pageId = page.id,
            target = RenderTarget.Thumbnail(
                maxWidthPx = THUMBNAIL_WIDTH,
                maxHeightPx = null
            )
        )
        bitmap.useAndRecycle { rendered ->
            savePageThumbnail(context, rendered, page.id, mode)
        }
        log.d("Thumbnail generated for pageId=${page.id}")
        return ThumbnailEnsureResult.GENERATED
    }

    private suspend fun isThumbnailStale(page: Page): Boolean = withContext(ioDispatcher) {
        return@withContext true
        val thumbFile = getThumbnailFile(context, page.id)
        if (!thumbFile.exists()) return@withContext true

        if (page.updatedAt.time + thumbnailGeneratorStaleMs > thumbFile.lastModified()) return@withContext true
        else return@withContext false
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
