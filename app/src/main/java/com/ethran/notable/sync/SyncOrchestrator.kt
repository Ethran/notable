package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.di.IoDispatcher
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.flatMap
import com.ethran.notable.utils.getOrElse
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.onFailure
import com.ethran.notable.utils.onSuccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncOrchestrator @Inject constructor(
    private val appRepository: AppRepository,
    private val kvProxy: KvProxy,
    private val syncPreflightService: SyncPreflightService,
    private val folderSyncService: FolderSyncService,
    private val notebookSyncService: NotebookSyncService,
    private val syncForceService: SyncForceService,
    private val notebookReconciliationService: NotebookReconciliationService,
    private val webDavClientFactory: WebDavClientFactoryPort,
    private val reporter: SyncProgressReporter,
    @param:ApplicationScope private val appScope: CoroutineScope,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val log = SyncLogger

    /**
     * Performs a full synchronization of all folders and notebooks.
     */
    suspend fun syncAllNotebooks(): AppResult<Unit, DomainError> = withContext(ioDispatcher) {
        if (!syncMutex.tryLock()) {
            log.w(TAG, "Sync already in progress, skipping")
            return@withContext AppResult.Error(DomainError.SyncInProgress)
        }

        val startTime = System.currentTimeMillis()

        try {
            log.i(TAG, "Starting full sync...")
            reporter.beginStep(SyncStep.INITIALIZING, PROGRESS_INITIALIZING, "Initializing sync...")

            val settings = kvProxy.getSyncSettings()
            val uploadOnly = settings.uploadOnly
            var nonCriticalError: DomainError? = null

            if (!settings.syncEnabled) {
                return@withContext failStep(DomainError.SyncConfigError)
            }

            if (settings.username.isBlank() || settings.password.isBlank()) {
                return@withContext failStep(DomainError.SyncAuthError)
            }

            syncPreflightService.checkWifiConstraint().onFailure {
                return@withContext failStep(it)
            }

            val client = webDavClientFactory.create(
                settings.serverUrl,
                settings.username,
                settings.password
            )

            syncPreflightService.checkClockSkew(client).onFailure {
                return@withContext failStep(it)
            }

            syncPreflightService.ensureServerDirectories(client).onFailure {
                return@withContext failStep(it)
            }

            // One PROPFIND for the whole remote notebook set, shared by reconciliation (existence
            // checks) and new-notebook discovery -- replaces the per-notebook HEAD probes (5a/P13).
            val remoteNotebookIds = client.listCollection(SyncPaths.notebooksDir())
                .onFailure { return@withContext failStep(it) }
                .toSet()

            reporter.beginStep(
                SyncStep.SYNCING_FOLDERS,
                PROGRESS_SYNCING_FOLDERS,
                "Syncing folders..."
            )
            folderSyncService.syncFolders(client, uploadOnly).onFailure {
                return@withContext failStep(it)
            }

            reporter.beginStep(
                SyncStep.APPLYING_DELETIONS,
                PROGRESS_APPLYING_DELETIONS,
                "Applying remote deletions..."
            )
            val tombstonedIds = if (uploadOnly) {
                emptySet()
            } else {
                notebookSyncService.applyRemoteDeletions(client, TOMBSTONE_MAX_AGE_DAYS)
                    .onFailure { return@withContext failStep(it) }
            }

            reporter.beginStep(
                SyncStep.SYNCING_NOTEBOOKS,
                PROGRESS_SYNCING_NOTEBOOKS,
                "Syncing local notebooks..."
            )
            val localIdsSnapshot = appRepository.bookRepository.getAll().map { it.id }.toSet()
            val preDownloadIds = when (
                val syncResult = notebookReconciliationService.syncExistingNotebooks(client, remoteNotebookIds, uploadOnly)
            ) {
                is AppResult.Success -> syncResult.data
                // Per-notebook failures are NON-CRITICAL: each failed notebook was marked ERROR
                // (its badge shows it) and the run continues so the healthy notebooks still finalize.
                // Aborting here previously left the reporter stuck in Syncing forever -- so every
                // notebook's badge froze on SCHEDULED/SYNCING when one big notebook failed (P25).
                is AppResult.Error -> {
                    nonCriticalError = syncResult.error
                    localIdsSnapshot
                }
            }

            reporter.beginStep(
                SyncStep.DOWNLOADING_NEW,
                PROGRESS_DOWNLOADING_NEW,
                "Downloading new notebooks..."
            )
            val downloadedCount = if (uploadOnly) {
                0
            } else {
                notebookSyncService.downloadNewNotebooks(
                    client,
                    tombstonedIds,
                    preDownloadIds,
                    remoteNotebookIds
                ).onFailure { return@withContext failStep(it) }
            }

            reporter.beginStep(
                SyncStep.UPLOADING_DELETIONS,
                PROGRESS_UPLOADING_DELETIONS,
                "Uploading deletions..."
            )
            val deletedCount =
                notebookSyncService.detectAndUploadLocalDeletions(client, preDownloadIds)
                    .onFailure { return@withContext failStep(it) }


            reporter.beginStep(SyncStep.FINALIZING, PROGRESS_FINALIZING, "Finalizing...")
            // No bulk finalize needed: each notebook's sync-state row is written at its own commit
            // point (upload/download success), and deletions dropped their rows above.

            val summary = SyncSummary(
                preDownloadIds.size,
                downloadedCount,
                deletedCount,
                System.currentTimeMillis() - startTime
            )
            finalizeSyncResult(reporter, summary, nonCriticalError).onSuccess {
                // Persist the last successful full-sync time so the settings "Last synced" line
                // reflects background/periodic syncs too, not just manual ones (P8).
                kvProxy.setSyncSettings(
                    kvProxy.getSyncSettings().copy(lastSyncTime = System.currentTimeMillis())
                )
            }

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
    }.also { result ->
        // Auto-reset the transient Success state after a delay, but off the caller's path so
        // syncAllNotebooks() returns immediately instead of blocking for 3 s.
        if (result is AppResult.Success) {
            appScope.launch {
                delay(SUCCESS_STATE_AUTO_RESET_MS)
                if (reporter.state.value is SyncState.Success) reporter.reset()
            }
        }
    }

    suspend fun syncNotebook(notebookId: String): AppResult<Unit, DomainError> =
        withContext(ioDispatcher) {
            // Actually hold the mutex for the whole operation. A bare isLocked check is
            // check-then-act: it let a sync-on-close race a full/periodic sync (P4). Skip-if-busy
            // is still the right behavior for a single-notebook sync, so a failed tryLock succeeds.
            if (!syncMutex.tryLock()) return@withContext AppResult.Success(Unit)
            try {
                val settings = kvProxy.getSyncSettings()
                if (!settings.syncEnabled) return@withContext AppResult.Success(Unit)

                syncPreflightService.checkWifiConstraint().onSuccess {
                    if (settings.username.isBlank() || settings.password.isBlank()) {
                        return@withContext AppResult.Error(DomainError.SyncAuthError)
                    }
                    val client = webDavClientFactory.create(
                        settings.serverUrl,
                        settings.username,
                        settings.password
                    )
                    // Preflight the clock once here: reconciliation no longer checks skew per
                    // notebook (5c), so the single-notebook path must gate on it itself.
                    syncPreflightService.checkClockSkew(client).onFailure {
                        return@withContext AppResult.Error(it)
                    }
                    return@withContext notebookReconciliationService.syncNotebook(
                        notebookId,
                        client,
                        settings.uploadOnly
                    )
                }
                AppResult.Success(Unit)
            } finally {
                syncMutex.unlock()
            }
        }

    suspend fun syncFromPageId(pageId: String) {
        val settings = kvProxy.getSyncSettings()
        if (!settings.syncEnabled || !settings.syncOnNoteClose) return
        try {
            val page = appRepository.pageRepository.getById(pageId) ?: return
            page.notebookId?.let { syncNotebook(it) }
        } catch (e: Exception) {
            log.e(TAG, "Auto-sync failed: ${e.message}")
        }
    }

    suspend fun uploadDeletion(notebookId: String): AppResult<Unit, DomainError> =
        withContext(ioDispatcher) {
            val settings = kvProxy.getSyncSettings()
            if (!settings.syncEnabled) return@withContext AppResult.Success(Unit)

            return@withContext syncPreflightService.checkWifiConstraint().flatMap {
                if (settings.username.isBlank() || settings.password.isBlank()) {
                    return@flatMap AppResult.Error(DomainError.SyncAuthError)
                }
                val client =
                    webDavClientFactory.create(
                        settings.serverUrl,
                        settings.username,
                        settings.password
                    )

                val path = SyncPaths.notebookDir(notebookId)
                // If existence can't be determined, skip the delete but still write the tombstone
                // below; DELETE is idempotent and a full sync will reconcile any leftover.
                if (client.exists(path).getOrElse { false }) {
                    client.delete(path).onError {
                        log.w(
                            TAG,
                            "Failed to delete remote notebook $notebookId: ${it.userMessage}"
                        )
                    }
                }
                when (val tombstoneResult =
                    client.putFile(SyncPaths.tombstone(notebookId), ByteArray(0))) {
                    is AppResult.Success -> {
                        // Deletion propagated -- drop the sync-state row.
                        appRepository.notebookSyncStateRepository.delete(notebookId)
                        AppResult.Success(Unit)
                    }

                    is AppResult.Error -> tombstoneResult
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

    /** Report [error] as the terminal state of the current sync and return it as a failure. */
    private fun failStep(error: DomainError): AppResult<Unit, DomainError> {
        reporter.finishError(error, false)
        return AppResult.Error(error)
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

internal fun finalizeSyncResult(
    reporter: SyncProgressReporter,
    summary: SyncSummary,
    nonCriticalError: DomainError?
): AppResult<Unit, DomainError> {
    // Upload-only skips are ordinary planned no-ops now (6b), so the only thing that reaches here
    // as a nonCriticalError is a genuine per-notebook failure.
    if (nonCriticalError != null) {
        reporter.finishError(nonCriticalError, false)
        return AppResult.Error(nonCriticalError)
    }

    reporter.finishSuccess(summary)
    return AppResult.Success(Unit)
}
