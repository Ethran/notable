package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.sync.serializers.FolderSerializer
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FolderSyncService @Inject constructor(
    private val appRepository: AppRepository
) {
    private val folderSerializer = FolderSerializer
    suspend fun syncFolders(webdavClient: WebDAVClient) {
        SyncLogger.i("FolderSyncService", "Syncing folders...")
        try {
            val localFolders = appRepository.folderRepository.getAll()
            val remotePath = SyncPaths.foldersFile()
            if (webdavClient.exists(remotePath)) {
                val remoteFile = webdavClient.getFileWithMetadata(remotePath)
                val remoteEtag = remoteFile.etag
                    ?: throw IOException("Missing ETag for $remotePath")
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
                    try {
                        appRepository.folderRepository.get(folder.id)
                        appRepository.folderRepository.update(folder)
                    } catch (_: Exception) {
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
                SyncLogger.i("FolderSyncService", "Synced ${mergedFolders.size} folders")
            } else {
                if (localFolders.isNotEmpty()) {
                    val foldersJson = folderSerializer.serializeFolders(localFolders)
                    webdavClient.putFile(remotePath, foldersJson.toByteArray(), "application/json")
                    SyncLogger.i(
                        "FolderSyncService",
                        "Uploaded ${localFolders.size} folders to server"
                    )
                }
            }
        } catch (e: Exception) {
            SyncLogger.e(
                "FolderSyncService",
                "Error syncing folders: ${e.message}\n${e.stackTraceToString()}"
            )
            throw e
        }
    }
}
