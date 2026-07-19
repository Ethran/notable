package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.sync.serializers.FolderSerializer
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.ErrorAccumulator
import com.ethran.notable.utils.getOrElse
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.onFailure
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

        // 1. Ensure directories exist.
        syncPreflightService.ensureServerDirectories(client)
            .onError { return AppResult.Error(it) }

        // 2. Upload folders (folders.json is a single file, replaced wholesale).
        val folders = appRepository.folderRepository.getAll()
        if (folders.isNotEmpty()) {
            val foldersJson = folderSerializer.serializeFolders(folders)
            client.putFile(
                SyncPaths.foldersFile(),
                foldersJson.toByteArray(),
                "application/json"
            ).onError { errors.add(it) }
        }

        // 3. Upload all local notebooks first. Uploads are upserts, so there is no need to wipe the
        //    server beforehand -- doing so risked losing server data before the local copy is
        //    safely up (P3).
        val notebooks = appRepository.bookRepository.getAll()
        val localIds = notebooks.map { it.id }.toSet()
        log.i(TAG, "Uploading ${notebooks.size} local notebooks...")
        notebooks.forEach { notebook ->
            notebookSyncService.uploadNotebook(notebook, client).onSuccess {
                log.i(TAG, "Uploaded: ${notebook.title}")
            }.onError { error ->
                log.e(TAG, "Failed to upload ${notebook.title}: ${error.userMessage}")
                errors.add(error)
            }
        }

        // 4. Delete server notebooks that no longer exist locally, so the server ends up == local.
        client.listCollection(SyncPaths.notebooksDir()).onSuccess { serverDirs ->
            serverDirs.map { it.trimEnd('/') }.filter { it !in localIds }.forEach { extra ->
                log.i(TAG, "Deleting server notebook not present locally: $extra")
                client.delete(SyncPaths.notebookDir(extra)).onError { errors.add(it) }
            }
        }.onError { errors.add(it) }

        // 5. Record all local notebooks as synced (only on full success).
        if (!errors.hasErrors) markAllLocalSynced()

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

        // 1. Verify the server is reachable and actually has notebooks BEFORE touching local data.
        //    Deleting local first and only then discovering the server is unreachable or empty was
        //    a total-loss path (P3). We refuse to wipe unless the server has content to restore.
        val notebooksDirExists =
            client.exists(SyncPaths.notebooksDir()).onFailure { return AppResult.Error(it) }
        if (!notebooksDirExists) {
            return AppResult.Error(
                DomainError.SyncError("Server has no notebooks directory; refusing to wipe local data")
            )
        }
        val serverNotebookDirs =
            client.listCollection(SyncPaths.notebooksDir()).onFailure { return AppResult.Error(it) }
        if (serverNotebookDirs.isEmpty()) {
            return AppResult.Error(
                DomainError.SyncError("Server has no notebooks; refusing to wipe local data")
            )
        }

        // 2. Now safe to clear local data.
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

        // 3. Download folders.
        if (client.exists(SyncPaths.foldersFile()).onError { errors.add(it) }.getOrElse { false }) {
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

        // 4. Download notebooks (list already fetched in step 1).
        log.i(TAG, "Found ${serverNotebookDirs.size} notebook(s) on server")
        serverNotebookDirs.forEach { notebookDir ->
            val notebookId = notebookDir.trimEnd('/')
            notebookSyncService.downloadNotebook(notebookId, client)
                .onError { error ->
                    log.e(TAG, "Failed to download $notebookDir: ${error.userMessage}")
                    errors.add(error)
                }
        }

        // 5. Record downloaded notebooks as synced (only on full success).
        if (!errors.hasErrors) markAllLocalSynced()

        return errors.asResult(Unit).onSuccess {
            log.i(TAG, "FORCE DOWNLOAD complete")
        }
    }

    /** Record the current local notebook set as the last successfully synced set. */
    private suspend fun markAllLocalSynced() {
        val ids = appRepository.bookRepository.getAll().map { it.id }.toSet()
        kvProxy.setSyncSettings(kvProxy.getSyncSettings().copy(syncedNotebookIds = ids))
    }

    companion object {
        private const val TAG = "SyncForceService"
    }
}
