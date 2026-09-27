package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.di.IoDispatcher
import com.ethran.notable.sync.serializers.NotebookSerializer
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.flatMap
import com.ethran.notable.utils.getOrElse
import com.ethran.notable.utils.getOrNull
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.onFailure
import com.ethran.notable.utils.onSuccess
import kotlinx.coroutines.CancellationException
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
            val downloadOnly = settings.downloadOnly
            var nonCriticalError: DomainError? = null
            log.i(
                TAG,
                "Mode: ${if (uploadOnly) "upload-only" else if (downloadOnly) "download-only" else "two-way"}" +
                    ", fastSync=${settings.fastSyncEnabled}, wifiOnly=${settings.wifiOnly}"
            )

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

            // One root PROPFIND collapses the three directory-existence HEADs and (when the server
            // sends a usable Date header) the dedicated clock-skew HEAD into a single request.
            syncPreflightService.ensureServerReady(client).onFailure {
                return@withContext failStep(it)
            }

            // One PROPFIND for the whole remote notebook set, shared by reconciliation (existence
            // checks) and new-notebook discovery -- replaces the per-notebook HEAD probes. The
            // per-directory ETags it also carries drive bulk change detection: a matching baseline
            // lets a notebook skip its manifest GET entirely.
            val remoteEntries = client.listCollectionWithMetadata(SyncPaths.notebooksDir())
                .onFailure { return@withContext failStep(it) }
            val remoteNotebookIds = remoteEntries.mapTo(mutableSetOf()) { it.name }
            val dirEtags: Map<String, ETag?> = remoteEntries.associate { it.name to it.etag }
            log.i(
                TAG,
                "Remote listing: ${remoteNotebookIds.size} notebook dir(s), " +
                    "${dirEtags.values.count { it != null }} with an ETag"
            )

            // Snapshot the measured capability once for the whole pass, bound to this client's server
            // identity. A read/decode failure is treated as "no capability" (fast path off) rather
            // than failing the sync -- optimization state must never break a functional sync.
            val currentServerKey = client.serverKey
            val capabilities = try {
                kvProxy.getServerCapabilities()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(TAG, "Could not read server capabilities; bulk detection off: ${e.message}")
                null
            }
            val bulkEnabled = settings.fastSyncEnabled && capabilities?.let {
                it.serverKey == currentServerKey && it.collectionEtagPropagates
            } == true
            log.i(
                TAG,
                "Bulk change detection: " + when {
                    !settings.fastSyncEnabled -> "off (fast sync disabled)"
                    capabilities == null -> "off (no stored capability — run Test Connection)"
                    capabilities.serverKey != currentServerKey ->
                        "off (capability is for a different server URL/username)"
                    !capabilities.collectionEtagPropagates ->
                        "off (server does not propagate collection ETags)"
                    else -> "on"
                }
            )

            reporter.beginStep(
                SyncStep.SYNCING_FOLDERS,
                PROGRESS_SYNCING_FOLDERS,
                "Syncing folders..."
            )
            folderSyncService.syncFolders(client, uploadOnly, downloadOnly).onFailure {
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
                val syncResult = notebookReconciliationService.syncExistingNotebooks(
                    client, remoteNotebookIds, uploadOnly, downloadOnly,
                    bulkEnabled, currentServerKey, dirEtags
                )
            ) {
                is AppResult.Success -> syncResult.data
                // Per-notebook failures are NON-CRITICAL: each failed notebook was marked ERROR
                // (its badge shows it) and the run continues so the healthy notebooks still finalize.
                // Aborting here previously left the reporter stuck in Syncing forever -- so every
                // notebook's badge froze on SCHEDULED/SYNCING when one big notebook failed.
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
                    client = client,
                    tombstonedIds = tombstonedIds,
                    preDownloadNotebookIds = preDownloadIds,
                    remoteNotebookIds = remoteNotebookIds,
                    downloadOnly = downloadOnly,
                    bulkEnabled = bulkEnabled,
                    currentServerKey = currentServerKey,
                    dirEtags = dirEtags,
                ).onFailure { return@withContext failStep(it) }
            }

            reporter.beginStep(
                SyncStep.UPLOADING_DELETIONS,
                PROGRESS_UPLOADING_DELETIONS,
                "Uploading deletions..."
            )
            val deletedCount = if (downloadOnly) {
                0 // download-only: never push local deletions to the server
            } else {
                notebookSyncService.detectAndUploadLocalDeletions(client, preDownloadIds)
                    .onFailure { return@withContext failStep(it) }
            }


            reporter.beginStep(SyncStep.FINALIZING, PROGRESS_FINALIZING, "Finalizing...")
            // No bulk finalize needed: each notebook's sync-state row is written at its own commit
            // point (upload/download success), and deletions dropped their rows above.

            // Best-effort cleanup of dead remote data (dir with no manifest, not held locally, older
            // than a week) — an abandoned half-upload from another device the re-upload self-heal misses.
            // Full bidirectional mode only: it deletes remotely (skip in download-only) and needs the
            // remote scan (skip in upload-only). Never fails the run.
            if (!uploadOnly && !downloadOnly) {
                val currentLocalIds = appRepository.bookRepository.getAll().map { it.id }.toSet()
                notebookSyncService.garbageCollectOrphanedRemotes(
                    client, currentLocalIds, ORPHAN_MAX_AGE_DAYS
                )
            }

            val summary = SyncSummary(
                preDownloadIds.size,
                downloadedCount,
                deletedCount,
                System.currentTimeMillis() - startTime
            )
            log.i(
                TAG,
                "Full sync finished in ${summary.duration} ms: " +
                    "${summary.notebooksDownloaded} downloaded, ${summary.notebooksDeleted} deleted, " +
                    "${preDownloadIds.size} local notebook(s)" +
                    (nonCriticalError?.let { " — with error: ${it.userMessage}" } ?: "")
            )
            finalizeSyncResult(reporter, summary, nonCriticalError).onSuccess {
                // Persist the last successful full-sync time so the settings "Last synced" line
                // reflects background/periodic syncs too, not just manual ones.
                kvProxy.updateSyncSettings {
                    it.copy(lastSyncTime = System.currentTimeMillis())
                }
            }

        } catch (e: CancellationException) {
            // The worker was cancelled (e.g. schedule disabled mid-run). Don't leave the reporter
            // stuck in Syncing/Error (which wedged the Sync-now button) -- reset to Idle and let the
            // cancellation propagate normally.
            reporter.reset()
            throw e
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
            // check-then-act: it let a sync-on-close race a full/periodic sync. Skip-if-busy
            // is still the right behavior for a single-notebook sync, so a failed tryLock succeeds.
            if (!syncMutex.tryLock()) return@withContext AppResult.Success(Unit)
            try {
                runSingleNotebookSync(notebookId)
            } finally {
                syncMutex.unlock()
            }
        }

    /**
     * The single-notebook sync body — preflight then reconcile — WITHOUT touching [syncMutex]. The
     * caller owns the mutex around it: [syncNotebook] via a skip-if-busy `tryLock`, and the
     * conflict-resolution entry points by *waiting* for it so an explicit user choice is applied for
     * real rather than dropped on contention. Reconciliation no longer checks clock skew per
     * notebook, so this gates on it itself.
     */
    private suspend fun runSingleNotebookSync(notebookId: String): AppResult<Unit, DomainError> {
        val settings = kvProxy.getSyncSettings()
        if (!settings.syncEnabled) return AppResult.Success(Unit)
        // Wifi constraint not satisfied -> planned no-op, the same policy as any other sync.
        if (syncPreflightService.checkWifiConstraint() is AppResult.Error) return AppResult.Success(Unit)
        if (settings.username.isBlank() || settings.password.isBlank()) {
            return AppResult.Error(DomainError.SyncAuthError)
        }
        val client = webDavClientFactory.create(settings.serverUrl, settings.username, settings.password)
        syncPreflightService.checkClockSkew(client).onFailure { return AppResult.Error(it) }
        return notebookReconciliationService.syncNotebook(
            notebookId,
            client,
            settings.uploadOnly,
            settings.downloadOnly
        )
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

    /**
     * Read-only check for the check-on-open hint: is the server's copy of [notebookId] newer
     * than ours? Uses the stored ETag for a cheap conditional GET (a 304 means "not newer"). Never
     * mutates anything and never holds the sync mutex — it is a best-effort hint, so any error or
     * ambiguity returns false.
     */
    suspend fun isRemoteNewer(notebookId: String): Boolean = withContext(ioDispatcher) {
        val settings = kvProxy.getSyncSettings()
        if (!settings.syncEnabled || !settings.checkOnOpen ||
            settings.username.isBlank() || settings.password.isBlank()
        ) {
            return@withContext false
        }
        val local = appRepository.bookRepository.getById(notebookId) ?: return@withContext false
        val client = webDavClientFactory.create(
            settings.serverUrl, settings.username, settings.password
        )
        val manifestPath = SyncPaths.manifestFile(notebookId)
        val storedEtag =
            ETag.parse(appRepository.notebookSyncStateRepository.get(notebookId)?.remoteEtag)

        val remoteContent = if (storedEtag != null) {
            // null == 304 (unchanged) OR error -> treat as "not newer".
            client.getFileIfNoneMatch(manifestPath, storedEtag).getOrNull()?.content
                ?: return@withContext false
        } else {
            client.getFileWithMetadata(manifestPath).getOrNull()?.content
                ?: return@withContext false
        }

        val remoteUpdatedAt = NotebookSerializer.getManifestUpdatedAt(remoteContent.decodeToString())
            ?: return@withContext false
        remoteUpdatedAt.time - local.updatedAt.time > REMOTE_NEWER_TOLERANCE_MS
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
                        // Deletion propagated -- drop the sync-state rows (notebook + per-page).
                        appRepository.notebookSyncStateRepository.delete(notebookId)
                        appRepository.pageSyncStateRepository.deleteByNotebook(notebookId)
                        AppResult.Success(Unit)
                    }

                    is AppResult.Error -> tombstoneResult
                }
            }
        }

    suspend fun forceUploadAll(): AppResult<Unit, DomainError> =
        runForce("Uploading all notebooks...") { syncForceService.forceUploadAll() }

    suspend fun forceDownloadAll(): AppResult<Unit, DomainError> =
        runForce("Downloading all notebooks...") { syncForceService.forceDownloadAll() }

    /**
     * Shared wrapper for the force operations. Beyond holding [syncMutex] it drives the same
     * [reporter] a normal sync does, so the "Sync now" button disables and the progress panel shows
     * while a force run is in flight -- otherwise the reporter stayed Idle and the button, thinking
     * nothing was running, funnelled a tap into [syncAllNotebooks] whose [syncMutex.tryLock] then
     * failed with SyncInProgress. The terminal state is set only after the lock is actually held, so
     * a contended run (another sync active) returns SyncInProgress without clobbering its state.
     */
    private suspend fun runForce(
        details: String,
        block: suspend () -> AppResult<Unit, DomainError>
    ): AppResult<Unit, DomainError> = withContext(ioDispatcher) {
        if (!syncMutex.tryLock()) return@withContext AppResult.Error(DomainError.SyncInProgress)
        try {
            reporter.beginStep(SyncStep.SYNCING_NOTEBOOKS, PROGRESS_SYNCING_NOTEBOOKS, details)
            block().also { result ->
                when (result) {
                    is AppResult.Success -> reporter.finishSuccess(SyncSummary(0, 0, 0, 0))
                    is AppResult.Error -> reporter.finishError(result.error, false)
                }
            }
        } finally {
            syncMutex.unlock()
        }
    }.also { result ->
        if (result is AppResult.Success) appScope.launch {
            delay(SUCCESS_STATE_AUTO_RESET_MS)
            if (reporter.state.value is SyncState.Success) reporter.reset()
        }
    }

    /**
     * The full conflict picture for [notebookId] — the pages edited on both sides plus whether the
     * manifests diverge structurally. For the resolution UI: read-only, holds no sync mutex. Returns
     * an empty [NotebookConflict] when sync is off/unconfigured or the notebook is gone.
     */
    suspend fun notebookConflict(notebookId: String): AppResult<NotebookConflict, DomainError> =
        withContext(ioDispatcher) {
            val settings = kvProxy.getSyncSettings()
            if (!settings.syncEnabled || settings.username.isBlank() || settings.password.isBlank()) {
                return@withContext AppResult.Success(NotebookConflict(emptyList(), structural = false))
            }
            val notebook = appRepository.bookRepository.getById(notebookId)
                ?: return@withContext AppResult.Success(NotebookConflict(emptyList(), structural = false))
            val client =
                webDavClientFactory.create(settings.serverUrl, settings.username, settings.password)
            notebookSyncService.detectConflicts(notebook, client)
        }

    /**
     * Apply the user's [resolution] to one conflicted page and transfer it, then — once the last
     * conflict is resolved — the CONFLICT badge clears. [PageConflictResolution.SKIP] changes nothing.
     *
     * Everything below runs while *holding* [syncMutex] (waiting for it, not skip-if-busy), so the
     * rebaseline and its transfer are one operation a concurrent sync can neither drop nor observe
     * half-done. [resolutionPreflight] refuses upload-only / download-only mode first, so an explicit
     * choice is only ever acknowledged when the notebook can actually be transferred both ways.
     */
    suspend fun resolvePageConflict(
        notebookId: String,
        pageId: String,
        resolution: PageConflictResolution
    ): AppResult<Unit, DomainError> = withContext(ioDispatcher) {
        // SKIP mutates nothing and needs no transfer, so it need not wait for the mutex.
        if (resolution == PageConflictResolution.SKIP) return@withContext AppResult.Success(Unit)
        syncMutex.lock()
        try {
            val settings = kvProxy.getSyncSettings()
            val client = resolutionPreflight(settings)
                .getOrElse { return@withContext AppResult.Error(it) }
            notebookSyncService.resolveConflictedPage(notebookId, pageId, resolution, client)
                .onFailure { return@withContext AppResult.Error(it) }
            runResolutionTransfer(notebookId, client)
        } finally {
            syncMutex.unlock()
        }
    }

    /**
     * Apply a whole-notebook [resolution] for a structural conflict. TAKE_SERVER downloads the server
     * copy (which commits as synced); KEEP_LOCAL adopts the server ETags as our base and then uploads
     * the local copy over the server. Either way the CONFLICT badge clears once it completes.
     *
     * Runs while *holding* [syncMutex] (waiting for it, not skip-if-busy) so the rebaseline and its
     * transfer are atomic against other syncs. [resolutionPreflight] refuses upload-only / download-only
     * mode and an unmet Wi-Fi constraint up front, so KEEP_LOCAL is never acknowledged while the server's
     * other version would in fact be kept.
     */
    suspend fun resolveNotebookConflict(
        notebookId: String,
        resolution: NotebookConflictResolution
    ): AppResult<Unit, DomainError> = withContext(ioDispatcher) {
        syncMutex.lock()
        try {
            val settings = kvProxy.getSyncSettings()
            val client = resolutionPreflight(settings)
                .getOrElse { return@withContext AppResult.Error(it) }
            // TAKE_SERVER downloads inline (its own committed transfer); KEEP_LOCAL only rebaselines
            // here and needs the upload half below.
            notebookSyncService.resolveNotebookConflict(notebookId, resolution, client)
                .onFailure { return@withContext AppResult.Error(it) }
            if (resolution == NotebookConflictResolution.TAKE_SERVER) AppResult.Success(Unit)
            else runResolutionTransfer(notebookId, client)
        } finally {
            syncMutex.unlock()
        }
    }

    /**
     * Strict preflight for an explicit user resolution, returning the [WebDAVClient] to use. Unlike
     * background sync — where disabled sync or an unmet Wi-Fi constraint is a planned no-op that
     * returns success — a resolution is about to change the sync baseline, so a silent no-op would
     * acknowledge a choice we never transfer. Every gate here fails with an error instead.
     *
     * Upload-only / download-only is refused outright: resolving a conflict has to move a version in
     * whichever direction the choice needs, which a one-directional mode cannot honor without either
     * violating the mode or dropping the choice. The notebook keeps its CONFLICT badge and the user
     * resolves it once back on two-way sync.
     */
    private suspend fun resolutionPreflight(
        settings: SyncSettings
    ): AppResult<WebDAVClient, DomainError> {
        if (!settings.syncEnabled || settings.username.isBlank() || settings.password.isBlank()) {
            return AppResult.Error(DomainError.SyncConfigError)
        }
        if (settings.uploadOnly || settings.downloadOnly) {
            return AppResult.Error(DomainError.SyncDirectionalConflict)
        }
        val client =
            webDavClientFactory.create(settings.serverUrl, settings.username, settings.password)
        syncPreflightService.checkWifiConstraint().onFailure { return AppResult.Error(it) }
        syncPreflightService.checkClockSkew(client).onFailure { return AppResult.Error(it) }
        return AppResult.Success(client)
    }

    /**
     * The transfer half of a resolution: a bidirectional reconcile that picks up the rebaselined rows.
     * [resolutionPreflight] has already refused upload-only / download-only, so the notebook is on
     * two-way sync here and a whole-notebook reconcile stays within the user's chosen direction. Runs
     * under the caller's held [syncMutex]; on failure the rebaselined row is left as a pending state
     * the next sync completes, and the caller surfaces the error.
     */
    private suspend fun runResolutionTransfer(
        notebookId: String,
        client: WebDAVClient
    ): AppResult<Unit, DomainError> = notebookReconciliationService.syncNotebook(
        notebookId, client, uploadOnly = false, downloadOnly = false
    )

    /** Report [error] as the terminal state of the current sync and return it as a failure. */
    private fun failStep(error: DomainError): AppResult<Unit, DomainError> {
        val step = (reporter.state.value as? SyncState.Syncing)?.currentStep ?: SyncStep.INITIALIZING
        log.w(TAG, "Sync aborted during $step: ${error.userMessage}")
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
        private const val ORPHAN_MAX_AGE_DAYS = 7L
        private const val REMOTE_NEWER_TOLERANCE_MS = 1000L
        private val syncMutex = Mutex()
    }
}

internal fun finalizeSyncResult(
    reporter: SyncProgressReporter,
    summary: SyncSummary,
    nonCriticalError: DomainError?
): AppResult<Unit, DomainError> {
    // Upload-only skips are ordinary planned no-ops now, so the only thing that reaches here
    // as a nonCriticalError is a genuine per-notebook failure.
    if (nonCriticalError != null) {
        reporter.finishError(nonCriticalError, false)
        return AppResult.Error(nonCriticalError)
    }

    reporter.finishSuccess(summary)
    return AppResult.Success(Unit)
}
