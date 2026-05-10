package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.sync.serializers.FolderSerializer
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.flatMap
import com.ethran.notable.utils.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FolderSyncService @Inject constructor(
    private val appRepository: AppRepository
) {
    private val folderSerializer = FolderSerializer

    suspend fun syncFolders(webdavClient: WebDAVClient): AppResult<Unit, DomainError> {
        SyncLogger.i(TAG, "Syncing folders...")
        val localFolders = appRepository.folderRepository.getAll()
        val remotePath = SyncPaths.foldersFile()

        if (webdavClient.exists(remotePath)) {
            return webdavClient.getFileWithMetadata(remotePath).flatMap { remoteFile ->
                val remoteEtag = remoteFile.etag
                    ?: return@flatMap AppResult.Error(DomainError.SyncError("Missing ETag for $remotePath"))

                val remoteFoldersJson = remoteFile.content.decodeToString()
                val remoteFolders = folderSerializer.deserializeFolders(remoteFoldersJson)

                val folderMap = mutableMapOf<String, Folder>()
                remoteFolders.forEach { folderMap[it.id] = it }

                localFolders.forEach { local ->
                    val remote = folderMap[local.id]
                    if (remote == null || local.updatedAt.after(remote.updatedAt)) {
                        folderMap[local.id] = local
                    }
                }

                val mergedFolders = folderMap.values.toList()
                for (folder in mergedFolders) {
                    val existing = appRepository.folderRepository.get(folder.id)
                    if (existing != null) {
                        appRepository.folderRepository.update(folder)
                    } else {
                        appRepository.folderRepository.create(folder)
                    }
                }

                val updatedFoldersJson = folderSerializer.serializeFolders(mergedFolders)
                webdavClient.putFile(
                    remotePath,
                    updatedFoldersJson.toByteArray(),
                    "application/json",
                    ifMatch = remoteEtag
                )
            }.map { }
        } else {
            if (localFolders.isNotEmpty()) {
                val foldersJson = folderSerializer.serializeFolders(localFolders)
                return webdavClient.putFile(remotePath, foldersJson.toByteArray(), "application/json")
            }
        }
        return AppResult.Success(Unit)
    }

    companion object {
        private const val TAG = "FolderSyncService"
    }
}
