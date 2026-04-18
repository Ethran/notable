package com.ethran.notable.io

import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.di.IoDispatcher
import com.ethran.notable.editor.utils.PreviewSaveMode
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A background worker queue for generating page thumbnails.
 *
 * Processes page IDs sequentially and reports progress via global snackbars.
 * Uses a [Mutex] for thread-safe state management across coroutines.
 */
@Singleton
class ThumbnailBackfillQueue @Inject constructor(
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val thumbnailGenerator: ThumbnailGenerator,
    private val appEventBus: AppEventBus
) {
    private val log = ShipBook.getLogger("ThumbnailBackfillQueue")
    private val queue = Channel<Pair<String, PreviewSaveMode>>(Channel.UNLIMITED)

    private val mutex = Mutex()
    private val queuedPageIds = linkedSetOf<String>()

    private var isCycleActive = false
    private var cycleTotal = 0
    private var cycleDone = 0

    private var lastUpdateMs = 0L

    init {
        // listen for thumbnail generation requests
        applicationScope.launch(ioDispatcher) {
            for ((pageId, mode) in queue) {
                processOne(pageId, mode)
            }
        }
    }

    /**
     * Enqueues a list of [pageIds] for thumbnail generation.
     */
    fun enqueue(pageIds: List<String>, mode: PreviewSaveMode = PreviewSaveMode.REGULAR) {
        if (pageIds.isEmpty()) return

        applicationScope.launch(ioDispatcher) {
            val added = mutableListOf<String>()
            mutex.withLock {
                for (pageId in pageIds) {
                    if (pageId.isBlank()) continue
                    if (queuedPageIds.add(pageId)) {
                        added += pageId
                    }
                }

                if (added.isNotEmpty()) {
                    if (!isCycleActive) {
                        isCycleActive = true
                        cycleDone = 0
                        cycleTotal = added.size
                    } else {
                        cycleTotal += added.size
                    }
                    updateProgressLocked()
                }
            }

            added.forEach { pageId ->
                val sent = queue.trySend(pageId to mode)
                if (sent.isFailure) {
                    mutex.withLock {
                        queuedPageIds.remove(pageId)
                    }
                    log.w("Failed to enqueue thumbnail pageId=$pageId")
                }
            }
        }
    }

    private suspend fun processOne(pageId: String, mode: PreviewSaveMode) {
        try {
            thumbnailGenerator.ensureThumbnail(pageId, mode)
        } catch (t: Throwable) {
            log.e("Thumbnail generation failed for pageId=$pageId: ${t.message}")
        } finally {
            mutex.withLock {
                queuedPageIds.remove(pageId)
                cycleDone += 1

                if (queuedPageIds.isEmpty()) {
                    finalizeCycleLocked()
                } else {
                    updateProgressLocked(throttled = true)
                }
            }
        }
    }

    private fun updateProgressLocked(throttled: Boolean = false) {
        val now = System.currentTimeMillis()
        if (throttled && now - lastUpdateMs < 300) return

        lastUpdateMs = now
        appEventBus.tryEmit(
            AppEvent.PreviewBackfillProgress(
                current = cycleDone,
                total = cycleTotal
            )
        )
    }

    private fun finalizeCycleLocked() {
        isCycleActive = false
        val done = cycleDone
        val total = cycleTotal
        cycleDone = 0
        cycleTotal = 0

        // Use a small delay to ensure the user sees the 100% state or "Done" state
        applicationScope.launch {
            delay(100)
            appEventBus.tryEmit(AppEvent.PreviewBackfillCompleted(current = done, total = total))
        }
    }
}
