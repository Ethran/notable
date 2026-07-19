package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.sync.serializers.NotebookSerializer
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.ErrorAccumulator
import com.ethran.notable.utils.flatMap
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.onFailure
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotebookReconciliationService @Inject constructor(
    private val appRepository: AppRepository,
    private val syncPreflightService: SyncPreflightService,
    private val notebookSyncService: NotebookSyncService,
    private val reporter: SyncProgressReporter
) {
    private val log = SyncLogger

    suspend fun syncExistingNotebooks(
        client: WebDAVClient,
        uploadOnly: Boolean
    ): AppResult<Set<String>, DomainError> {
        val localNotebooks = appRepository.bookRepository.getAll()
        val preDownloadNotebookIds = localNotebooks.map { it.id }.toSet()
        val total = localNotebooks.size
        val errors = ErrorAccumulator()

        localNotebooks.forEachIndexed { i, notebook ->
            reporter.beginItem(index = i + 1, total = total, name = notebook.title)
            // Individual notebook sync failures are non-fatal for the whole process
            syncNotebook(notebook.id, client, uploadOnly).onError { errors.add(it) }
        }
        reporter.endItem()

        return errors.asResult(preDownloadNotebookIds)
    }

    suspend fun syncNotebook(
        notebookId: String,
        client: WebDAVClient,
        uploadOnly: Boolean
    ): AppResult<Unit, DomainError> {
        log.i(TAG, "Syncing notebook: $notebookId")

        syncPreflightService.checkWifiConstraint().onError { return AppResult.Error(it) }
        syncPreflightService.checkClockSkew(client).onError { return AppResult.Error(it) }

        val localNotebook = appRepository.bookRepository.getById(notebookId)
            ?: return AppResult.Error(DomainError.NotFound("Notebook $notebookId"))

        val remotePath = SyncPaths.manifestFile(notebookId)
        // If we cannot determine whether the remote manifest exists (e.g. transient network
        // error), abort this notebook rather than fall through to an unguarded upload (P2).
        val remoteExists = client.exists(remotePath).onFailure { return AppResult.Error(it) }

        return if (remoteExists) {
            client.getFileWithMetadata(remotePath).flatMap { remoteManifest ->
                val remoteEtag = remoteManifest.etag
                    ?: return@flatMap AppResult.Error(DomainError.SyncError("Missing ETag for $remotePath"))

                val remoteManifestJson = remoteManifest.content.decodeToString()
                val remoteUpdatedAt = NotebookSerializer.getManifestUpdatedAt(remoteManifestJson)
                val diffMs = remoteUpdatedAt?.let { localNotebook.updatedAt.time - it.time }
                    ?: Long.MAX_VALUE

                when {
                    remoteUpdatedAt == null -> notebookSyncService.uploadNotebook(
                        localNotebook,
                        client,
                        manifestIfMatch = remoteEtag
                    )

                    diffMs < -TIMESTAMP_TOLERANCE_MS -> {
                        if (uploadOnly) {
                            AppResult.Error(DomainError.SyncUploadOnlySkip(localNotebook.title))
                        } else {
                            notebookSyncService.downloadNotebook(
                                notebookId,
                                client
                            )
                        }
                    }

                    diffMs > TIMESTAMP_TOLERANCE_MS -> notebookSyncService.uploadNotebook(
                        localNotebook,
                        client,
                        manifestIfMatch = remoteEtag
                    )

                    else -> {
                        log.i(
                            TAG,
                            "= No changes (within tolerance), skipping ${localNotebook.title}"
                        )
                        AppResult.Success(Unit)
                    }
                }
            }
        } else {
            notebookSyncService.uploadNotebook(localNotebook, client)
        }
    }

    companion object {
        private const val TAG = "NotebookReconciliationService"
        private const val TIMESTAMP_TOLERANCE_MS = 1000L
    }
}
