package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.sync.serializers.NotebookSerializer
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.ErrorAccumulator
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.onFailure
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotebookReconciliationService @Inject constructor(
    private val appRepository: AppRepository,
    private val notebookSyncService: NotebookSyncService,
    private val reporter: SyncProgressReporter
) {
    private val log = SyncLogger

    /**
     * Reconcile every local notebook against the server.
     *
     * [remoteNotebookIds] is the single `PROPFIND` listing of `/notebooks/` fetched once by the
     * orchestrator, so existence is a set lookup instead of a `HEAD` per notebook (5a). Preflight
     * (wifi + clock skew) is done once by the orchestrator, not here (5c).
     */
    suspend fun syncExistingNotebooks(
        client: WebDAVClient,
        remoteNotebookIds: Set<String>,
        uploadOnly: Boolean
    ): AppResult<Set<String>, DomainError> {
        val localNotebooks = appRepository.bookRepository.getAll()
        val preDownloadNotebookIds = localNotebooks.map { it.id }.toSet()
        val total = localNotebooks.size
        val errors = ErrorAccumulator()

        localNotebooks.forEachIndexed { i, notebook ->
            reporter.beginItem(index = i + 1, total = total, name = notebook.title, id = notebook.id)
            // Individual notebook sync failures are non-fatal for the whole process.
            reconcileNotebook(notebook.id, client, remoteNotebookIds.contains(notebook.id), uploadOnly)
                .onError { errors.add(it) }
        }
        reporter.endItem()

        return errors.asResult(preDownloadNotebookIds)
    }

    /**
     * Reconcile a single notebook (sync-on-close). Existence is probed with one `HEAD`; preflight is
     * done by the caller (orchestrator).
     */
    suspend fun syncNotebook(
        notebookId: String,
        client: WebDAVClient,
        uploadOnly: Boolean
    ): AppResult<Unit, DomainError> {
        log.i(TAG, "Syncing notebook: $notebookId")
        // If we cannot determine whether the remote manifest exists (e.g. transient network error),
        // abort this notebook rather than fall through to an unguarded upload (P2).
        val remotePresent = client.exists(SyncPaths.manifestFile(notebookId))
            .onFailure { return AppResult.Error(it) }
        return reconcileNotebook(notebookId, client, remotePresent, uploadOnly)
    }

    private suspend fun reconcileNotebook(
        notebookId: String,
        client: WebDAVClient,
        remotePresent: Boolean,
        uploadOnly: Boolean
    ): AppResult<Unit, DomainError> {
        val localNotebook = appRepository.bookRepository.getById(notebookId)
            ?: return AppResult.Error(DomainError.NotFound("Notebook $notebookId"))

        // Remote absent -> straight upload (new to the server), no If-Match.
        if (!remotePresent) {
            return notebookSyncService.uploadNotebook(localNotebook, client)
        }

        val syncState = appRepository.notebookSyncStateRepository.get(notebookId)
        val storedEtag = syncState?.remoteEtag
        val manifestPath = SyncPaths.manifestFile(notebookId)

        // Fetch the remote manifest -- conditionally when we have a stored ETag, so an unchanged
        // notebook comes back as a cheap, bodyless 304 (5a).
        val remoteChanged: Boolean
        val remote: RemoteManifestInfo?
        if (storedEtag != null) {
            val fetched = client.getFileIfNoneMatch(manifestPath, storedEtag)
                .onFailure { return AppResult.Error(it) }
            if (fetched == null) {
                remoteChanged = false
                remote = null
            } else {
                remoteChanged = true
                remote = fetched.toManifestInfo()
            }
        } else {
            val fetched = client.getFileWithMetadata(manifestPath)
                .onFailure { return AppResult.Error(it) }
            remoteChanged = true
            remote = fetched.toManifestInfo()
        }

        val action = NotebookSyncPlanner.decide(
            localUpdatedAt = localNotebook.updatedAt.time,
            syncedLocalUpdatedAt = syncState?.localUpdatedAtAtSync?.time,
            storedEtag = storedEtag,
            remoteChanged = remoteChanged,
            remote = remote,
            uploadOnly = uploadOnly,
        )

        return when (action) {
            is NotebookAction.Upload ->
                notebookSyncService.uploadNotebook(localNotebook, client, action.ifMatch)

            NotebookAction.Download -> notebookSyncService.downloadNotebook(notebookId, client)

            NotebookAction.SkipUploadOnly -> {
                // Upload-only mode: remote is newer but we never pull. This is a planned no-op, not
                // an error -- we leave local and the sync-state row untouched (the notebook simply
                // isn't up to date with the server, by the user's choice) (6a/6b).
                log.i(TAG, "↑ Upload-only: leaving newer server copy of ${localNotebook.title}")
                AppResult.Success(Unit)
            }

            NotebookAction.Skip -> {
                log.i(TAG, "= No changes, skipping ${localNotebook.title}")
                // Refresh the sync-state row so an unchanged notebook stays SYNCED and (re)stores the
                // current ETag/timestamp for the next If-None-Match check.
                val etagToStore = if (remoteChanged) remote?.etag else storedEtag
                val remoteAtToStore =
                    if (remoteChanged) remote?.updatedAt?.let { Date(it) } else syncState?.remoteUpdatedAt
                appRepository.notebookSyncStateRepository.markSynced(
                    notebookId = notebookId,
                    localUpdatedAt = localNotebook.updatedAt,
                    remoteUpdatedAt = remoteAtToStore,
                    remoteEtag = etagToStore,
                )
                AppResult.Success(Unit)
            }
        }
    }

    private fun DownloadedFile.toManifestInfo(): RemoteManifestInfo {
        val updatedAt = NotebookSerializer.getManifestUpdatedAt(content.decodeToString())?.time
        return RemoteManifestInfo(updatedAt = updatedAt, etag = etag)
    }

    companion object {
        private const val TAG = "NotebookReconciliationService"
    }
}
