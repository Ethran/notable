package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.sync.serializers.NotebookSerializer
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotebookReconciliationService @Inject constructor(
    private val appRepository: AppRepository,
    private val credentialManager: CredentialManager,
    private val syncPreflightService: SyncPreflightService,
    private val notebookSyncService: NotebookSyncService,
    private val reporter: SyncProgressReporter
) {
    private val notebookSerializer = NotebookSerializer()
    private val logger = SyncLogger

    suspend fun syncExistingNotebooks(webdavClient: WebDAVClient): Set<String> {
        val localNotebooks = appRepository.bookRepository.getAll()
        val preDownloadNotebookIds = localNotebooks.map { it.id }.toSet()
        val total = localNotebooks.size

        localNotebooks.forEachIndexed { i, notebook ->
            reporter.beginItem(index = i + 1, total = total, name = notebook.title)
            try {
                syncNotebook(notebook.id, webdavClient)
            } catch (e: Exception) {
                logger.e(TAG, "Failed to sync ${notebook.title}: ${e.message}")
            }
        }
        reporter.endItem()

        return preDownloadNotebookIds
    }

    suspend fun syncNotebook(notebookId: String, webdavClient: WebDAVClient): SyncResult {
        return try {
            logger.i(TAG, "Syncing notebook: $notebookId")
            val settings = credentialManager.settings.value

            if (!settings.syncEnabled) return SyncResult.Success
            if (!syncPreflightService.checkWifiConstraint()) return SyncResult.Success

            val skewMs = syncPreflightService.checkClockSkew(webdavClient)
            if (skewMs != null && kotlin.math.abs(skewMs) > CLOCK_SKEW_THRESHOLD_MS) {
                return SyncResult.Failure(SyncError.CLOCK_SKEW)
            }

            val localNotebook = appRepository.bookRepository.getById(notebookId)
                ?: return SyncResult.Failure(SyncError.UNKNOWN_ERROR)

            val remotePath = SyncPaths.manifestFile(notebookId)
            val remoteExists = webdavClient.exists(remotePath)

            if (remoteExists) {
                val remoteManifest = webdavClient.getFileWithMetadata(remotePath)
                val remoteEtag = remoteManifest.etag
                    ?: throw IOException("Missing ETag for $remotePath")
                val remoteManifestJson = remoteManifest.content.decodeToString()
                val remoteUpdatedAt = notebookSerializer.getManifestUpdatedAt(remoteManifestJson)
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

                    else -> logger.i(
                        TAG,
                        "= No changes (within tolerance), skipping ${localNotebook.title}"
                    )
                }
            } else {
                notebookSyncService.uploadNotebook(localNotebook, webdavClient)
            }

            SyncResult.Success
        } catch (e: PreconditionFailedException) {
            logger.w(TAG, "Conflict syncing notebook $notebookId: ${e.message}")
            SyncResult.Failure(SyncError.CONFLICT)
        } catch (e: IOException) {
            logger.e(TAG, "Network error syncing notebook $notebookId: ${e.message}")
            SyncResult.Failure(SyncError.NETWORK_ERROR)
        } catch (e: Exception) {
            logger.e(TAG, "Error syncing notebook $notebookId: ${e.message}")
            SyncResult.Failure(SyncError.UNKNOWN_ERROR)
        }
    }

    companion object {
        private const val TAG = "NotebookReconciliationService"
        private const val TIMESTAMP_TOLERANCE_MS = 1000L
        private const val CLOCK_SKEW_THRESHOLD_MS = 30_000L
    }
}

