package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.sync.serializers.FolderSerializer
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.ErrorAccumulator
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.onSuccess
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
    private val log = SyncLogger

    suspend fun forceUploadAll(): AppResult<Unit, DomainError> {
        log.i(TAG, "FORCE UPLOAD: Replacing server with local data")
        val settings = kvProxy.getSyncSettings()
        if (settings.username.isBlank() || settings.password.isBlank()) {
            return AppResult.Error(DomainError.SyncAuthError)
        }

        val client = webDavClientFactory.create(
            settings.serverUrl,
            settings.username,
            settings.password
        )

        val errors = ErrorAccumulator()

        // 1. Clean server notebooks
        if (client.exists(SyncPaths.notebooksDir())) {
            client.listCollection(SyncPaths.notebooksDir()).onSuccess { existingNotebooks ->
                log.i(TAG, "Deleting ${existingNotebooks.size} existing notebooks from server")
                existingNotebooks.forEach { notebookDir ->
                    client.delete(SyncPaths.notebookDir(notebookDir)).onError { error ->
                        log.w(TAG, "Failed to delete $notebookDir: ${error.userMessage}")
                        errors.add(error)
                    }
                }
            }.onError { error ->
                log.w(TAG, "Error listing server notebooks: ${error.userMessage}")
                errors.add(error)
            }
        }

        // 2. Ensure directories
        syncPreflightService.ensureServerDirectories(client)
            .onError { return AppResult.Error(it) }

        // 3. Upload folders
        val folders = appRepository.folderRepository.getAll()
        if (folders.isNotEmpty()) {
            val foldersJson = folderSerializer.serializeFolders(folders)
            client.putFile(
                SyncPaths.foldersFile(),
                foldersJson.toByteArray(),
                "application/json"
            ).onError { errors.add(it) }
        }

        // 4. Upload notebooks
        val notebooks = appRepository.bookRepository.getAll()
        log.i(TAG, "Uploading ${notebooks.size} local notebooks...")
        notebooks.forEach { notebook ->
            notebookSyncService.uploadNotebook(notebook, client).onSuccess {
                log.i(TAG, "Uploaded: ${notebook.title}")
            }.onError { error ->
                log.e(TAG, "Failed to upload ${notebook.title}: ${error.userMessage}")
                errors.add(error)
            }
        }

        return errors.asResult(Unit).onSuccess {
            log.i(TAG, "FORCE UPLOAD complete: ${notebooks.size} notebooks")
        }
    }

    suspend fun forceDownloadAll(): AppResult<Unit, DomainError> {
        log.i(TAG, "FORCE DOWNLOAD: Replacing local with server data")
        val settings = kvProxy.getSyncSettings()
        if (settings.username.isBlank() || settings.password.isBlank()) {
            return AppResult.Error(DomainError.SyncAuthError)
        }

        val client = webDavClientFactory.create(
            settings.serverUrl,
            settings.username,
            settings.password
        )

        val errors = ErrorAccumulator()

        // 1. Delete local data
        try {
            val localFolders = appRepository.folderRepository.getAll()
            localFolders.forEach { appRepository.folderRepository.delete(it.id) }

            val localNotebooks = appRepository.bookRepository.getAll()
            localNotebooks.forEach { appRepository.bookRepository.delete(it.id) }

            log.i(
                TAG,
                "Deleted ${localFolders.size} folders and ${localNotebooks.size} local notebooks"
            )
        } catch (e: Exception) {
            val error = DomainError.DatabaseError("Failed to clear local data: ${e.message}")
            return AppResult.Error(error)
        }

        // 2. Download folders
        if (client.exists(SyncPaths.foldersFile())) {
            client.getFile(SyncPaths.foldersFile()).onSuccess { foldersBytes ->
                val foldersJson = foldersBytes.decodeToString()
                try {
                    val folders = folderSerializer.deserializeFolders(foldersJson)
                    folders.forEach { appRepository.folderRepository.create(it) }
                    log.i(TAG, "Downloaded ${folders.size} folders from server")
                } catch (e: Exception) {
                    errors.add(DomainError.SyncError("Failed to process folders: ${e.message}"))
                }
            }.onError { errors.add(it) }
        }

        // 3. Download notebooks
        if (client.exists(SyncPaths.notebooksDir())) {
            client.listCollection(SyncPaths.notebooksDir()).onSuccess { notebookDirs ->
                log.i(TAG, "Found ${notebookDirs.size} notebook(s) on server")
                notebookDirs.forEach { notebookDir ->
                    val notebookId = notebookDir.trimEnd('/')
                    notebookSyncService.downloadNotebook(notebookId, client)
                        .onError { error ->
                            log.e(TAG, "Failed to download $notebookDir: ${error.userMessage}")
                            errors.add(error)
                        }
                }
            }.onError { errors.add(it) }
        } else {
            log.w(TAG, "${SyncPaths.notebooksDir()} doesn't exist on server")
        }

        return errors.asResult(Unit).onSuccess {
            log.i(TAG, "FORCE DOWNLOAD complete")
        }
    }

    companion object {
        private const val TAG = "SyncForceService"
    }
}
