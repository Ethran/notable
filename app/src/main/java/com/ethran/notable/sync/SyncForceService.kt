package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.sync.serializers.FolderSerializer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncForceService @Inject constructor(
    private val appRepository: AppRepository,
    private val credentialManager: CredentialManager,
    private val syncPreflightService: SyncPreflightService,
    private val notebookSyncService: NotebookSyncService,
    private val webDavClientFactory: WebDavClientFactoryPort
) {
    private val folderSerializer = FolderSerializer
    private val logger = SyncLogger

    suspend fun forceUploadAll(): SyncResult {
        return try {
            logger.i(TAG, "FORCE UPLOAD: Replacing server with local data")
            val settings = credentialManager.settings.value
            val credentials = credentialManager.getCredentials()
                ?: return SyncResult.Failure(SyncError.AUTH_ERROR)

            val webdavClient =
                webDavClientFactory.create(settings.serverUrl, credentials.first, credentials.second)

            try {
                if (webdavClient.exists(SyncPaths.notebooksDir())) {
                    val existingNotebooks = webdavClient.listCollection(SyncPaths.notebooksDir())
                    logger.i(
                        TAG,
                        "Deleting ${existingNotebooks.size} existing notebooks from server"
                    )
                    existingNotebooks.forEach { notebookDir ->
                        try {
                            webdavClient.delete(SyncPaths.notebookDir(notebookDir))
                        } catch (e: Exception) {
                            logger.w(TAG, "Failed to delete $notebookDir: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                logger.w(TAG, "Error cleaning server notebooks: ${e.message}")
            }

            syncPreflightService.ensureServerDirectories(webdavClient)

            val folders = appRepository.folderRepository.getAll()
            if (folders.isNotEmpty()) {
                val foldersJson = folderSerializer.serializeFolders(folders)
                webdavClient.putFile(
                    SyncPaths.foldersFile(),
                    foldersJson.toByteArray(),
                    "application/json"
                )
                logger.i(TAG, "Uploaded ${folders.size} folders")
            }

            val notebooks = appRepository.bookRepository.getAll()
            logger.i(TAG, "Uploading ${notebooks.size} local notebooks...")
            notebooks.forEach { notebook ->
                try {
                    notebookSyncService.uploadNotebook(notebook, webdavClient)
                    logger.i(TAG, "Uploaded: ${notebook.title}")
                } catch (e: Exception) {
                    logger.e(TAG, "Failed to upload ${notebook.title}: ${e.message}")
                }
            }

            logger.i(TAG, "FORCE UPLOAD complete: ${notebooks.size} notebooks")
            SyncResult.Success
        } catch (e: Exception) {
            logger.e(TAG, "Force upload failed: ${e.message}")
            SyncResult.Failure(SyncError.UNKNOWN_ERROR)
        }
    }

    suspend fun forceDownloadAll(): SyncResult {
        return try {
            logger.i(TAG, "FORCE DOWNLOAD: Replacing local with server data")
            val settings = credentialManager.settings.value
            val credentials = credentialManager.getCredentials()
                ?: return SyncResult.Failure(SyncError.AUTH_ERROR)

            val webdavClient =
                webDavClientFactory.create(settings.serverUrl, credentials.first, credentials.second)

            val localFolders = appRepository.folderRepository.getAll()
            localFolders.forEach { appRepository.folderRepository.delete(it.id) }

            val localNotebooks = appRepository.bookRepository.getAll()
            localNotebooks.forEach { appRepository.bookRepository.delete(it.id) }

            logger.i(
                TAG,
                "Deleted ${localFolders.size} folders and ${localNotebooks.size} local notebooks"
            )

            if (webdavClient.exists(SyncPaths.foldersFile())) {
                val foldersJson = webdavClient.getFile(SyncPaths.foldersFile()).decodeToString()
                val folders = folderSerializer.deserializeFolders(foldersJson)
                folders.forEach { appRepository.folderRepository.create(it) }
                logger.i(TAG, "Downloaded ${folders.size} folders from server")
            }

            if (webdavClient.exists(SyncPaths.notebooksDir())) {
                val notebookDirs = webdavClient.listCollection(SyncPaths.notebooksDir())
                logger.i(TAG, "Found ${notebookDirs.size} notebook(s) on server")
                notebookDirs.forEach { notebookDir ->
                    try {
                        val notebookId = notebookDir.trimEnd('/')
                        notebookSyncService.downloadNotebook(notebookId, webdavClient)
                    } catch (e: Exception) {
                        logger.e(TAG, "Failed to download $notebookDir: ${e.message}")
                    }
                }
            } else {
                logger.w(TAG, "${SyncPaths.notebooksDir()} doesn't exist on server")
            }

            logger.i(TAG, "FORCE DOWNLOAD complete")
            SyncResult.Success
        } catch (e: Exception) {
            logger.e(TAG, "Force download failed: ${e.message}")
            SyncResult.Failure(SyncError.UNKNOWN_ERROR)
        }
    }

    companion object {
        private const val TAG = "SyncForceService"
    }
}

