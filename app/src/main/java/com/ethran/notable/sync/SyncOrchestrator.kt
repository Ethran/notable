package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.di.IoDispatcher
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.flatMap
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.onFailure
import com.ethran.notable.utils.onSuccess
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
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
    private val logger = SyncLogger

    /**
     * Performs a full synchronization of all folders and notebooks.
     */
    suspend fun syncAllNotebooks(): AppResult<Unit, DomainError> = withContext(ioDispatcher) {
        if (!syncMutex.tryLock()) {
            logger.w(TAG, "Sync already in progress, skipping")
            return@withContext AppResult.Error(DomainError.SyncInProgress)
        }

        val startTime = System.currentTimeMillis()

        try {
            logger.i(TAG, "Starting full sync...")
            reporter.beginStep(SyncStep.INITIALIZING, PROGRESS_INITIALIZING, "Initializing sync...")

            val settings = credentialManager.settings.first()
            val credentials = credentialManager.getCredentials()

            if (!settings.syncEnabled) {
                val error = DomainError.SyncConfigError
                reporter.finishError(error, false)
                return@withContext AppResult.Error(error)
            }

            if (credentials == null) {
                val error = DomainError.SyncAuthError
                reporter.finishError(error, false)
                return@withContext AppResult.Error(error)
            }


            syncPreflightService.checkWifiConstraint().onFailure { error ->
                reporter.finishError(error, false)
                return@withContext AppResult.Error(error)
            }

            val client = webDavClientFactory.create(
                settings.serverUrl,
                credentials.first,
                credentials.second
            )

            syncPreflightService.checkClockSkew(client).onFailure { error ->
                reporter.finishError(error, false)
                return@withContext AppResult.Error(error)
            }

            syncPreflightService.ensureServerDirectories(client).onFailure { error ->
                return@withContext AppResult.Error(error)
            }

            reporter.beginStep(
                SyncStep.SYNCING_FOLDERS,
                PROGRESS_SYNCING_FOLDERS,
                "Syncing folders..."
            )
            folderSyncService.syncFolders(client).onFailure { error ->
                return@withContext AppResult.Error(error)
            }

            reporter.beginStep(
                SyncStep.APPLYING_DELETIONS,
                PROGRESS_APPLYING_DELETIONS,
                "Applying remote deletions..."
            )
            val tombstonedIds =
                notebookSyncService.applyRemoteDeletions(client, TOMBSTONE_MAX_AGE_DAYS)
                    .onFailure { error ->
                        return@withContext AppResult.Error(error)
                    }

            reporter.beginStep(
                SyncStep.SYNCING_NOTEBOOKS,
                PROGRESS_SYNCING_NOTEBOOKS,
                "Syncing local notebooks..."
            )
            val preDownloadIds = notebookReconciliationService.syncExistingNotebooks(client)
                .onFailure { error ->
                    return@withContext AppResult.Error(error)
                }

            reporter.beginStep(
                SyncStep.DOWNLOADING_NEW,
                PROGRESS_DOWNLOADING_NEW,
                "Downloading new notebooks..."
            )
            val downloadedCount = notebookSyncService.downloadNewNotebooks(
                client,
                tombstonedIds,
                settings,
                preDownloadIds
            ).onFailure { error ->
                return@withContext AppResult.Error(error)
            }

            reporter.beginStep(
                SyncStep.UPLOADING_DELETIONS,
                PROGRESS_UPLOADING_DELETIONS,
                "Uploading deletions..."
            )
            val deletedCount =
                notebookSyncService.detectAndUploadLocalDeletions(client, settings, preDownloadIds)
                    .onFailure { error ->
                        return@withContext AppResult.Error(error)
                    }


            reporter.beginStep(SyncStep.FINALIZING, PROGRESS_FINALIZING, "Finalizing...")
            updateSyncedNotebookIds()

            val summary = SyncSummary(
                preDownloadIds.size,
                downloadedCount,
                deletedCount,
                System.currentTimeMillis() - startTime
            )
            reporter.finishSuccess(summary)

            AppResult.Success(Unit)

        } catch (e: Exception) {
            val error = DomainError.SyncError(
                e.message ?: "Unexpected error during sync",
                recoverable = false
            )
            reporter.finishError(error, false)
            AppResult.Error(error)
        } finally {
            syncMutex.unlock()
        }
    }.also {
        if (it is AppResult.Success) {
            delay(SUCCESS_STATE_AUTO_RESET_MS)
            if (reporter.state.value is SyncState.Success) reporter.reset()
        }
    }

    suspend fun syncNotebook(notebookId: String): AppResult<Unit, DomainError> =
        withContext(ioDispatcher) {
            if (syncMutex.isLocked) return@withContext AppResult.Success(Unit)
            val settings = credentialManager.settings.first()
            if (!settings.syncEnabled) return@withContext AppResult.Success(Unit)

            syncPreflightService.checkWifiConstraint().onSuccess {
                val credentials =
                    credentialManager.getCredentials() ?: return@withContext AppResult.Error(
                        DomainError.SyncAuthError
                    )
                val client = webDavClientFactory.create(
                    settings.serverUrl,
                    credentials.first,
                    credentials.second
                )
                return@withContext notebookReconciliationService.syncNotebook(notebookId, client)
            }
            AppResult.Success(Unit)
        }

    suspend fun syncFromPageId(pageId: String) {
        val settings = credentialManager.settings.first()
        if (!settings.syncEnabled || !settings.syncOnNoteClose) return
        try {
            val page = appRepository.pageRepository.getById(pageId) ?: return
            page.notebookId?.let { syncNotebook(it) }
        } catch (e: Exception) {
            logger.e(TAG, "Auto-sync failed: ${e.message}")
        }
    }

    suspend fun uploadDeletion(notebookId: String): AppResult<Unit, DomainError> =
        withContext(ioDispatcher) {
            val settings = credentialManager.settings.first()
            if (!settings.syncEnabled) return@withContext AppResult.Success(Unit)

            return@withContext syncPreflightService.checkWifiConstraint().flatMap {
                val creds = credentialManager.getCredentials() ?: return@flatMap AppResult.Error(
                    DomainError.SyncAuthError
                )
                val client =
                    webDavClientFactory.create(settings.serverUrl, creds.first, creds.second)

                val path = SyncPaths.notebookDir(notebookId)
                if (client.exists(path)) {
                    client.delete(path).onError {
                        logger.w(TAG, "Failed to delete remote notebook $notebookId: ${it.userMessage}")
                    }
                }
                client.putFile(SyncPaths.tombstone(notebookId), ByteArray(0)).flatMap {
                    credentialManager.updateSettings { it.copy(syncedNotebookIds = it.syncedNotebookIds - notebookId) }
                    AppResult.Success(Unit)
                }
            }
        }

    suspend fun forceUploadAll(): AppResult<Unit, DomainError> = withContext(ioDispatcher) {
        if (!syncMutex.tryLock()) return@withContext AppResult.Error(DomainError.SyncInProgress)
        try {
            syncForceService.forceUploadAll()
        } finally {
            syncMutex.unlock()
        }
    }

    suspend fun forceDownloadAll(): AppResult<Unit, DomainError> = withContext(ioDispatcher) {
        if (!syncMutex.tryLock()) return@withContext AppResult.Error(DomainError.SyncInProgress)
        try {
            syncForceService.forceDownloadAll()
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun updateSyncedNotebookIds() {
        val currentIds = appRepository.bookRepository.getAll().map { it.id }.toSet()
        credentialManager.updateSettings { it.copy(syncedNotebookIds = currentIds) }
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
        private const val TOMBSTONE_MAX_AGE_DAYS = 90L
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

sealed class SyncState {
    data object Idle : SyncState()
    data class Syncing(
        val currentStep: SyncStep,
        val stepProgress: Float,
        val details: String,
        val item: ItemProgress? = null
    ) : SyncState()

    data class Success(val summary: SyncSummary) : SyncState()
    data class Error(val error: DomainError, val step: SyncStep, val canRetry: Boolean) :
        SyncState()
}

data class ItemProgress(val index: Int, val total: Int, val name: String)
enum class SyncStep { INITIALIZING, SYNCING_FOLDERS, APPLYING_DELETIONS, SYNCING_NOTEBOOKS, DOWNLOADING_NEW, UPLOADING_DELETIONS, FINALIZING }
data class SyncSummary(
    val notebooksSynced: Int,
    val notebooksDownloaded: Int,
    val notebooksDeleted: Int,
    val duration: Long
)
