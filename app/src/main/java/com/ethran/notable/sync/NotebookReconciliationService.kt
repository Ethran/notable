package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.sync.serializers.NotebookSerializer
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.flatMap
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.plus
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotebookReconciliationService @Inject constructor(
    private val appRepository: AppRepository,
    private val syncPreflightService: SyncPreflightService,
    private val notebookSyncService: NotebookSyncService,
    private val reporter: SyncProgressReporter
) {
    private val logger = SyncLogger

    suspend fun syncExistingNotebooks(webdavClient: WebDAVClient): AppResult<Set<String>, DomainError> {
        val localNotebooks = appRepository.bookRepository.getAll()
        val preDownloadNotebookIds = localNotebooks.map { it.id }.toSet()
        val total = localNotebooks.size
        var persistentError: DomainError? = null

        localNotebooks.forEachIndexed { i, notebook ->
            reporter.beginItem(index = i + 1, total = total, name = notebook.title)
            // Individual notebook sync failures are non-fatal for the whole process
            syncNotebook(notebook.id, webdavClient).onError { error ->
                persistentError = persistentError?.let { it + error } ?: error
            }
        }
        reporter.endItem()

        return if (persistentError != null) AppResult.Error(persistentError)
        else AppResult.Success(preDownloadNotebookIds)
    }

    suspend fun syncNotebook(
        notebookId: String,
        webdavClient: WebDAVClient
    ): AppResult<Unit, DomainError> {
        logger.i(TAG, "Syncing notebook: $notebookId")

        syncPreflightService.checkWifiConstraint().onError { return AppResult.Error(it) }
        syncPreflightService.checkClockSkew(webdavClient).onError { return AppResult.Error(it) }

        val localNotebook = appRepository.bookRepository.getById(notebookId)
            ?: return AppResult.Error(DomainError.NotFound("Notebook $notebookId"))

        val remotePath = SyncPaths.manifestFile(notebookId)
        val remoteExists = webdavClient.exists(remotePath)

        return if (remoteExists) {
            webdavClient.getFileWithMetadata(remotePath).flatMap { remoteManifest ->
                val remoteEtag = remoteManifest.etag
                    ?: return@flatMap AppResult.Error(DomainError.SyncError("Missing ETag for $remotePath"))

                val remoteManifestJson = remoteManifest.content.decodeToString()
                val remoteUpdatedAt = NotebookSerializer.getManifestUpdatedAt(remoteManifestJson)
                val diffMs = remoteUpdatedAt?.let { localNotebook.updatedAt.time - it.time }
                    ?: Long.MAX_VALUE

                when {
                    remoteUpdatedAt == null -> notebookSyncService.uploadNotebook(
                        localNotebook,
                        webdavClient,
                        manifestIfMatch = remoteEtag
                    )

                    diffMs < -TIMESTAMP_TOLERANCE_MS -> notebookSyncService.downloadNotebook(
                        notebookId,
                        webdavClient
                    )

                    diffMs > TIMESTAMP_TOLERANCE_MS -> notebookSyncService.uploadNotebook(
                        localNotebook,
                        webdavClient,
                        manifestIfMatch = remoteEtag
                    )

                    else -> {
                        logger.i(
                            TAG,
                            "= No changes (within tolerance), skipping ${localNotebook.title}"
                        )
                        AppResult.Success(Unit)
                    }
                }
            }
        } else {
            notebookSyncService.uploadNotebook(localNotebook, webdavClient)
        }
    }

    companion object {
        private const val TAG = "NotebookReconciliationService"
        private const val TIMESTAMP_TOLERANCE_MS = 1000L
    }
}
