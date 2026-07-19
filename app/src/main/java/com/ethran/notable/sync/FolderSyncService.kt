package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.sync.serializers.FolderSerializer
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.flatMap
import com.ethran.notable.utils.map
import com.ethran.notable.utils.onFailure
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FolderSyncService @Inject constructor(
    private val appRepository: AppRepository,
) {
    private val folderSerializer = FolderSerializer
    private val log = SyncLogger

    suspend fun syncFolders(
        client: WebDAVClient,
        uploadOnly: Boolean,
        downloadOnly: Boolean = false
    ): AppResult<Unit, DomainError> {
        log.i(TAG, "Syncing folders...")
        val localFolders = appRepository.folderRepository.getAll()
        val remotePath = SyncPaths.foldersFile()

        val remoteExists = client.exists(remotePath).onFailure { return AppResult.Error(it) }
        if (remoteExists) {
            return client.getFileWithMetadata(remotePath).flatMap { remoteFile ->
                val remoteEtag = remoteFile.etag
                    ?: return@flatMap AppResult.Error(DomainError.SyncError("Missing ETag for $remotePath"))

                val remoteFoldersJson = remoteFile.content.decodeToString()
                val remoteFolders = folderSerializer.deserializeFolders(remoteFoldersJson)

                val folderMap = mutableMapOf<String, Folder>()
                remoteFolders.forEach { folderMap[it.id] = it }

                localFolders.forEach { local ->
                    val remote = folderMap[local.id]
                    if ((remote == null) || (local.updatedAt.after(remote.updatedAt))) {
                        folderMap[local.id] = local
                    }
                }

                val mergedFolders = folderMap.values.toList()

                if (!uploadOnly) {
                    for (folder in mergedFolders) {
                        val existing = appRepository.folderRepository.get(folder.id)
                        if (existing != null) {
                            appRepository.folderRepository.update(folder)
                        } else {
                            appRepository.folderRepository.create(folder)
                        }
                    }
                }

                // Download-only: apply the merge locally but never push folders.json.
                if (downloadOnly) {
                    AppResult.Success(Unit)
                } else {
                    val updatedFoldersJson = folderSerializer.serializeFolders(mergedFolders)
                    client.putFile(
                        remotePath,
                        updatedFoldersJson.toByteArray(),
                        "application/json",
                        ifMatch = remoteEtag
                    )
                }
            }.map { }
        } else {
            if (!downloadOnly && localFolders.isNotEmpty()) {
                val foldersJson = folderSerializer.serializeFolders(localFolders)
                return client.putFile(remotePath, foldersJson.toByteArray(), "application/json")
            }
        }
        return AppResult.Success(Unit)
    }

    companion object {
        private const val TAG = "FolderSyncService"
    }
}
