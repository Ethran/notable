package com.ethran.notable.io

import android.content.Context
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.di.IoDispatcher
import com.ethran.notable.editor.utils.getThumbnailFile
import com.ethran.notable.editor.utils.getThumbnailTargetWidthPx
import com.ethran.notable.editor.utils.persistBitmapThumbnail
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class ThumbnailEnsureResult {
    GENERATED,
    UP_TO_DATE,
    PAGE_NOT_FOUND
}

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

    // Map of pageId to its last known generation timestamp (signature)
    private val _thumbnailSignatures = MutableStateFlow<Map<String, Long>>(emptyMap())
    val thumbnailSignatures = _thumbnailSignatures.asStateFlow()

    /**
     * Checks if a thumbnail is up to date and generates it if necessary.
     * Returns immediately if a generation for the same [pageId] is already in progress.
     */
    suspend fun ensureThumbnail(pageId: String): ThumbnailEnsureResult {
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
            if (inFlight.containsKey(pageId)) {
                false
            } else {
                inFlight[pageId] = marker
                true
            }
        }

        if (!acquired) {
            return inFlightLock.withLock { inFlight[pageId] }?.await()
                ?: ThumbnailEnsureResult.UP_TO_DATE
        }

        try {
            val result = generateIfNeeded(page)
            if (result == ThumbnailEnsureResult.GENERATED) {
                val now = System.currentTimeMillis()
                _thumbnailSignatures.update { it + (pageId to now) }
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

    /**
     * Returns the persistent signature (last modified time) for a thumbnail.
     * Performs IO.
     */
    suspend fun getThumbnailSignature(pageId: String): Long = withContext(ioDispatcher) {
        val file = getThumbnailFile(context, pageId)
        if (file.exists()) file.lastModified() else 0L
    }

    private suspend fun generateIfNeeded(page: Page): ThumbnailEnsureResult {
        if (!isThumbnailStale(page)) return ThumbnailEnsureResult.UP_TO_DATE

        val targetWidth = getThumbnailTargetWidthPx()
        val bitmap = pageContentRenderer.renderPageBitmap(
            pageId = page.id,
            target = RenderTarget.Thumbnail(
                maxWidthPx = targetWidth,
                maxHeightPx = Int.MAX_VALUE
            )
        )

        bitmap.useAndRecycle { rendered ->
            persistBitmapThumbnail(context, rendered, page.id)
        }
        writeThumbnailMeta(page)

        log.d("Thumbnail ensured for pageId=${page.id}")
        return ThumbnailEnsureResult.GENERATED
    }

    private suspend fun isThumbnailStale(page: Page): Boolean = withContext(ioDispatcher) {
        val thumbFile = getThumbnailFile(context, page.id)
        if (!thumbFile.exists()) return@withContext true

        if (page.updatedAt.time > thumbFile.lastModified()) return@withContext true

        val meta = readThumbnailMeta(page.id) ?: return@withContext true
        meta.updatedAtMs < page.updatedAt.time || meta.scroll != page.scroll
    }

    private suspend fun writeThumbnailMeta(page: Page) = withContext(ioDispatcher) {
        val metaFile = thumbnailMetaFile(page.id)
        metaFile.parentFile?.mkdirs()
        metaFile.writeText("${page.updatedAt.time}|${page.scroll}")
    }

    private fun readThumbnailMeta(pageId: String): ThumbnailMeta? {
        val metaFile = thumbnailMetaFile(pageId)
        if (!metaFile.exists()) return null

        val parts = metaFile.readText().split("|")
        if (parts.size != 2) return null
        val updatedAtMs = parts[0].toLongOrNull() ?: return null
        val scroll = parts[1].toIntOrNull() ?: return null
        return ThumbnailMeta(updatedAtMs = updatedAtMs, scroll = scroll)
    }

    private fun thumbnailMetaFile(pageId: String): File {
        val thumbFile = getThumbnailFile(context, pageId)
        return File(thumbFile.parentFile, "$pageId.meta")
    }

    private data class ThumbnailMeta(
        val updatedAtMs: Long,
        val scroll: Int
    )

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
