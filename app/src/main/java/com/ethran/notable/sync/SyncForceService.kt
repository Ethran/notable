package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.sync.serializers.FolderSerializer
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.onSuccess
import com.ethran.notable.utils.plus
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncForceService @Inject constructor(
    private val appRepository: AppRepository,
    private val kvProxy: KvProxy,
    private val syncPreflightService: SyncPreflightService,
    private val notebookSyncService: NotebookSyncService,
    private val webDavClientFactory: WebDavClientFactoryPort
) {
    private val folderSerializer = FolderSerializer
    private val logger = SyncLogger

    suspend fun forceUploadAll(): AppResult<Unit, DomainError> {
        logger.i(TAG, "FORCE UPLOAD: Replacing server with local data")
        val settings = kvProxy.getSyncSettings()
        if (settings.username.isBlank() || settings.password.isBlank()) {
            return AppResult.Error(DomainError.SyncAuthError)
        }

        val webdavClient = webDavClientFactory.create(
            settings.serverUrl,
            settings.username,
            settings.password
        )

        var persistentError: DomainError? = null

        // 1. Clean server notebooks
        if (webdavClient.exists(SyncPaths.notebooksDir())) {
            webdavClient.listCollection(SyncPaths.notebooksDir()).onSuccess { existingNotebooks ->
                logger.i(TAG, "Deleting ${existingNotebooks.size} existing notebooks from server")
                existingNotebooks.forEach { notebookDir ->
                    webdavClient.delete(SyncPaths.notebookDir(notebookDir)).onError { error ->
                        logger.w(TAG, "Failed to delete $notebookDir: ${error.userMessage}")
                        persistentError = persistentError?.plus(error) ?: error
                    }
                }
            }.onError { error ->
                logger.w(TAG, "Error listing server notebooks: ${error.userMessage}")
                persistentError = persistentError?.plus(error) ?: error
            }
        }

        // 2. Ensure directories
        syncPreflightService.ensureServerDirectories(webdavClient)
            .onError { return AppResult.Error(it) }

        // 3. Upload folders
        val folders = appRepository.folderRepository.getAll()
        if (folders.isNotEmpty()) {
            val foldersJson = folderSerializer.serializeFolders(folders)
            webdavClient.putFile(
                SyncPaths.foldersFile(),
                foldersJson.toByteArray(),
                "application/json"
            ).onError { error ->
                persistentError = persistentError?.plus(error) ?: error
            }
        }

        // 4. Upload notebooks
        val notebooks = appRepository.bookRepository.getAll()
        logger.i(TAG, "Uploading ${notebooks.size} local notebooks...")
        notebooks.forEach { notebook ->
            notebookSyncService.uploadNotebook(notebook, webdavClient).onSuccess {
                logger.i(TAG, "Uploaded: ${notebook.title}")
            }.onError { error ->
                logger.e(TAG, "Failed to upload ${notebook.title}: ${error.userMessage}")
                persistentError = persistentError?.plus(error) ?: error
            }
        }

        return if (persistentError != null) AppResult.Error(persistentError)
        else {
            logger.i(TAG, "FORCE UPLOAD complete: ${notebooks.size} notebooks")
            AppResult.Success(Unit)
        }
    }

    suspend fun forceDownloadAll(): AppResult<Unit, DomainError> {
        logger.i(TAG, "FORCE DOWNLOAD: Replacing local with server data")
        val settings = kvProxy.getSyncSettings()
        if (settings.username.isBlank() || settings.password.isBlank()) {
            return AppResult.Error(DomainError.SyncAuthError)
        }

        val webdavClient = webDavClientFactory.create(
            settings.serverUrl,
            settings.username,
            settings.password
        )

        var persistentError: DomainError? = null

        // 1. Delete local data
        try {
            val localFolders = appRepository.folderRepository.getAll()
            localFolders.forEach { appRepository.folderRepository.delete(it.id) }

            val localNotebooks = appRepository.bookRepository.getAll()
            localNotebooks.forEach { appRepository.bookRepository.delete(it.id) }

            logger.i(
                TAG,
                "Deleted ${localFolders.size} folders and ${localNotebooks.size} local notebooks"
            )
        } catch (e: Exception) {
            val error = DomainError.DatabaseError("Failed to clear local data: ${e.message}")
            return AppResult.Error(error)
        }

        // 2. Download folders
        if (webdavClient.exists(SyncPaths.foldersFile())) {
            webdavClient.getFile(SyncPaths.foldersFile()).onSuccess { foldersBytes ->
                val foldersJson = foldersBytes.decodeToString()
                try {
                    val folders = folderSerializer.deserializeFolders(foldersJson)
                    folders.forEach { appRepository.folderRepository.create(it) }
                    logger.i(TAG, "Downloaded ${folders.size} folders from server")
                } catch (e: Exception) {
                    val error = DomainError.SyncError("Failed to process folders: ${e.message}")
                    persistentError = persistentError?.plus(error) ?: error
                }
            }.onError { error ->
                persistentError = persistentError?.plus(error) ?: error
            }
        }

        // 3. Download notebooks
        if (webdavClient.exists(SyncPaths.notebooksDir())) {
            webdavClient.listCollection(SyncPaths.notebooksDir()).onSuccess { notebookDirs ->
                logger.i(TAG, "Found ${notebookDirs.size} notebook(s) on server")
                notebookDirs.forEach { notebookDir ->
                    val notebookId = notebookDir.trimEnd('/')
                    notebookSyncService.downloadNotebook(notebookId, webdavClient)
                        .onError { error ->
                            logger.e(TAG, "Failed to download $notebookDir: ${error.userMessage}")
                            persistentError = persistentError?.plus(error) ?: error
                        }
                }
            }.onError { error ->
                persistentError = persistentError?.plus(error) ?: error
            }
        } else {
            logger.w(TAG, "${SyncPaths.notebooksDir()} doesn't exist on server")
        }

        return if (persistentError != null) AppResult.Error(persistentError)
        else {
            logger.i(TAG, "FORCE DOWNLOAD complete")
            AppResult.Success(Unit)
        }
    }

    companion object {
        private const val TAG = "SyncForceService"
    }
}
