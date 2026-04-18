package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.di.IoDispatcher
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncOrchestrator @Inject constructor(
    private val appRepository: AppRepository,
    private val credentialManager: CredentialManager,
    private val syncPreflightService: SyncPreflightService,
    private val folderSyncService: FolderSyncService,
    private val notebookSyncService: NotebookSyncService,
    private val syncForceService: SyncForceService,
    private val notebookReconciliationService: NotebookReconciliationService,
    private val webDavClientFactory: WebDavClientFactoryPort,
    private val reporter: SyncProgressReporter,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val sLog = SyncLogger
    suspend fun syncAllNotebooks(): SyncResult = withContext(ioDispatcher) {
        if (!syncMutex.tryLock()) {
            sLog.w(TAG, "Sync already in progress, skipping")
            return@withContext SyncResult.Failure(SyncError.SYNC_IN_PROGRESS)
        }
        val startTime = System.currentTimeMillis()
        return@withContext try {
            sLog.i(TAG, "Starting full sync...")
            reporter.beginStep(SyncStep.INITIALIZING, PROGRESS_INITIALIZING, "Initializing sync...")
            val settings = credentialManager.settings.value
            val credentials = credentialManager.getCredentials()
            if (!settings.syncEnabled) {
                reporter.finishError(SyncError.CONFIG_ERROR, canRetry = false)
                return@withContext SyncResult.Failure(SyncError.CONFIG_ERROR)
            }
            if (credentials == null) {
                reporter.finishError(SyncError.AUTH_ERROR, canRetry = false)
                return@withContext SyncResult.Failure(SyncError.AUTH_ERROR)
            }
            if (!syncPreflightService.checkWifiConstraint()) {
                reporter.finishError(SyncError.WIFI_REQUIRED, canRetry = false)
                return@withContext SyncResult.Failure(SyncError.WIFI_REQUIRED)
            }
            val webdavClient =
                webDavClientFactory.create(
                    settings.serverUrl,
                    credentials.first,
                    credentials.second
                )
            val skewMs = syncPreflightService.checkClockSkew(webdavClient)
            if (skewMs != null && kotlin.math.abs(skewMs) > CLOCK_SKEW_THRESHOLD_MS) {
                reporter.finishError(SyncError.CLOCK_SKEW, canRetry = false)
                return@withContext SyncResult.Failure(SyncError.CLOCK_SKEW)
            }
            syncPreflightService.ensureServerDirectories(webdavClient)
            reporter.beginStep(SyncStep.SYNCING_FOLDERS, PROGRESS_SYNCING_FOLDERS, "Syncing folders...")
            folderSyncService.syncFolders(webdavClient)
            reporter.beginStep(SyncStep.APPLYING_DELETIONS, PROGRESS_APPLYING_DELETIONS, "Applying remote deletions...")
            val tombstonedIds =
                notebookSyncService.applyRemoteDeletions(webdavClient, TOMBSTONE_MAX_AGE_DAYS)
            reporter.beginStep(SyncStep.SYNCING_NOTEBOOKS, PROGRESS_SYNCING_NOTEBOOKS, "Syncing local notebooks...")
            val preDownloadNotebookIds =
                notebookReconciliationService.syncExistingNotebooks(webdavClient)
            val notebooksSynced = preDownloadNotebookIds.size
            reporter.beginStep(SyncStep.DOWNLOADING_NEW, PROGRESS_DOWNLOADING_NEW, "Downloading new notebooks...")
            val notebooksDownloaded = notebookSyncService.downloadNewNotebooks(
                webdavClient,
                tombstonedIds,
                settings,
                preDownloadNotebookIds
            )
            reporter.beginStep(SyncStep.UPLOADING_DELETIONS, PROGRESS_UPLOADING_DELETIONS, "Uploading deletions...")
            val notebooksDeleted = notebookSyncService.detectAndUploadLocalDeletions(
                webdavClient,
                settings,
                preDownloadNotebookIds
            )
            reporter.beginStep(SyncStep.FINALIZING, PROGRESS_FINALIZING, "Finalizing...")
            updateSyncedNotebookIds()
            val duration = System.currentTimeMillis() - startTime
            val summary =
                SyncSummary(notebooksSynced, notebooksDownloaded, notebooksDeleted, duration)
            sLog.i(TAG, "Full sync completed in ${duration}ms")
            reporter.finishSuccess(summary)
            SyncResult.Success
        } catch (e: PreconditionFailedException) {
            sLog.w(TAG, "Conflict during sync: ${e.message}")
            reporter.finishError(SyncError.CONFLICT, canRetry = true)
            SyncResult.Failure(SyncError.CONFLICT)
        } catch (e: IOException) {
            sLog.e(TAG, "Network error during sync: ${e.message}")
            reporter.finishError(SyncError.NETWORK_ERROR, canRetry = true)
            SyncResult.Failure(SyncError.NETWORK_ERROR)
        } catch (e: Exception) {
            sLog.e(TAG, "Unexpected error during sync: ${e.message}\n${e.stackTraceToString()}")
            reporter.finishError(SyncError.UNKNOWN_ERROR, canRetry = false)
            SyncResult.Failure(SyncError.UNKNOWN_ERROR)
        } finally {
            syncMutex.unlock()
        }
    }.also {
        if (it is SyncResult.Success) {
            delay(SUCCESS_STATE_AUTO_RESET_MS)
            if (reporter.state.value is SyncState.Success) reporter.reset()
        }
    }

    suspend fun syncNotebook(notebookId: String): SyncResult = withContext(ioDispatcher) {
        if (syncMutex.isLocked) {
            sLog.i(TAG, "Full sync in progress, skipping per-notebook sync for $notebookId")
            return@withContext SyncResult.Success
        }
        val settings = credentialManager.settings.value
        if (!settings.syncEnabled) return@withContext SyncResult.Success
        if (!syncPreflightService.checkWifiConstraint()) {
            sLog.i(TAG, "WiFi-only sync enabled but not on WiFi, skipping notebook sync")
            return@withContext SyncResult.Success
        }
        val credentials = credentialManager.getCredentials()
            ?: return@withContext SyncResult.Failure(SyncError.AUTH_ERROR)
        val webdavClient =
            webDavClientFactory.create(settings.serverUrl, credentials.first, credentials.second)
        return@withContext notebookReconciliationService.syncNotebook(notebookId, webdavClient)
    }

    suspend fun syncFromPageId(pageId: String) {
        val settings = credentialManager.settings.value
        if (!settings.syncEnabled || !settings.syncOnNoteClose) return
        try {
            val pageEntity = appRepository.pageRepository.getById(pageId) ?: return
            pageEntity.notebookId?.let { notebookId ->
                sLog.i("EditorSync", "Auto-syncing notebook $notebookId on page close")
                syncNotebook(notebookId)
            }
        } catch (e: Exception) {
            sLog.e("EditorSync", "Auto-sync failed: ${e.message}")
        }
    }

    suspend fun uploadDeletion(notebookId: String): SyncResult = withContext(ioDispatcher) {
        return@withContext try {
            val settings = credentialManager.settings.value
            if (!settings.syncEnabled) return@withContext SyncResult.Success
            if (!syncPreflightService.checkWifiConstraint()) return@withContext SyncResult.Success
            val credentials =
                credentialManager.getCredentials() ?: return@withContext SyncResult.Failure(
                    SyncError.AUTH_ERROR
                )
            val webdavClient =
                webDavClientFactory.create(
                    settings.serverUrl,
                    credentials.first,
                    credentials.second
                )
            val notebookPath = SyncPaths.notebookDir(notebookId)
            if (webdavClient.exists(notebookPath)) {
                webdavClient.delete(notebookPath)
            }
            webdavClient.putFile(
                SyncPaths.tombstone(notebookId),
                ByteArray(0),
                "application/octet-stream"
            )
            val updatedSyncedIds = settings.syncedNotebookIds - notebookId
            credentialManager.updateSettings { it.copy(syncedNotebookIds = updatedSyncedIds) }
            SyncResult.Success
        } catch (e: Exception) {
            sLog.e(TAG, "Failed to upload deletion: ${e.message}")
            SyncResult.Failure(SyncError.UNKNOWN_ERROR)
        }
    }

    suspend fun forceUploadAll(): SyncResult = withContext(ioDispatcher) {
        if (!syncMutex.tryLock()) {
            sLog.w(TAG, "Sync already in progress, skipping force upload")
            return@withContext SyncResult.Failure(SyncError.SYNC_IN_PROGRESS)
        }
        return@withContext try {
            syncForceService.forceUploadAll()
        } finally {
            syncMutex.unlock()
        }
    }

    suspend fun forceDownloadAll(): SyncResult = withContext(ioDispatcher) {
        if (!syncMutex.tryLock()) {
            sLog.w(TAG, "Sync already in progress, skipping force download")
            return@withContext SyncResult.Failure(SyncError.SYNC_IN_PROGRESS)
        }
        return@withContext try {
            syncForceService.forceDownloadAll()
        } finally {
            syncMutex.unlock()
        }
    }

    private fun updateSyncedNotebookIds() {
        val currentNotebookIds = appRepository.bookRepository.getAll().map { it.id }.toSet()
        credentialManager.updateSettings { it.copy(syncedNotebookIds = currentNotebookIds) }
    }

    companion object {
        private const val TAG = "SyncOrchestrator"
        private const val PROGRESS_INITIALIZING = 0.0f
        private const val PROGRESS_SYNCING_FOLDERS = 0.1f
        private const val PROGRESS_APPLYING_DELETIONS = 0.2f
        private const val PROGRESS_SYNCING_NOTEBOOKS = 0.3f
        private const val PROGRESS_DOWNLOADING_NEW = 0.6f
        private const val PROGRESS_UPLOADING_DELETIONS = 0.8f
        private const val PROGRESS_FINALIZING = 0.9f
        private const val SUCCESS_STATE_AUTO_RESET_MS = 3000L
        private const val CLOCK_SKEW_THRESHOLD_MS = 30_000L
        private const val TOMBSTONE_MAX_AGE_DAYS = 90L

        // Shared across all call sites (UI, worker, editor) to prevent parallel sync jobs.
        private val syncMutex = Mutex()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncOrchestratorEntryPoint {
    fun syncOrchestrator(): SyncOrchestrator
    fun kvProxy(): KvProxy
    fun credentialManager(): CredentialManager
    fun snackDispatcher(): com.ethran.notable.ui.SnackDispatcher
}

sealed class SyncResult {
    data object Success : SyncResult()
    data class Failure(val error: SyncError) : SyncResult()
}

enum class SyncError { NETWORK_ERROR, AUTH_ERROR, CONFIG_ERROR, CLOCK_SKEW, WIFI_REQUIRED, SYNC_IN_PROGRESS, CONFLICT, UNKNOWN_ERROR }
sealed class SyncState {
    data object Idle : SyncState()
    data class Syncing(
        val currentStep: SyncStep,
        val stepProgress: Float,
        val details: String,
        val item: ItemProgress? = null
    ) : SyncState()

    data class Success(val summary: SyncSummary) : SyncState()
    data class Error(val error: SyncError, val step: SyncStep, val canRetry: Boolean) : SyncState()
}

data class ItemProgress(
    val index: Int,
    val total: Int,
    val name: String
)

enum class SyncStep { INITIALIZING, SYNCING_FOLDERS, APPLYING_DELETIONS, SYNCING_NOTEBOOKS, DOWNLOADING_NEW, UPLOADING_DELETIONS, FINALIZING }
data class SyncSummary(
    val notebooksSynced: Int,
    val notebooksDownloaded: Int,
    val notebooksDeleted: Int,
    val duration: Long
)
