package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.KvProxy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val webDavClientFactory: WebDavClientFactoryPort
) {
    private val sLog = SyncLogger
    suspend fun syncAllNotebooks(): SyncResult = withContext(Dispatchers.IO) {
        if (!syncMutex.tryLock()) {
            sLog.w(TAG, "Sync already in progress, skipping")
            return@withContext SyncResult.Failure(SyncError.SYNC_IN_PROGRESS)
        }
        val startTime = System.currentTimeMillis()
        return@withContext try {
            sLog.i(TAG, "Starting full sync...")
            updateState(
                SyncState.Syncing(
                    SyncStep.INITIALIZING,
                    PROGRESS_INITIALIZING,
                    "Initializing sync..."
                )
            )
            val settings = credentialManager.settings.value
            val credentials = credentialManager.getCredentials()
            if (!settings.syncEnabled) return@withContext SyncResult.Failure(SyncError.CONFIG_ERROR)
            if (credentials == null) return@withContext SyncResult.Failure(SyncError.AUTH_ERROR)
            if (!syncPreflightService.checkWifiConstraint()) {
                updateState(SyncState.Error(SyncError.WIFI_REQUIRED, SyncStep.INITIALIZING, false))
                return@withContext SyncResult.Failure(SyncError.WIFI_REQUIRED)
            }
            val webdavClient =
                webDavClientFactory.create(settings.serverUrl, credentials.first, credentials.second)
            val skewMs = syncPreflightService.checkClockSkew(webdavClient)
            if (skewMs != null && kotlin.math.abs(skewMs) > CLOCK_SKEW_THRESHOLD_MS) {
                updateState(SyncState.Error(SyncError.CLOCK_SKEW, SyncStep.INITIALIZING, false))
                return@withContext SyncResult.Failure(SyncError.CLOCK_SKEW)
            }
            syncPreflightService.ensureServerDirectories(webdavClient)
            updateState(
                SyncState.Syncing(
                    SyncStep.SYNCING_FOLDERS,
                    PROGRESS_SYNCING_FOLDERS,
                    "Syncing folders..."
                )
            )
            folderSyncService.syncFolders(webdavClient)
            updateState(
                SyncState.Syncing(
                    SyncStep.APPLYING_DELETIONS,
                    PROGRESS_APPLYING_DELETIONS,
                    "Applying remote deletions..."
                )
            )
            val tombstonedIds =
                notebookSyncService.applyRemoteDeletions(webdavClient, TOMBSTONE_MAX_AGE_DAYS)
            updateState(
                SyncState.Syncing(
                    SyncStep.SYNCING_NOTEBOOKS,
                    PROGRESS_SYNCING_NOTEBOOKS,
                    "Syncing local notebooks..."
                )
            )
            val preDownloadNotebookIds = notebookReconciliationService.syncExistingNotebooks(webdavClient)
            val notebooksSynced = preDownloadNotebookIds.size
            updateState(
                SyncState.Syncing(
                    SyncStep.DOWNLOADING_NEW,
                    PROGRESS_DOWNLOADING_NEW,
                    "Downloading new notebooks..."
                )
            )
            val notebooksDownloaded = notebookSyncService.downloadNewNotebooks(
                webdavClient,
                tombstonedIds,
                settings,
                preDownloadNotebookIds
            )
            updateState(
                SyncState.Syncing(
                    SyncStep.UPLOADING_DELETIONS,
                    PROGRESS_UPLOADING_DELETIONS,
                    "Uploading deletions..."
                )
            )
            val notebooksDeleted = notebookSyncService.detectAndUploadLocalDeletions(
                webdavClient,
                settings,
                preDownloadNotebookIds
            )
            updateState(
                SyncState.Syncing(
                    SyncStep.FINALIZING,
                    PROGRESS_FINALIZING,
                    "Finalizing..."
                )
            )
            updateSyncedNotebookIds()
            val duration = System.currentTimeMillis() - startTime
            val summary =
                SyncSummary(notebooksSynced, notebooksDownloaded, notebooksDeleted, duration)
            sLog.i(TAG, "✓ Full sync completed in ${duration}ms")
            updateState(SyncState.Success(summary))
            delay(SUCCESS_STATE_AUTO_RESET_MS)
            if (syncState.value is SyncState.Success) updateState(SyncState.Idle)
            SyncResult.Success
        } catch (e: IOException) {
            sLog.e(TAG, "Network error during sync: ${e.message}")
            val step = (syncState.value as? SyncState.Syncing)?.currentStep ?: SyncStep.INITIALIZING
            updateState(SyncState.Error(SyncError.NETWORK_ERROR, step, true))
            SyncResult.Failure(SyncError.NETWORK_ERROR)
        } catch (e: Exception) {
            sLog.e(TAG, "Unexpected error during sync: ${e.message}\n${e.stackTraceToString()}")
            val step = (syncState.value as? SyncState.Syncing)?.currentStep ?: SyncStep.INITIALIZING
            updateState(SyncState.Error(SyncError.UNKNOWN_ERROR, step, false))
            SyncResult.Failure(SyncError.UNKNOWN_ERROR)
        } finally {
            syncMutex.unlock()
        }
    }

    suspend fun syncNotebook(notebookId: String): SyncResult = withContext(Dispatchers.IO) {
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

    suspend fun uploadDeletion(notebookId: String): SyncResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val settings = credentialManager.settings.value
            if (!settings.syncEnabled) return@withContext SyncResult.Success
            if (!syncPreflightService.checkWifiConstraint()) return@withContext SyncResult.Success
            val credentials =
                credentialManager.getCredentials() ?: return@withContext SyncResult.Failure(
                    SyncError.AUTH_ERROR
                )
            val webdavClient =
                webDavClientFactory.create(settings.serverUrl, credentials.first, credentials.second)
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

    suspend fun forceUploadAll(): SyncResult = withContext(Dispatchers.IO) {
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

    suspend fun forceDownloadAll(): SyncResult = withContext(Dispatchers.IO) {
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
        private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
        val syncState: StateFlow<SyncState> = _syncState.asStateFlow()
        private val syncMutex = Mutex()
        internal fun updateState(state: SyncState) {
            _syncState.value = state
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncOrchestratorEntryPoint {
    fun syncOrchestrator(): SyncOrchestrator
    fun kvProxy(): KvProxy
    fun credentialManager(): CredentialManager
}

sealed class SyncResult {
    data object Success : SyncResult()
    data class Failure(val error: SyncError) : SyncResult()
}

enum class SyncError { NETWORK_ERROR, AUTH_ERROR, CONFIG_ERROR, CLOCK_SKEW, WIFI_REQUIRED, SYNC_IN_PROGRESS, UNKNOWN_ERROR }
sealed class SyncState {
    data object Idle : SyncState()
    data class Syncing(val currentStep: SyncStep, val progress: Float, val details: String) :
        SyncState()

    data class Success(val summary: SyncSummary) : SyncState()
    data class Error(val error: SyncError, val step: SyncStep, val canRetry: Boolean) : SyncState()
}

enum class SyncStep { INITIALIZING, SYNCING_FOLDERS, APPLYING_DELETIONS, SYNCING_NOTEBOOKS, DOWNLOADING_NEW, UPLOADING_DELETIONS, FINALIZING }
data class SyncSummary(
    val notebooksSynced: Int,
    val notebooksDownloaded: Int,
    val notebooksDeleted: Int,
    val duration: Long
)
