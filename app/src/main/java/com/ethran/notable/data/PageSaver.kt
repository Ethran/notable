package com.ethran.notable.data

import android.os.SystemClock
import androidx.room.withTransaction
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Writes page edits to the database a few seconds behind the editor.
 *
 * The editor changes pages in [PageDataManager] and moves on; [PageDataManager] keeps what changed
 * as [PageChanges], the latest version of each changed stroke and image. This class writes them
 * once editing pauses for [IDLE_DELAY_MS], and no later than [MAX_DELAY_MS] after the first unsaved
 * edit, so a crash loses at most the last few seconds of work. Each save writes everything
 * collected in one transaction, together with the edit timestamps of the pages and notebooks.
 *
 * Code that reads page content from the database — export, sync — calls [saveAll] first, so it
 * sees every edit made so far, and stops if it returns false. The editor never needs to:
 * [PageDataManager] lays unsaved edits over whatever it loads. Other readers (duplicating a page,
 * thumbnails) don't call it. They miss the edits not saved yet — usually those of the last
 * [IDLE_DELAY_MS], up to [MAX_DELAY_MS] of them during continuous editing, more while writes fail —
 * and a thumbnail rendered before the save counts as stale after it, since the save bumps the page's
 * edit timestamp.
 *
 * A failed write keeps its changes unsaved and is retried. Changes to a page deleted meanwhile are
 * dropped.
 *
 * A singleton injected into the editor, the only source of edits, so it exists whenever edits do;
 * once created it runs for the rest of the process.
 */
@Singleton
class PageSaver @Inject constructor(
    private val pageDataManager: PageDataManager,
    private val appRepository: AppRepository,
    private val db: AppDatabase,
    private val appEventBus: AppEventBus,
) {
    private val log = ShipBook.getLogger("PageSaver")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // One save at a time. Each takes its changes only once it holds this, so saves commit in the
    // order their changes were taken and an older version of a stroke never overwrites a newer one.
    private val saveMutex = Mutex()

    init {
        scope.launch { saveAfterEdits() }
    }

    /**
     * Writes every edit made so far and returns once it is in the database, or has failed to be
     * written and stays unsaved. Returns whether everything was saved.
     */
    suspend fun saveAll(): Boolean = saveMutex.withLock {
        val changes = pageDataManager.takeUnsaved()
        if (changes.isEmpty()) return@withLock true
        var failed: Set<String> = changes.keys
        try {
            failed = write(changes)
        } finally {
            pageDataManager.finishSaving(failed)
        }
        failed.isEmpty()
    }

    /** Starts [saveAll] without waiting for it, for when the app may be stopped soon. */
    fun saveSoon() {
        scope.launch { saveAll() }
    }

    private suspend fun saveAfterEdits() {
        val edited = pageDataManager.unsavedChanges
        while (true) {
            edited.receive()
            // Wait for a pause in editing, but not past the deadline.
            val deadline = SystemClock.elapsedRealtime() + MAX_DELAY_MS
            while (true) {
                val wait = minOf(IDLE_DELAY_MS, deadline - SystemClock.elapsedRealtime())
                if (wait <= 0 || withTimeoutOrNull(wait.milliseconds) { edited.receive() } == null) break
            }
            var retryDelay = RETRY_MIN_DELAY_MS
            while (!saveAll()) {
                if (retryDelay == RETRY_MIN_DELAY_MS)
                    appEventBus.tryEmit(AppEvent.ActionHint("Could not save changes, retrying", 3000))
                delay(retryDelay.milliseconds)
                retryDelay = (retryDelay * 2).coerceAtMost(RETRY_MAX_DELAY_MS)
            }
        }
    }

    /**
     * Writes [changes] in one transaction, or page by page if that fails, so one failing page
     * doesn't hold back the others. Returns the pages whose changes could not be written.
     */
    private suspend fun write(changes: Map<String, PageChanges>): Set<String> {
        try {
            db.withTransaction {
                changes.forEach { (pageId, pageChanges) -> writePage(pageId, pageChanges) }
            }
            return emptySet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("Saving ${changes.size} page(s) together failed, saving them one by one: ${e.message}")
        }

        val failed = HashSet<String>()
        for ((pageId, pageChanges) in changes) {
            try {
                db.withTransaction { writePage(pageId, pageChanges) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (pageExists(pageId)) {
                    failed += pageId
                    log.e("Saving page $pageId failed: ${e.message}", e)
                    appEventBus.tryEmit(
                        AppEvent.LogMessage(
                            reason = "PageSaver.write",
                            message = "Saving page $pageId failed: ${e::class.simpleName}: ${e.message}"
                        )
                    )
                } else {
                    log.w("Page $pageId was deleted, dropping its unsaved changes")
                }
            }
        }
        return failed
    }

    private suspend fun writePage(pageId: String, changes: PageChanges) {
        val strokes = changes.strokes.values.filterNotNull()
        val removedStrokeIds = changes.strokes.filterValues { it == null }.keys.toList()
        val images = changes.images.values.filterNotNull()
        val removedImageIds = changes.images.filterValues { it == null }.keys.toList()

        if (strokes.isNotEmpty()) appRepository.strokeRepository.upsert(strokes)
        if (removedStrokeIds.isNotEmpty()) appRepository.strokeRepository.deleteAll(removedStrokeIds)
        if (images.isNotEmpty()) appRepository.imageRepository.upsert(images)
        if (removedImageIds.isNotEmpty()) appRepository.imageRepository.deleteAll(removedImageIds)

        // The page timestamp is the per-page dirty signal for incremental upload; the notebook
        // timestamp drives the per-notebook sync Upload/Download decision.
        if (changes.hasEdits) {
            val now = Date()
            appRepository.pageRepository.touchUpdatedAt(pageId, now.time)
            appRepository.bookRepository.touchUpdatedAtForPage(pageId, now)
        }
        changes.scrollY?.let { appRepository.pageRepository.updateScroll(pageId, it) }
    }

    // On a read error assume the page exists, so its changes are kept and retried.
    private suspend fun pageExists(pageId: String): Boolean = try {
        appRepository.pageRepository.getById(pageId) != null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        true
    }

    private companion object {
        const val IDLE_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 5_000L
        const val RETRY_MIN_DELAY_MS = 2_000L
        const val RETRY_MAX_DELAY_MS = 60_000L
    }
}
