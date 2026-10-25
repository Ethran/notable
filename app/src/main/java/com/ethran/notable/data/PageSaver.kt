package com.ethran.notable.data

import android.os.SystemClock
import androidx.room.withTransaction
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
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
 * collected in one transaction, together with the edit timestamps of the pages, set to the time of
 * each page's last edit, and of their notebooks, set to the time of the save.
 *
 * Code that reads page content from the database — export, sync, duplicating a page — calls
 * [saveAll] first, so it sees every edit made so far, and stops if it returns an error. The editor
 * never needs to: [PageDataManager] lays unsaved edits over whatever it loads. Thumbnails don't call
 * it. They miss the edits not saved yet — usually those of the last [IDLE_DELAY_MS], up to
 * [MAX_DELAY_MS] of them during continuous editing, more while writes fail. A thumbnail counts as
 * stale until a margin after the page's edit timestamp, so one rendered before an edit was saved is
 * rendered again, unless the save came more than that margin after the edit.
 *
 * A failed write keeps its changes unsaved and is retried, at most a minute apart, for as long as
 * the app runs. Until a save succeeds a warning stays on screen: edits still unsaved when the process
 * ends are lost. Changes to a page deleted meanwhile are dropped.
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

    // Whether the warning that edits can't be saved is up. Guarded by [saveMutex].
    private var warningShown = false

    init {
        scope.launch { saveAfterEdits() }
    }

    /**
     * Writes every edit made so far and returns once it is in the database, or has failed to be
     * written and stays unsaved. Returns an error if any page could not be saved.
     */
    suspend fun saveAll(): AppResult<Unit, DomainError> = saveMutex.withLock {
        val changes = pageDataManager.takeUnsaved()
        if (changes.isEmpty()) return@withLock AppResult.Success(Unit)
        val failed = try {
            write(changes)
        } catch (e: Throwable) {
            pageDataManager.finishSaving(changes.keys)
            throw e
        }
        pageDataManager.finishSaving(failed.keys)

        val cause = failed.values.firstOrNull()
        if (cause == null) {
            if (warningShown) appEventBus.tryEmit(AppEvent.DismissMessage(AppEvent.EDITS_NOT_SAVED_ID))
            warningShown = false
            return@withLock AppResult.Success(Unit)
        }
        val error = DomainError.DatabaseError(
            "Edits of ${failed.size} page(s) could not be saved: ${cause.message ?: cause::class.simpleName}"
        )
        // Shown on every failure and kept until a save succeeds, so edits that are not being saved
        // never go unnoticed, even if the warning was dismissed.
        appEventBus.tryEmit(AppEvent.EditsNotSaved(error.userMessage))
        warningShown = true
        AppResult.Error(error)
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
            while (saveAll() is AppResult.Error) {
                delay(retryDelay.milliseconds)
                retryDelay = (retryDelay * 2).coerceAtMost(RETRY_MAX_DELAY_MS)
            }
        }
    }

    /**
     * Writes [changes] in one transaction, or page by page if that fails, so one failing page
     * doesn't hold back the others. Returns the pages whose changes could not be written, with why.
     */
    private suspend fun write(changes: Map<String, PageChanges>): Map<String, Exception> {
        try {
            db.withTransaction {
                changes.forEach { (pageId, pageChanges) -> writePage(pageId, pageChanges) }
            }
            return emptyMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("Saving ${changes.size} page(s) together failed, saving them one by one: ${e.message}")
        }

        val failed = HashMap<String, Exception>()
        for ((pageId, pageChanges) in changes) {
            try {
                db.withTransaction { writePage(pageId, pageChanges) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (pageExists(pageId)) {
                    failed[pageId] = e
                    log.e("Saving page $pageId failed: ${e.message}", e)
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
        // timestamp drives the per-notebook sync Upload/Download decision. A scroll alone is saved
        // below without them, so scrolling never makes sync upload a page. The page is stamped with
        // when it was last edited, so a preview captured after the last edit stays fresh, or past
        // a later time stamped meanwhile. The notebook is stamped with when it was saved, which
        // keeps it further from its last sync: sync treats a notebook changed less than a second
        // after it as unchanged.
        if (changes.hasEdits) {
            appRepository.pageRepository.touchUpdatedAt(pageId, changes.editedAtMs)
            appRepository.bookRepository.touchUpdatedAtForPage(pageId, Date())
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
